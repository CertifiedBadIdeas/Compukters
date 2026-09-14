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
import ru.lazyhat.compukters.addon.api.AddonCapabilitySignature
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

data class AddonCapabilityAuthoring(
    val identity: AddonCapabilityIdentity,
    val bindingOwner: String,
)

data class AddonAuthoringContract(
    val addon: String,
    val module: PlatformModuleId,
    val version: String,
    val dependencies: List<PlatformModuleId>,
    val capabilities: List<AddonCapabilityAuthoring>,
) {
    companion object {
        fun parse(lines: List<String>): AddonAuthoringContract {
            var addon: String? = null
            var module: PlatformModuleId? = null
            var version: String? = null
            var dependencies: List<PlatformModuleId>? = null
            val capabilities = mutableListOf<AddonCapabilityAuthoring>()
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
                        module = fields[1].authoringModuleId()
                    }

                    "version" -> {
                        require(fields.size == 2 && version == null) { "invalid version directive at line ${index + 1}" }
                        version = fields[1]
                    }

                    "dependencies" -> {
                        require(dependencies == null) { "duplicate dependencies directive at line ${index + 1}" }
                        dependencies = fields.drop(1).map(String::authoringModuleId)
                    }

                    "capability" -> {
                        require(fields.size == 6) { "invalid capability directive at line ${index + 1}" }
                        capabilities +=
                            AddonCapabilityAuthoring(
                                AddonCapabilityIdentity(fields[1], fields[2], fields[3].toInt(), fields[4].toInt()),
                                fields[5],
                            )
                    }

                    else -> {
                        error("unsupported addon authoring directive at line ${index + 1}: ${fields.first()}")
                    }
                }
            }
            val identity = requireNotNull(addon) { "addon contract identity is missing" }
            val parsedModule = requireNotNull(module) { "addon contract module is missing" }
            require(parsedModule.namespace == identity) { "addon module namespace must equal addon identity" }
            require(capabilities.isNotEmpty()) { "addon contract contains no capabilities" }
            require(capabilities.distinctBy { it.identity.capabilityKey() }.size == capabilities.size) {
                "addon contract contains duplicate capability identities"
            }
            capabilities.forEach { capability ->
                require(capability.identity.namespace == identity) { "addon capability namespace must equal addon identity" }
                require(capability.bindingOwner.startsWith("$identity.")) {
                    "addon binding owner escapes namespace: ${capability.bindingOwner}"
                }
            }
            return AddonAuthoringContract(
                identity,
                parsedModule,
                requireNotNull(version) { "addon contract version is missing" },
                requireNotNull(dependencies) { "addon contract dependencies are missing" },
                capabilities,
            )
        }
    }
}

enum class AddonAbiEntryState(
    val serialized: String,
) {
    ACTIVE("active"),
    TOMBSTONE("tombstone"),
}

data class AddonAbiEntry(
    val namespace: String,
    val name: String,
    val abiMajor: Int,
    val operation: Int,
    val state: AddonAbiEntryState,
    val symbol: String,
    val signature: String,
) {
    val callableKey: Pair<String, String> = symbol to signature
    val capabilityKey: Triple<String, String, Int> = Triple(namespace, name, abiMajor)
}

data class AddonAbiLock(
    val entries: List<AddonAbiEntry>,
) {
    fun render(): String =
        buildString {
            appendLine("lock 1")
            entries
                .sortedWith(compareBy(AddonAbiEntry::namespace, AddonAbiEntry::name, AddonAbiEntry::abiMajor, AddonAbiEntry::operation))
                .forEach { entry ->
                    appendLine(
                        "operation ${entry.namespace} ${entry.name} ${entry.abiMajor} ${entry.operation} " +
                            "${entry.state.serialized} ${entry.symbol} ${entry.signature}",
                    )
                }
        }

    companion object {
        fun empty(): AddonAbiLock = AddonAbiLock(emptyList())

        fun parse(lines: List<String>): AddonAbiLock {
            var header = false
            val entries = mutableListOf<AddonAbiEntry>()
            lines.forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val fields = line.split(Regex("\\s+"))
                when (fields.first()) {
                    "lock" -> {
                        require(fields == listOf("lock", "1") && !header) { "invalid addon ABI lock header at line ${index + 1}" }
                        header = true
                    }

                    "operation" -> {
                        require(header && fields.size == 8) { "invalid addon ABI operation at line ${index + 1}" }
                        entries +=
                            AddonAbiEntry(
                                fields[1],
                                fields[2],
                                fields[3].toInt(),
                                fields[4].toInt(),
                                AddonAbiEntryState.entries.singleOrNull { it.serialized == fields[5] }
                                    ?: error("invalid addon ABI operation state at line ${index + 1}"),
                                fields[6],
                                fields[7],
                            )
                    }

                    else -> {
                        error("unsupported addon ABI lock directive at line ${index + 1}: ${fields.first()}")
                    }
                }
            }
            require(header) { "addon ABI lock header is missing" }
            require(entries.distinctBy { it.capabilityKey to it.operation }.size == entries.size) {
                "addon ABI lock contains duplicate operation selectors"
            }
            require(entries.distinctBy { it.capabilityKey to it.callableKey }.size == entries.size) {
                "addon ABI lock contains duplicate callable identities"
            }
            entries.groupBy(AddonAbiEntry::capabilityKey).forEach { (capability, operations) ->
                val selectors = operations.map(AddonAbiEntry::operation).sorted()
                require(selectors == selectors.indices.toList()) { "addon ABI lock operations are not contiguous for $capability" }
            }
            entries.forEach { entry ->
                require(entry.abiMajor > 0) { "addon ABI major must be positive" }
                require(entry.operation >= 0) { "addon ABI operation must not be negative" }
                AddonCapabilitySignature.parse(entry.signature)
            }
            return AddonAbiLock(entries)
        }
    }
}

