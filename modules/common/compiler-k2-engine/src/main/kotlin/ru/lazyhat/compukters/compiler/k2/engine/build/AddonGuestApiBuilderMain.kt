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

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.addon.api.AddonCapabilityIdentity
import ru.lazyhat.compukters.addon.api.AddonCapabilityOperation
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.k2.engine.PlatformCapabilityShape
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.AddonIntrinsicContract
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.AddonTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.PlatformCapabilityId
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readLines

fun buildAddonGuestApiBundle(
    base: PlatformBundle,
    addonSourceRoot: Path,
    contract: AddonContract,
): ByteArray {
    val availableModules = (listOf(base.builtins) + base.modules).associateBy(PlatformModule::id)
    contract.dependencies.forEach { dependency ->
        require(dependency in availableModules) { "addon module ${contract.module} depends on unavailable module $dependency" }
    }
    val workspace = Files.createTempDirectory("compukters-addon-platform-")
    try {
        val baseModules = listOf(base.builtins) + base.modules
        baseModules.flatMap(PlatformModule::sources).forEach { source -> writeSource(workspace, source) }
        val addonSources = discoverAddonSources(addonSourceRoot)
        require(addonSources.isNotEmpty()) { "addon module ${contract.module} has no Kotlin sources" }
        val stagedAddonSources =
            addonSources.map { relative ->
                require(relative.startsWith("${contract.addon}/")) {
                    "addon source path must be owned by ${contract.addon}: $relative"
                }
                val staged = relative
                val target = workspace.resolve(staged).normalize()
                require(target.startsWith(workspace)) { "addon source escapes build workspace: $relative" }
                target.parent.createDirectories()
                Files.copy(addonSourceRoot.resolve(relative), target)
                staged
            }
        val descriptor = workspace.resolve("modules.toml")
        Files.writeString(descriptor, renderCatalog(baseModules, contract, stagedAddonSources))
        val addonIntrinsic = AddonIntrinsicContract(contract.module, contract.schemas, contract.bindings)
        val registry = AddonTrustedIntrinsics.extend(CanonicalTrustedIntrinsics.registry, listOf(addonIntrinsic))
        val capabilities =
            CanonicalTrustedIntrinsics.executableCapabilities +
                contract.schemas.mapTo(mutableSetOf()) { schema ->
                    PlatformCapabilityId(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor)
                }
        val capabilityShapes =
            contract.schemas.associate { schema ->
                PlatformCapabilityId(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor) to
                    PlatformCapabilityShape(schema.identity.abiMinor, schema.operations.size.toUInt())
            }
        val combined = PlatformBundleBuilder(registry, capabilities, capabilityShapes).build(workspace, descriptor)
        val module =
            combined.modules.singleOrNull { it.id == contract.module }
                ?: error("platform contains no unique addon module ${contract.module}")
        require(module.version == contract.version) {
            "addon module ${contract.module} version ${module.version} does not match contract ${contract.version}"
        }
        require(module.dependencies == contract.dependencies) {
            "addon module ${contract.module} dependencies ${module.dependencies} do not match contract ${contract.dependencies}"
        }
        require(combined.modules.none { contract.module in it.dependencies }) {
            "base platform module depends on addon module ${contract.module}"
        }
        val addon =
            AddonGuestApiBundleCodec.assemble(
                contract.addon,
                combined.identity.platformAbi,
                module,
                contract.schemas,
                contract.bindings,
                includeSources = true,
            )
        return AddonGuestApiBundleCodec.encode(addon)
    } finally {
        Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

fun main(args: Array<String>) {
    val arguments = args.toList().windowed(2, 2, partialWindows = false).associate { (name, value) -> name to value }
    val platformInput = arguments.path("--platform-input")
    val sourceRoot = arguments.path("--sources")
    val descriptor = arguments.path("--descriptor")
    val abiLock = arguments.path("--abi-lock")
    val output = arguments.path("--output")
    val hostOutput = arguments["--host-output"]?.let { Path.of(it).toAbsolutePath().normalize() }
    val base = PlatformBundleCodec.decode(Files.readAllBytes(platformInput))
    val authoring = AddonAuthoringContract.parse(descriptor.readLines())
    val currentLock = if (Files.exists(abiLock)) AddonAbiLock.parse(abiLock.readLines()) else AddonAbiLock.empty()
    val resolved = resolveAddonContract(sourceRoot, authoring, currentLock, base.modules.map(PlatformModule::id))
    val updateLock = arguments["--update-abi-lock"]?.toBooleanStrict() ?: false
    if (updateLock) {
        writeAtomically(abiLock, resolved.expectedLock.render().encodeToByteArray())
    } else {
        require(Files.exists(abiLock)) { "addon ABI lock is missing; run updateAddonGuestApiAbiLock" }
        require(currentLock == resolved.expectedLock && Files.readString(abiLock) == currentLock.render()) {
            "addon ABI lock is stale; run updateAddonGuestApiAbiLock and review the ABI change"
        }
    }
    writeAtomically(output, buildAddonGuestApiBundle(base, sourceRoot, resolved.contract))
    hostOutput?.let { path -> writeAtomically(path, renderAddonHostContract(authoring, resolved.contract).encodeToByteArray()) }
}

private fun writeSource(
    root: Path,
    source: PlatformSource,
) {
    val target = root.resolve(source.path).normalize()
    require(target.startsWith(root)) { "base platform source escapes build workspace: ${source.path}" }
    target.parent.createDirectories()
    require(Files.notExists(target)) { "duplicate base platform source: ${source.path}" }
    Files.write(target, source.content.toByteArray())
}

private fun discoverAddonSources(root: Path): List<String> =
    Files.walk(root).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .map { root.relativize(it).invariantSeparatorsPathString }
            .sorted()
            .toList()
    }

private fun renderCatalog(
    baseModules: List<PlatformModule>,
    contract: AddonContract,
    addonSources: List<String>,
): String {
    val modules =
        baseModules.map { module ->
            CatalogModule(
                module.id.toString(),
                module.version,
                module.dependencies.map(Any::toString),
                module.sources.map(PlatformSource::path),
            )
        } + CatalogModule(contract.module.toString(), contract.version, contract.dependencies.map(Any::toString), addonSources)
    return buildString {
        modules.forEach { (id, version, dependencies, sources) ->
            appendLine("[[module]]")
            appendLine("id = \"$id\"")
            appendLine("version = \"$version\"")
            appendLine("dependencies = ${dependencies.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}")
            appendLine("sources = ${sources.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}")
            appendLine()
        }
    }
}

private data class CatalogModule(
    val id: String,
    val version: String,
    val dependencies: List<String>,
    val sources: List<String>,
)

data class AddonContract(
    val addon: String,
    val module: PlatformModuleId,
    val version: String,
    val dependencies: List<PlatformModuleId>,
    val schemas: List<AddonCapabilitySchema>,
    val bindings: List<AddonGuestApiBinding>,
) {
    companion object {
        fun parse(lines: List<String>): AddonContract {
            var addon: String? = null
            var module: PlatformModuleId? = null
            var version: String? = null
            var dependencies: List<PlatformModuleId>? = null
            val schemas = mutableListOf<MutableSchema>()
            val bindings = mutableListOf<AddonGuestApiBinding>()
            lines.forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val fields = line.split(Regex("\\s+"))
                when (fields.first()) {
                    "addon" -> {
                        require(fields.size == 2 && addon == null) { "invalid addon directive at line ${index + 1}" }
                        addon = fields[1]
                    }

                    "module" -> {
                        require(fields.size == 2 && module == null) { "invalid module directive at line ${index + 1}" }
                        module = fields[1].moduleId()
                    }

                    "version" -> {
                        require(fields.size == 2 && version == null) { "invalid version directive at line ${index + 1}" }
                        version = fields[1]
                    }

                    "dependencies" -> {
                        require(dependencies == null) { "duplicate dependencies directive at line ${index + 1}" }
                        dependencies = fields.drop(1).map(String::moduleId)
                    }

                    "capability" -> {
                        require(fields.size == 5) { "invalid capability directive at line ${index + 1}" }
                        schemas += MutableSchema(AddonCapabilityIdentity(fields[1], fields[2], fields[3].toInt(), fields[4].toInt()))
                    }

                    "operation" -> {
                        require(fields.size >= 3 && schemas.isNotEmpty()) { "invalid operation directive at line ${index + 1}" }
                        schemas.last().operations +=
                            AddonCapabilityOperation(
                                fields.drop(3).map(AddonCapabilityValueType::valueOf),
                                AddonCapabilityValueType.valueOf(fields[2]),
                                when (fields[1]) {
                                    "sync" -> false
                                    "async" -> true
                                    else -> error("invalid operation mode at line ${index + 1}")
                                },
                            )
                    }

                    "binding" -> {
                        require(fields.size == 7) { "invalid binding directive at line ${index + 1}" }
                        val capability = fields[5].split(':')
                        require(capability.size == 3) { "invalid binding capability at line ${index + 1}" }
                        val schema =
                            schemas.singleOrNull {
                                it.identity.namespace == capability[0] &&
                                    it.identity.name == capability[1] &&
                                    it.identity.abiMajor == capability[2].toInt()
                            } ?: error("binding references unknown capability at line ${index + 1}")
                        bindings +=
                            AddonGuestApiBinding(
                                fields[1],
                                fields[2].takeUnless { it == "-" },
                                fields[3],
                                fields[4],
                                schema.identity,
                                fields[6].toInt(),
                            )
                    }

                    else -> {
                        error("unsupported addon contract directive at line ${index + 1}: ${fields.first()}")
                    }
                }
            }
            return AddonContract(
                requireNotNull(addon) { "addon contract identity is missing" },
                requireNotNull(module) { "addon contract module is missing" },
                requireNotNull(version) { "addon contract version is missing" },
                requireNotNull(dependencies) { "addon contract dependencies are missing" },
                schemas.map { AddonCapabilitySchema(it.identity, it.operations) },
                bindings,
            )
        }
    }
}

private data class MutableSchema(
    val identity: AddonCapabilityIdentity,
    val operations: MutableList<AddonCapabilityOperation> = mutableListOf(),
)

private fun Map<String, String>.path(name: String): Path =
    Path.of(requireNotNull(get(name)) { "missing $name" }).toAbsolutePath().normalize()

private fun String.moduleId(): PlatformModuleId =
    PlatformModuleId(substringBefore(':', missingDelimiterValue = ""), substringAfter(':', missingDelimiterValue = ""))

private fun writeAtomically(
    output: Path,
    bytes: ByteArray,
) {
    output.parent.createDirectories()
    val temporary = Files.createTempFile(output.parent, output.fileName.toString(), ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
