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

package ru.lazyhat.compukters.addon.api

import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import ru.lazyhat.compukters.worker.value.Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream

object AddonGuestApiBundleCodec {
    private const val FORMAT_VERSION = 1
    private const val METADATA_ENTRY = "META-INF/compukters/module.cpm"
    private val MANIFEST_BYTES = "Manifest-Version: 1.0\r\n\r\n".encodeToByteArray()
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte())
    private val COMPONENT = Regex("[a-z][a-z0-9-]{0,63}")
    private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,2}(?:[-+][0-9A-Za-z.-]+)?")

    fun assemble(
        addon: String,
        platformAbi: Int,
        module: PlatformModule,
        capabilitySchemas: List<AddonCapabilitySchema>,
        bindings: List<AddonGuestApiBinding>,
        includeSources: Boolean = true,
    ): AddonGuestApiBundle {
        val metadataModule = module.copy(sources = emptyList())
        val metadataJar = metadataJar(metadataModule)
        val sourcesJar = if (includeSources) sourcesJar(module.sources) else null
        return assembleEncoded(addon, platformAbi, metadataJar, sourcesJar, capabilitySchemas, bindings)
    }

    fun encode(bundle: AddonGuestApiBundle): ByteArray {
        val canonical =
            assembleEncoded(
                bundle.identity.addon,
                bundle.identity.platformAbi,
                bundle.metadataJar.toByteArray(),
                bundle.sourcesJar?.toByteArray(),
                bundle.capabilitySchemas,
                bundle.bindings,
            )
        require(canonical == bundle) { "addon guest API bundle is not canonical or has an invalid content hash" }
        val semantic = semanticBytes(canonical)
        return ByteArrayOutputStream()
            .also { output ->
                DataOutputStream(output).use { sink ->
                    sink.write(MAGIC)
                    sink.writeInt(FORMAT_VERSION)
                    sink.write(canonical.identity.contentHash.toByteArray())
                    sink.write(semantic)
                }
            }.toByteArray()
            .also { require(it.size <= AddonGuestApiLimits.MAXIMUM_BUNDLE_BYTES) { "addon guest API bundle exceeds byte limit" } }
    }

    fun decode(bytes: ByteArray): AddonGuestApiBundle {
        require(bytes.size <= AddonGuestApiLimits.MAXIMUM_BUNDLE_BYTES) { "addon guest API bundle exceeds byte limit" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "invalid addon guest API bundle magic" }
        require(input.readInt() == FORMAT_VERSION) { "unsupported addon guest API bundle format" }
        val storedHash = Sha256.of(input.readNBytes(32).also { require(it.size == 32) { "truncated addon guest API bundle hash" } })
        val addon = input.text("addon identity")
        val platformAbi = input.readInt().also { require(it >= 0) { "invalid addon platform ABI" } }
        val metadataJar = input.bytes(AddonGuestApiLimits.MAXIMUM_METADATA_BYTES, "metadata JAR")
        val sourcesJar =
            when (val present = input.readUnsignedByte()) {
                0 -> null
                1 -> input.bytes(AddonGuestApiLimits.MAXIMUM_SOURCE_BYTES, "sources JAR")
                else -> throw IllegalArgumentException("invalid sources JAR presence: $present")
            }
        val schemas =
            List(input.count(AddonGuestApiLimits.MAXIMUM_CAPABILITIES, "capability schema")) {
                val identity =
                    AddonCapabilityIdentity(
                        input.text("capability namespace"),
                        input.text("capability name"),
                        input.readUnsignedShort(),
                        input.readUnsignedShort(),
                    )
                val operations =
                    List(input.count(AddonGuestApiLimits.MAXIMUM_OPERATIONS, "capability operation")) {
                        val asynchronous = input.boolean("capability asynchronous flag")
                        val result = input.valueType()
                        val arguments =
                            List(input.count(AddonGuestApiLimits.MAXIMUM_ARGUMENTS, "capability argument")) { input.valueType() }
                        AddonCapabilityOperation(arguments, result, asynchronous)
                    }
                AddonCapabilitySchema(identity, operations)
            }
        val bindings =
            List(input.count(AddonGuestApiLimits.MAXIMUM_BINDINGS, "addon binding")) {
                AddonGuestApiBinding(
                    input.text("binding package"),
                    input.optionalText("binding owner"),
                    input.text("binding callable name"),
                    input.text("binding signature"),
                    AddonCapabilityIdentity(
                        input.text("binding capability namespace"),
                        input.text("binding capability name"),
                        input.readUnsignedShort(),
                        input.readUnsignedShort(),
                    ),
                    input.readInt().also { require(it >= 0) { "addon binding operation must be non-negative" } },
                )
            }
        require(input.read() == -1) { "addon guest API bundle contains trailing bytes" }
        val assembled = assembleEncoded(addon, platformAbi, metadataJar, sourcesJar, schemas, bindings)
        require(assembled.identity.contentHash == storedHash) { "addon guest API bundle content hash mismatch" }
        return assembled
    }

    private fun assembleEncoded(
        addon: String,
        platformAbi: Int,
        metadataJar: ByteArray,
        sourcesJar: ByteArray?,
        capabilitySchemas: List<AddonCapabilitySchema>,
        bindings: List<AddonGuestApiBinding>,
    ): AddonGuestApiBundle {
        require(COMPONENT.matches(addon)) { "invalid addon identity: $addon" }
        require(platformAbi == PlatformBundleCodec.SUPPORTED_PLATFORM_ABI) { "unsupported addon platform ABI: $platformAbi" }
        require(metadataJar.size <= AddonGuestApiLimits.MAXIMUM_METADATA_BYTES) { "addon metadata JAR exceeds byte limit" }
        require((sourcesJar?.size ?: 0) <= AddonGuestApiLimits.MAXIMUM_SOURCE_BYTES) { "addon sources JAR exceeds byte limit" }
        val metadataEntries = readArchive(metadataJar, AddonGuestApiLimits.MAXIMUM_METADATA_BYTES, sources = false)
        require(metadataEntries.keys == setOf(METADATA_ENTRY)) { "addon metadata JAR contains undeclared entries" }
        require(metadataJar.contentEquals(archive(metadataEntries, AddonGuestApiLimits.MAXIMUM_METADATA_BYTES))) {
            "addon metadata JAR is not canonical"
        }
        val metadataModule = PlatformBundleCodec.decodeModule(metadataEntries.getValue(METADATA_ENTRY))
        require(metadataModule.sources.isEmpty()) { "addon metadata module must not embed sources" }
        require(metadataModule.id.namespace == addon) { "addon module namespace must equal addon identity" }
        require(VERSION.matches(metadataModule.version)) { "invalid addon module version: ${metadataModule.version}" }
        val sources =
            sourcesJar
                ?.let { sourceBytes ->
                    readArchive(sourceBytes, AddonGuestApiLimits.MAXIMUM_SOURCE_BYTES, sources = true).also { entries ->
                        require(sourceBytes.contentEquals(archive(entries, AddonGuestApiLimits.MAXIMUM_SOURCE_BYTES))) {
                            "addon sources JAR is not canonical"
                        }
                    }
                }.orEmpty()
        require(sources.size <= AddonGuestApiLimits.MAXIMUM_SOURCE_FILES) { "addon source file count exceeds limit" }
        val module = metadataModule.copy(sources = sources.map { (path, content) -> PlatformSource(path, ImmutableBytes.of(content)) })
        val orderedSchemas =
            capabilitySchemas.sortedWith(
                compareBy({
                    it.identity.namespace
                }, { it.identity.name }, { it.identity.abiMajor }, { it.identity.abiMinor }),
            )
        val orderedBindings = bindings.sortedWith(compareBy(AddonGuestApiBinding::symbol, AddonGuestApiBinding::signature))
        validate(addon, module, orderedSchemas, orderedBindings)
        val placeholder =
            AddonGuestApiBundle(
                AddonGuestApiIdentity(addon, module.id.toString(), module.version, platformAbi, Sha256.of(ByteArray(32))),
                module,
                metadataJar,
                sourcesJar,
                orderedSchemas,
                orderedBindings,
            )
        val hash = contentHash(semanticBytes(placeholder))
        return AddonGuestApiBundle(
            placeholder.identity.copy(contentHash = hash),
            module,
            metadataJar,
            sourcesJar,
            orderedSchemas,
            orderedBindings,
        )
    }

    private fun validate(
        addon: String,
        module: PlatformModule,
        schemas: List<AddonCapabilitySchema>,
        bindings: List<AddonGuestApiBinding>,
    ) {
        require(schemas.size <= AddonGuestApiLimits.MAXIMUM_CAPABILITIES) { "addon capability count exceeds limit" }
        require(bindings.size <= AddonGuestApiLimits.MAXIMUM_BINDINGS) { "addon binding count exceeds limit" }
        require(schemas.distinctBy { Triple(it.identity.namespace, it.identity.name, it.identity.abiMajor) }.size == schemas.size) {
            "addon bundle contains duplicate capability identities"
        }
        require(bindings.distinctBy { it.symbol to it.signature }.size == bindings.size) { "addon bundle contains duplicate bindings" }
        bindings.forEach { binding ->
            require(binding.packageName == addon || binding.packageName.startsWith("$addon.")) {
                "addon binding package escapes namespace: ${binding.packageName}"
            }
            require(binding.owner?.split('.')?.all(::validKotlinIdentifier) != false) { "invalid addon binding owner: ${binding.owner}" }
            require(validKotlinIdentifier(binding.callableName)) { "invalid addon binding callable name: ${binding.callableName}" }
        }
        schemas.forEach { schema ->
            val identity = schema.identity
            require(identity.namespace == addon) { "addon capability namespace must equal addon identity" }
            require(COMPONENT.matches(identity.name)) { "invalid addon capability name: ${identity.name}" }
            require(identity.abiMajor in 1..UShort.MAX_VALUE.toInt()) { "addon capability ABI major is out of range" }
            require(identity.abiMinor in 0..UShort.MAX_VALUE.toInt()) { "addon capability ABI minor is out of range" }
        }
        schemas.flatMap(AddonCapabilitySchema::operations).forEach { operation ->
            require(AddonCapabilityValueType.UNIT !in operation.arguments) { "addon capability arguments cannot use Unit" }
            require(
                operation.arguments.size <= AddonGuestApiLimits.MAXIMUM_ARGUMENTS,
            ) { "addon capability operation has too many arguments" }
        }
        require(schemas.all { it.operations.isNotEmpty() && it.operations.size <= AddonGuestApiLimits.MAXIMUM_OPERATIONS }) {
            "addon capability operation count is outside limit"
        }
        module.dependencies.forEach { dependency -> require(dependency != module.id) { "addon module must not depend on itself" } }
        require(module.dependencies.toSet().size == module.dependencies.size) { "addon module contains duplicate dependencies" }
        require(module.declarations.size <= AddonGuestApiLimits.MAXIMUM_DECLARATIONS) { "addon declaration count exceeds limit" }
        require(module.completionDeclarations.size <= AddonGuestApiLimits.MAXIMUM_DECLARATIONS) {
            "addon completion declaration count exceeds limit"
        }
        require(module.declarations.distinctBy { it.symbol to it.signature }.size == module.declarations.size) {
            "addon module contains duplicate declarations"
        }
        require(module.completionDeclarations.distinctBy { it.symbol to it.signature }.size == module.completionDeclarations.size) {
            "addon module contains duplicate completion declarations"
        }
        val sources = module.sources.associateBy(PlatformSource::path)
        require(sources.size == module.sources.size) { "addon bundle contains duplicate source paths" }
        sources.forEach { (path, source) ->
            require(path.startsWith("$addon/") || path.startsWith("libraries/$addon-")) {
                "addon source path must be owned by $addon: $path"
            }
            require(path.endsWith(".kt")) { "addon sources JAR may contain only Kotlin sources: $path" }
            strictUtf8(source.content.toByteArray(), "addon source $path")
        }
        module.declarations.forEach { declaration -> validateDeclaration(addon, module, declaration, sources) }
        module.completionDeclarations.forEach { declaration ->
            require(declaration.module == module.id) { "addon completion declaration belongs to another module" }
            require(declaration.symbol.startsWith("$addon.")) { "addon completion declaration escapes namespace: ${declaration.symbol}" }
            require(declaration.startUtf16 in 0..declaration.endUtf16) { "addon completion declaration has an invalid source range" }
            validateSourcePath(declaration.sourcePath)
        }
        val external = module.declarations.filter(PlatformDeclaration::trustedExternal).associateBy { it.symbol to it.signature }
        require(
            external.size == module.declarations.count(PlatformDeclaration::trustedExternal),
        ) { "addon external declarations must be unique" }
        require(external.keys == bindings.mapTo(mutableSetOf()) { it.symbol to it.signature }) {
            "addon bindings must exactly match external declarations"
        }
        val schemasByIdentity = schemas.associateBy { it.identity }
        bindings.forEach { binding ->
            val schema = requireNotNull(schemasByIdentity[binding.capability]) { "addon binding references an undeclared capability" }
            val operation =
                schema.operations.getOrNull(binding.operation)
                    ?: throw IllegalArgumentException("addon binding operation is outside capability schema")
            require(signatureTypes(binding.signature) == operation.arguments to operation.result) {
                "addon binding signature does not match capability operation: ${binding.symbol} ${binding.signature}"
            }
        }
    }

    private fun validateDeclaration(
        addon: String,
        module: PlatformModule,
        declaration: PlatformDeclaration,
        sources: Map<String, PlatformSource>,
    ) {
        require(declaration.module == module.id) { "addon declaration belongs to another module" }
        require(declaration.symbol.startsWith("$addon.")) { "addon declaration escapes namespace: ${declaration.symbol}" }
        require(declaration.signature.isNotBlank()) { "addon declaration has a blank signature" }
        validateSourcePath(declaration.sourcePath)
        require(declaration.startUtf16 in 0..declaration.endUtf16) { "addon declaration has an invalid source range" }
        sources[declaration.sourcePath]?.let { source ->
            val length = strictUtf8(source.content.toByteArray(), "addon source ${source.path}").length
            require(declaration.endUtf16 <= length) { "addon declaration source range exceeds ${source.path}" }
        }
    }

    private fun validateSourcePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) { "invalid addon source path: $path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "invalid addon source path: $path" }
    }

    private fun validKotlinIdentifier(value: String): Boolean =
        value.isNotEmpty() && (value.first() == '_' || value.first().isLetter()) && value.drop(1).all { it == '_' || it.isLetterOrDigit() }

    private fun signatureTypes(signature: String): Pair<List<AddonCapabilityValueType>, AddonCapabilityValueType> {
        val match =
            Regex("fun\\(([^)]*)\\):([A-Za-z0-9_.]+)").matchEntire(signature)
                ?: throw IllegalArgumentException("addon external binding must use a canonical function signature")
        val arguments =
            match.groupValues[1]
                .takeIf(String::isNotEmpty)
                ?.split(',')
                ?.map(::hostType)
                .orEmpty()
        return arguments to hostType(match.groupValues[2])
    }

    private fun hostType(value: String): AddonCapabilityValueType =
        when (value.substringAfterLast('.')) {
            "Unit" -> AddonCapabilityValueType.UNIT
            "Int" -> AddonCapabilityValueType.I32
            "Long" -> AddonCapabilityValueType.I64
            "Float" -> AddonCapabilityValueType.F32
            "Double" -> AddonCapabilityValueType.F64
            "Boolean" -> AddonCapabilityValueType.BOOL
            "Char" -> AddonCapabilityValueType.CHAR
            "String" -> AddonCapabilityValueType.STRING
            else -> throw IllegalArgumentException("unsupported addon capability ABI type: $value")
        }

    private fun semanticBytes(bundle: AddonGuestApiBundle): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                DataOutputStream(output).use { sink ->
                    sink.text(bundle.identity.addon)
                    sink.writeInt(bundle.identity.platformAbi)
                    sink.bytes(bundle.metadataJar.toByteArray())
                    val sources = bundle.sourcesJar
                    sink.writeByte(if (sources == null) 0 else 1)
                    sources?.let { sink.bytes(it.toByteArray()) }
                    sink.writeInt(bundle.capabilitySchemas.size)
                    bundle.capabilitySchemas.forEach { schema ->
                        sink.text(schema.identity.namespace)
                        sink.text(schema.identity.name)
                        sink.writeShort(schema.identity.abiMajor)
                        sink.writeShort(schema.identity.abiMinor)
                        sink.writeInt(schema.operations.size)
                        schema.operations.forEach { operation ->
                            sink.writeByte(if (operation.asynchronous) 1 else 0)
                            sink.writeByte(operation.result.ordinal)
                            sink.writeInt(operation.arguments.size)
                            operation.arguments.forEach { argument -> sink.writeByte(argument.ordinal) }
                        }
                    }
                    sink.writeInt(bundle.bindings.size)
                    bundle.bindings.forEach { binding ->
                        sink.text(binding.packageName)
                        sink.writeByte(if (binding.owner == null) 0 else 1)
                        binding.owner?.let { owner -> sink.text(owner) }
                        sink.text(binding.callableName)
                        sink.text(binding.signature)
                        sink.text(binding.capability.namespace)
                        sink.text(binding.capability.name)
                        sink.writeShort(binding.capability.abiMajor)
                        sink.writeShort(binding.capability.abiMinor)
                        sink.writeInt(binding.operation)
                    }
                }
            }.toByteArray()

    private fun contentHash(bytes: ByteArray): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MAGIC)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(FORMAT_VERSION).array())
        digest.update(bytes)
        return Sha256.of(digest.digest())
    }

    private fun metadataJar(module: PlatformModule): ByteArray =
        archive(mapOf(METADATA_ENTRY to PlatformBundleCodec.encodeModule(module)), AddonGuestApiLimits.MAXIMUM_METADATA_BYTES)

    private fun sourcesJar(sources: List<PlatformSource>): ByteArray? =
        sources.takeIf(List<PlatformSource>::isNotEmpty)?.associate { it.path to it.content.toByteArray() }?.let {
            archive(it, AddonGuestApiLimits.MAXIMUM_SOURCE_BYTES)
        }

    private fun archive(
        entries: Map<String, ByteArray>,
        maximumBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            JarEntry("META-INF/MANIFEST.MF").also { entry ->
                entry.time = 0L
                jar.putNextEntry(entry)
                jar.write(MANIFEST_BYTES)
                jar.closeEntry()
            }
            entries.toSortedMap().forEach { (name, bytes) ->
                JarEntry(name).also { entry ->
                    entry.time = 0L
                    jar.putNextEntry(entry)
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
        return output.toByteArray().also { require(it.size <= maximumBytes) { "addon archive exceeds byte limit" } }
    }

    private fun readArchive(
        bytes: ByteArray,
        maximumBytes: Int,
        sources: Boolean,
    ): Map<String, ByteArray> {
        require(bytes.size <= maximumBytes) { "addon archive exceeds byte limit" }
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        JarInputStream(ByteArrayInputStream(bytes), true).use { jar ->
            requireNotNull(jar.manifest) { "addon archive manifest is missing" }
            while (true) {
                val entry = jar.nextJarEntry ?: break
                require(!entry.isDirectory) { "addon archive contains an explicit directory" }
                val name = entry.name
                require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name) { "invalid addon archive path: $name" }
                require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "invalid addon archive path: $name" }
                require(!name.endsWith(".class", true)) { "addon archive contains JVM bytecode: $name" }
                require(!name.startsWith("META-INF/services/", true)) { "addon archive contains a service provider: $name" }
                require(!name.endsWith(".jar", true) && !name.endsWith(".zip", true)) { "addon archive contains a nested archive: $name" }
                require(sources || name == METADATA_ENTRY) { "addon metadata JAR contains forbidden entry: $name" }
                require(!sources || name.endsWith(".kt")) { "addon sources JAR contains a non-Kotlin entry: $name" }
                require(entries.size < AddonGuestApiLimits.MAXIMUM_ARCHIVE_ENTRIES) { "addon archive entry count exceeds limit" }
                val content = jar.readNBytes(maximumBytes + 1)
                require(content.size <= maximumBytes) { "addon archive entry exceeds byte limit" }
                expandedBytes = Math.addExact(expandedBytes, content.size.toLong())
                require(expandedBytes <= maximumBytes) { "addon archive expanded bytes exceed limit" }
                require(entries.put(name, content) == null) { "addon archive contains duplicate entry: $name" }
            }
        }
        return entries.toSortedMap()
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

    private fun DataOutputStream.text(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= AddonGuestApiLimits.MAXIMUM_TEXT_BYTES) { "addon bundle text exceeds byte limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.bytes(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.text(description: String): String =
        strictUtf8(bytes(AddonGuestApiLimits.MAXIMUM_TEXT_BYTES, description), description)

    private fun DataInputStream.bytes(
        maximum: Int,
        description: String,
    ): ByteArray {
        val size = readInt().also { require(it in 0..maximum) { "$description exceeds byte limit" } }
        return readNBytes(size).also { require(it.size == size) { "truncated $description" } }
    }

    private fun DataInputStream.count(
        maximum: Int,
        description: String,
    ): Int = readInt().also { require(it in 0..maximum) { "$description count exceeds limit" } }

    private fun DataInputStream.boolean(description: String): Boolean =
        when (val value = readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("invalid $description: $value")
        }

    private fun DataInputStream.optionalText(description: String): String? =
        when (val value = readUnsignedByte()) {
            0 -> null
            1 -> text(description)
            else -> throw IllegalArgumentException("invalid $description presence: $value")
        }

    private fun DataInputStream.valueType(): AddonCapabilityValueType =
        AddonCapabilityValueType.entries.getOrNull(readUnsignedByte()) ?: throw IllegalArgumentException("invalid addon value type")
}
