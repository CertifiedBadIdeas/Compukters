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
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines

fun main(args: Array<String>) {
    val arguments = args.toList().windowed(2, 2, partialWindows = false).associate { (name, value) -> name to value }
    val input = arguments.path("--input")
    val descriptor = arguments.path("--descriptor")
    val platformOutput = arguments.path("--platform-output")
    val addonOutput = arguments.path("--addon-output")
    val contract = AddonContract.parse(descriptor.readLines())
    val platform = PlatformBundleCodec.decode(Files.readAllBytes(input))
    val module =
        platform.modules.singleOrNull { it.id == contract.module }
            ?: error("platform contains no unique addon module ${contract.module}")
    require(platform.modules.none { contract.module in it.dependencies }) {
        "packaged platform module depends on extracted addon module ${contract.module}"
    }
    val base =
        PlatformBundleCodec.assemble(
            platform.identity.languageVersion,
            platform.identity.platformAbi,
            platform.builtins,
            platform.modules.filterNot { it.id == contract.module },
        )
    val addon =
        AddonGuestApiBundleCodec.assemble(
            contract.addon,
            platform.identity.platformAbi,
            module,
            contract.schemas,
            contract.bindings,
            includeSources = true,
        )
    writeAtomically(platformOutput, PlatformBundleCodec.encode(base))
    writeAtomically(addonOutput, AddonGuestApiBundleCodec.encode(addon))
}

data class AddonContract(
    val addon: String,
    val module: PlatformModuleId,
    val schemas: List<AddonCapabilitySchema>,
    val bindings: List<AddonGuestApiBinding>,
) {
    companion object {
        fun parse(lines: List<String>): AddonContract {
            var addon: String? = null
            var module: PlatformModuleId? = null
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
