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

package ru.lazyhat.compukters.ide.project

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

class ManifestException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProjectManifestCodec {
    fun decode(
        source: String,
        limits: ProjectLimits = ProjectLimits(),
    ): ProjectManifest {
        val sourceBytes =
            try {
                TomlSupport.strictUtf8(source)
            } catch (exception: Exception) {
                throw ManifestException("manifest must be strict UTF-8", exception)
            }
        if (sourceBytes.size > limits.manifestBytes) throw ManifestException("manifest byte count exceeds limit")

        val parsed = Toml.parse(source)
        if (parsed.hasErrors()) {
            val error = parsed.errors().first()
            throw ManifestException("manifest TOML error at ${error.position()}: ${error.message}", error)
        }
        rejectUnknownKeys(parsed, ROOT_KEYS, "manifest")
        val format = parsed["format"] as? Long ?: throw ManifestException("manifest format must be an integer")
        if (format !in 1L..ProjectManifest.FORMAT.toLong()) throw ManifestException("unsupported manifest format: $format")
        val name = parsed["name"] as? String ?: throw ManifestException("manifest name must be a string")
        val moduleTable =
            when (val modules = parsed["modules"]) {
                null -> null
                is TomlTable -> modules
                else -> throw ManifestException("manifest modules must be a table")
            }
        val requirements = linkedSetOf<ModuleId>()
        moduleTable?.entrySet()?.forEach { (provider, value) ->
            val modules =
                when (format) {
                    1L -> {
                        val providerTable =
                            value as? TomlTable ?: throw ManifestException("module provider $provider must be a table")
                        providerTable.entrySet().map { (module, rawMajor) ->
                            val majorValue =
                                rawMajor as? Long
                                    ?: throw ManifestException("module $provider:$module major must be an integer")
                            if (majorValue !in 1L..ApiMajor.MAXIMUM.toLong()) {
                                throw ManifestException("module $provider:$module major is outside the supported range")
                            }
                            module
                        }
                    }

                    2L -> {
                        val providerArray =
                            value as? TomlArray ?: throw ManifestException("module provider $provider must be an array")
                        List(providerArray.size()) { index ->
                            providerArray[index] as? String
                                ?: throw ManifestException("module provider $provider entry $index must be a string")
                        }
                    }

                    else -> {
                        error("unreachable manifest format")
                    }
                }
            modules.forEach { module ->
                val id = validated("invalid module ID $provider:$module") { ModuleId(provider, module) }
                if (!requirements.add(id)) throw ManifestException("duplicate module requirement: ${id.value}")
                if (requirements.size > limits.modules) throw ManifestException("project module count exceeds limit")
            }
        }
        return validated("invalid project manifest") { ProjectManifest.of(name, requirements, limits) }
    }

    fun encode(manifest: ProjectManifest): String {
        val providers =
            manifest.modules
                .groupBy(ModuleId::provider)
                .toSortedMap(TomlSupport.utf8Comparator)
        return buildString {
            append("format = ").append(ProjectManifest.FORMAT).append('\n')
            append("name = ").append(TomlSupport.quoted(manifest.name)).append("\n\n")
            append("[modules]\n")
            providers.forEach { (provider, entries) ->
                append(provider).append(" = [ ")
                entries
                    .sortedWith { left, right -> TomlSupport.utf8Comparator.compare(left.module, right.module) }
                    .forEachIndexed { index, id ->
                        if (index > 0) append(", ")
                        append(TomlSupport.quoted(id.module))
                    }
                append(" ]\n")
            }
        }
    }

    private fun rejectUnknownKeys(
        table: TomlTable,
        allowed: Set<String>,
        description: String,
    ) {
        val unknown = table.keySet() - allowed
        if (unknown.isNotEmpty()) throw ManifestException("unknown $description key: ${unknown.sorted().first()}")
    }

    private inline fun <T> validated(
        message: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (exception: IllegalArgumentException) {
            throw ManifestException("$message: ${exception.message}", exception)
        }

    private val ROOT_KEYS = setOf("format", "name", "modules")
}
