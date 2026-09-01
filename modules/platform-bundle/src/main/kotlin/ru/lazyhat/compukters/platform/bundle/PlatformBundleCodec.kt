/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import ru.lazyhat.compukters.worker.value.Sha256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PlatformBundleCodec {
    const val SUPPORTED_PLATFORM_ABI = 1

    private const val FORMAT_VERSION = 1
    private const val MAX_BUNDLE_BYTES = 128 * 1024 * 1024
    private const val MAX_BINARY_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_MODULES = 4096
    private const val MAX_DEPENDENCIES = 4096
    private const val MAX_SOURCES = 65_536
    private const val MAX_DECLARATIONS = 262_144
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), 'F'.code.toByte())

    fun assemble(
        languageVersion: String,
        platformAbi: Int,
        builtins: PlatformModule,
        modules: List<PlatformModule>,
    ): PlatformBundle {
        require(platformAbi == SUPPORTED_PLATFORM_ABI) { "unsupported platform ABI: $platformAbi" }
        require(languageVersion.isNotBlank()) { "platform language version must not be blank" }
        strictUtf8(languageVersion, "platform language version")
        val canonicalBuiltins = canonicalize(builtins)
        val canonicalModules = modules.map(::canonicalize).sortedBy(PlatformModule::id)
        val placeholder =
            PlatformBundle(
                PlatformIdentity(languageVersion, platformAbi, Sha256.of(ByteArray(32))),
                canonicalBuiltins,
                canonicalModules,
            )
        validate(placeholder)
        val contentHash = contentHash(semanticBytes(placeholder))
        return placeholder.copy(identity = placeholder.identity.copy(contentHash = contentHash))
    }

    fun encode(bundle: PlatformBundle): ByteArray {
        val canonical = assemble(bundle.identity.languageVersion, bundle.identity.platformAbi, bundle.builtins, bundle.modules)
        require(canonical == bundle) { "platform bundle is not canonical or has an invalid content hash" }
        val semantic = semanticBytes(canonical)
        return Sink()
            .apply {
                raw(MAGIC)
                u32(FORMAT_VERSION)
                raw(canonical.identity.contentHash.toByteArray())
                raw(semantic)
            }.result()
            .also { require(it.size <= MAX_BUNDLE_BYTES) { "platform bundle exceeds byte limit" } }
    }

    fun decode(bytes: ByteArray): PlatformBundle {
        require(bytes.size <= MAX_BUNDLE_BYTES) { "platform bundle exceeds byte limit" }
        val source = Source(bytes)
        require(source.raw(MAGIC.size).contentEquals(MAGIC)) { "invalid platform bundle magic" }
        val format = source.u32()
        require(format == FORMAT_VERSION) { "unsupported platform bundle format: $format" }
        val storedHash = Sha256.of(source.raw(32))
        val semanticStart = source.offset
        val languageVersion = source.string("platform language version")
        val platformAbi = source.u32()
        val builtins = source.module()
        val moduleCount = source.count(MAX_MODULES, "platform module")
        val modules = List(moduleCount) { source.module() }
        source.requireEnd()
        val semantic = bytes.copyOfRange(semanticStart, bytes.size)
        val actualHash = contentHash(semantic)
        require(storedHash == actualHash) { "platform bundle content hash mismatch" }
        val assembled = assemble(languageVersion, platformAbi, builtins, modules)
        require(assembled.identity.contentHash == storedHash) { "platform bundle content hash mismatch" }
        return assembled
    }

    private fun canonicalize(module: PlatformModule): PlatformModule =
        module.copy(
            dependencies = module.dependencies.sorted(),
            sources = module.sources.sortedBy(PlatformSource::path),
            declarations =
                module.declarations.sortedWith(
                    compareBy(
                        PlatformDeclaration::symbol,
                        PlatformDeclaration::signature,
                        PlatformDeclaration::sourcePath,
                        PlatformDeclaration::startUtf16,
                        PlatformDeclaration::endUtf16,
                    ),
                ),
        )

    private fun validate(bundle: PlatformBundle) {
        require(bundle.modules.size <= MAX_MODULES) { "platform module count exceeds limit" }
        val allModules = listOf(bundle.builtins) + bundle.modules
        require(allModules.map(PlatformModule::id).toSet().size == allModules.size) {
            "platform bundle contains duplicate module ids"
        }
        require(bundle.builtins.dependencies.isEmpty()) { "platform builtins must not have dependencies" }
        val sourceOwners = mutableMapOf<String, PlatformModuleId>()
        allModules.forEach { module ->
            require(module.version.isNotBlank()) { "platform module ${module.id} has a blank version" }
            strictUtf8(module.version, "platform module version")
            require(module.dependencies.size <= MAX_DEPENDENCIES) { "platform module ${module.id} has too many dependencies" }
            require(module.dependencies.toSet().size == module.dependencies.size) {
                "platform module ${module.id} has duplicate dependencies"
            }
            require(module.metadata.size <= MAX_BINARY_BYTES) { "platform module ${module.id} metadata exceeds byte limit" }
            require((module.libraryFragment?.size ?: 0) <= MAX_BINARY_BYTES) {
                "platform module ${module.id} library fragment exceeds byte limit"
            }
            require(module.sources.size <= MAX_SOURCES) { "platform module ${module.id} has too many sources" }
            require(module.declarations.size <= MAX_DECLARATIONS) { "platform module ${module.id} has too many declarations" }
            val sourceLengths = mutableMapOf<String, Int>()
            module.sources.forEach { source ->
                validatePath(source.path)
                val previous = sourceOwners.put(source.path, module.id)
                require(previous == null) { "duplicate platform source path ${source.path} in $previous and ${module.id}" }
                require(source.content.size <= MAX_BINARY_BYTES) { "platform source ${source.path} exceeds byte limit" }
                sourceLengths[source.path] = strictUtf8(source.content.toByteArray(), "platform source ${source.path}").length
            }
            val declarationKeys = mutableSetOf<Pair<String, String>>()
            module.declarations.forEach { declaration ->
                require(declaration.module == module.id) {
                    "platform declaration ${declaration.symbol} belongs to ${declaration.module}, expected ${module.id}"
                }
                strictUtf8(declaration.symbol, "platform declaration symbol")
                strictUtf8(declaration.signature, "platform declaration signature")
                val sourceLength =
                    requireNotNull(sourceLengths[declaration.sourcePath]) {
                        "platform declaration ${declaration.symbol} references unknown source ${declaration.sourcePath}"
                    }
                require(declaration.startUtf16 in 0..declaration.endUtf16 && declaration.endUtf16 <= sourceLength) {
                    "platform declaration ${declaration.symbol} has an invalid UTF-16 source range"
                }
                require(declarationKeys.add(declaration.symbol to declaration.signature)) {
                    "duplicate platform declaration ${declaration.symbol} ${declaration.signature}"
                }
            }
        }
        PlatformModuleGraph(bundle)
    }

    private fun validatePath(path: String) {
        strictUtf8(path, "platform source path")
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) { "invalid platform source path: $path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "invalid platform source path: $path" }
    }

    private fun semanticBytes(bundle: PlatformBundle): ByteArray =
        Sink()
            .apply {
                string(bundle.identity.languageVersion)
                u32(bundle.identity.platformAbi)
                module(bundle.builtins)
                count(bundle.modules.size)
                bundle.modules.forEach(::module)
            }.result()

    private fun contentHash(semantic: ByteArray): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MAGIC)
        digest.update(Sink().apply { u32(FORMAT_VERSION) }.result())
        digest.update(semantic)
        return Sha256.of(digest.digest())
    }

    private class Sink {
        private val output = ByteArrayOutputStream()

        fun result(): ByteArray = output.toByteArray()

        fun raw(value: ByteArray) {
            output.write(value)
        }

        fun u32(value: Int) {
            require(value >= 0) { "wire integer must be non-negative" }
            repeat(4) { shift -> output.write(value ushr (shift * 8)) }
        }

        fun count(value: Int) = u32(value)

        fun string(value: String) = bytes(strictUtf8(value, "platform bundle text").encodeToByteArray())

        fun bytes(value: ByteArray) {
            require(value.size <= MAX_BINARY_BYTES) { "platform binary value exceeds byte limit" }
            u32(value.size)
            raw(value)
        }

        fun immutableBytes(value: ImmutableBytes) = bytes(value.toByteArray())

        fun moduleId(value: PlatformModuleId) {
            string(value.namespace)
            string(value.name)
        }

        fun module(value: PlatformModule) {
            moduleId(value.id)
            string(value.version)
            count(value.dependencies.size)
            value.dependencies.forEach(::moduleId)
            immutableBytes(value.metadata)
            output.write(if (value.libraryFragment == null) 0 else 1)
            value.libraryFragment?.let(::immutableBytes)
            count(value.sources.size)
            value.sources.forEach { source ->
                string(source.path)
                immutableBytes(source.content)
            }
            count(value.declarations.size)
            value.declarations.forEach { declaration ->
                string(declaration.symbol)
                string(declaration.signature)
                moduleId(declaration.module)
                string(declaration.sourcePath)
                u32(declaration.startUtf16)
                u32(declaration.endUtf16)
                output.write(if (declaration.trustedExternal) 1 else 0)
            }
        }
    }

    private class Source(
        private val bytes: ByteArray,
    ) {
        var offset: Int = 0
            private set

        fun raw(size: Int): ByteArray {
            require(size >= 0 && size <= bytes.size - offset) { "truncated platform bundle" }
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun u8(): Int = raw(1)[0].toInt() and 0xff

        fun u32(): Int {
            val value = raw(4)
            val unsigned =
                value.indices.fold(0u) { result, index ->
                    result or ((value[index].toUInt() and 0xffu) shl (index * 8))
                }
            require(unsigned <= Int.MAX_VALUE.toUInt()) { "platform wire integer exceeds supported range" }
            return unsigned.toInt()
        }

        fun count(
            maximum: Int,
            description: String,
        ): Int = u32().also { require(it <= maximum) { "$description count exceeds limit" } }

        fun string(description: String): String = strictUtf8(binary(), description)

        fun binary(): ByteArray {
            val size = u32()
            require(size <= MAX_BINARY_BYTES) { "platform binary value exceeds byte limit" }
            return raw(size)
        }

        fun immutableBytes(): ImmutableBytes = ImmutableBytes.of(binary())

        fun moduleId(): PlatformModuleId = PlatformModuleId(string("module namespace"), string("module name"))

        fun module(): PlatformModule {
            val id = moduleId()
            val version = string("module version")
            val dependencies = List(count(MAX_DEPENDENCIES, "module dependency")) { moduleId() }
            val metadata = immutableBytes()
            val libraryFragment =
                when (val presence = u8()) {
                    0 -> null
                    1 -> immutableBytes()
                    else -> throw IllegalArgumentException("invalid library fragment presence: $presence")
                }
            val sources =
                List(count(MAX_SOURCES, "platform source")) {
                    PlatformSource(string("platform source path"), immutableBytes())
                }
            val declarations =
                List(count(MAX_DECLARATIONS, "platform declaration")) {
                    PlatformDeclaration(
                        symbol = string("platform declaration symbol"),
                        signature = string("platform declaration signature"),
                        module = moduleId(),
                        sourcePath = string("platform declaration source path"),
                        startUtf16 = u32(),
                        endUtf16 = u32(),
                        trustedExternal =
                            when (val value = u8()) {
                                0 -> false
                                1 -> true
                                else -> throw IllegalArgumentException("invalid trusted external flag: $value")
                            },
                    )
                }
            return PlatformModule(id, version, dependencies, metadata, libraryFragment, sources, declarations)
        }

        fun requireEnd() {
            require(offset == bytes.size) { "platform bundle contains trailing bytes" }
        }
    }

    private fun strictUtf8(
        value: String,
        description: String,
    ): String {
        try {
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("$description must be strict UTF-8", failure)
        }
        require(value.encodeToByteArray().size <= MAX_TEXT_BYTES) { "$description exceeds text byte limit" }
        return value
    }

    private fun strictUtf8(
        bytes: ByteArray,
        description: String,
    ): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("$description must be strict UTF-8", failure)
        }
}
