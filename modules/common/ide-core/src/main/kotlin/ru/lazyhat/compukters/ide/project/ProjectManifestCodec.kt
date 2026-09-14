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
        if (format != ProjectManifest.FORMAT.toLong()) throw ManifestException("unsupported manifest format: $format")
        val name = parsed["name"] as? String ?: throw ManifestException("manifest name must be a string")
        val addonArray =
            when (val addons = parsed["addons"]) {
                null -> null
                is TomlArray -> addons
                else -> throw ManifestException("manifest addons must be an array")
            }
        val addons = linkedSetOf<AddonId>()
        addonArray?.let { array ->
            repeat(array.size()) { index ->
                val value = array[index] as? String ?: throw ManifestException("manifest addon $index must be a string")
                val id = validated("invalid addon ID $value") { AddonId(value) }
                if (!addons.add(id)) throw ManifestException("duplicate addon requirement: ${id.value}")
                if (addons.size > limits.addons) throw ManifestException("project addon count exceeds limit")
            }
        }
        return validated("invalid project manifest") { ProjectManifest.of(name, addons, limits) }
    }

    fun encode(manifest: ProjectManifest): String =
        buildString {
            append("format = ").append(ProjectManifest.FORMAT).append('\n')
            append("name = ").append(TomlSupport.quoted(manifest.name)).append("\n\n")
            append("addons = [")
            manifest.addons.forEachIndexed { index, addon ->
                if (index > 0) append(", ")
                append(TomlSupport.quoted(addon.value))
            }
            append("]\n")
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

    private val ROOT_KEYS = setOf("format", "name", "addons")
}