data class ResolvedAddonContract(
    val contract: AddonContract,
    val expectedLock: AddonAbiLock,
)

fun resolveAddonContract(
    sourceRoot: Path,
    authoring: AddonAuthoringContract,
    currentLock: AddonAbiLock,
): ResolvedAddonContract {
    val sources = discoverAuthoringSources(sourceRoot)
    require(sources.isNotEmpty()) { "addon module ${authoring.module} has no Kotlin sources" }
    sources.forEach { source ->
        require(source.path.startsWith("${authoring.addon}/")) { "addon source path must be owned by ${authoring.addon}: ${source.path}" }
    }
    val external =
        PlatformMetadataCompiler()
            .compile(authoring.module, sources)
            .declarations
            .filter(PlatformDeclaration::trustedExternal)
    val byCapability =
        authoring.capabilities.associateWith { capability ->
            external.filter { declaration ->
                declaration.symbol.startsWith("${capability.bindingOwner}.") &&
                    '.' !in declaration.symbol.removePrefix("${capability.bindingOwner}.")
            }
        }
    val assigned = byCapability.values.flatten()
    require(assigned.size == external.size && assigned.toSet().size == external.size) {
        val unassigned = external - assigned.toSet()
        "every external declaration must belong to exactly one configured binding owner; unassigned=$unassigned"
    }
    byCapability.forEach { (capability, declarations) ->
        require(declarations.isNotEmpty()) { "addon capability ${capability.identity.capabilityKey()} has no external declarations" }
        declarations.forEach { declaration -> AddonCapabilitySignature.parse(declaration.signature) }
    }

    val actual =
        byCapability
            .flatMap { (capability, declarations) ->
                declarations.map { declaration -> capability.identity.capabilityKey() to (declaration.symbol to declaration.signature) }
            }.toSet()
    val retained =
        currentLock.entries
            .map { entry ->
                entry.copy(
                    state =
                        if (entry.capabilityKey to entry.callableKey in
                            actual
                        ) {
                            AddonAbiEntryState.ACTIVE
                        } else {
                            AddonAbiEntryState.TOMBSTONE
                        },
                )
            }.toMutableList()
    authoring.capabilities.forEach { capability ->
        val capabilityKey = capability.identity.capabilityKey()
        var next =
            retained
                .filter { it.capabilityKey == capabilityKey }
                .maxOfOrNull(AddonAbiEntry::operation)
                ?.plus(1) ?: 0
        byCapability
            .getValue(capability)
            .sortedWith(compareBy(PlatformDeclaration::symbol, PlatformDeclaration::signature))
            .forEach { declaration ->
                val callableKey = declaration.symbol to declaration.signature
                if (retained.none { it.capabilityKey == capabilityKey && it.callableKey == callableKey }) {
                    retained +=
                        AddonAbiEntry(
                            capability.identity.namespace,
                            capability.identity.name,
                            capability.identity.abiMajor,
                            next++,
                            AddonAbiEntryState.ACTIVE,
                            declaration.symbol,
                            declaration.signature,
                        )
                }
            }
    }
    val expectedLock = AddonAbiLock(retained)
    val schemas =
        authoring.capabilities.map { capability ->
            val operations =
                expectedLock.entries
                    .filter { it.capabilityKey == capability.identity.capabilityKey() }
                    .sortedBy(AddonAbiEntry::operation)
                    .map { entry ->
                        val signature = AddonCapabilitySignature.parse(entry.signature)
                        AddonCapabilityOperation(signature.arguments, signature.result, asynchronous = true)
                    }
            AddonCapabilitySchema(capability.identity, operations)
        }
    val identityByKey = authoring.capabilities.associate { it.identity.capabilityKey() to it.identity }
    val bindings =
        expectedLock.entries
            .filter { entry -> entry.state == AddonAbiEntryState.ACTIVE && entry.capabilityKey in identityByKey }
            .map { entry ->
                val ownerName = entry.symbol.substringBeforeLast('.')
                AddonGuestApiBinding(
                    ownerName.substringBeforeLast('.'),
                    ownerName.substringAfterLast('.'),
                    entry.symbol.substringAfterLast('.'),
                    entry.signature,
                    identityByKey.getValue(entry.capabilityKey),
                    entry.operation,
                )
            }
    return ResolvedAddonContract(
        AddonContract(authoring.addon, authoring.module, authoring.version, authoring.dependencies, schemas, bindings),
        expectedLock,
    )
}

private fun discoverAuthoringSources(root: Path): List<PlatformSource> =
    Files.walk(root).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .map { path ->
                val relative = root.relativize(path).invariantSeparatorsPathString
                PlatformSource(relative, ImmutableBytes.of(Files.readAllBytes(path)))
            }.sorted(Comparator.comparing(PlatformSource::path))
            .toList()
    }

private fun AddonCapabilityIdentity.capabilityKey(): Triple<String, String, Int> = Triple(namespace, name, abiMajor)

private fun String.authoringModuleId(): PlatformModuleId =
    PlatformModuleId(substringBefore(':', missingDelimiterValue = ""), substringAfter(':', missingDelimiterValue = ""))
