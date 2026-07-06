/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.runtime.kraftos

import java.io.StringReader
import java.util.Properties

data class KraftOsArtifactRef(
    val resource: String,
    val format: String,
)

data class KraftOsArtifactManifest(
    val schema: Int,
    val target: String,
    val profile: String,
    val biosFlash: KraftOsArtifactRef,
    val systemStorage0: KraftOsArtifactRef,
) {
    companion object {
        const val DEFAULT_RESOURCE: String = "firmware/kraftos-artifacts.properties"
        const val SUPPORTED_SCHEMA: Int = 1
        const val SUPPORTED_TARGET: String = "k16"
        const val PRODUCTION_PROFILE: String = "production"
        const val DEVELOPMENT_PROFILE: String = "development"
        const val BIOS_FLASH_FORMAT: String = "kflash"
        const val SYSTEM_STORAGE0_FORMAT: String = "kfs-kv"

        fun load(
            resourcePath: String = DEFAULT_RESOURCE,
            classLoader: ClassLoader = KraftOsArtifactManifest::class.java.classLoader,
        ): KraftOsArtifactManifest {
            val text =
                classLoader
                    .getResourceAsStream(resourcePath)
                    ?.use { it.readBytes().decodeToString() }
                    ?: error("KraftOS artifact manifest resource not found: $resourcePath")
            return parse(text = text, source = resourcePath)
        }

        fun parse(
            text: String,
            source: String,
        ): KraftOsArtifactManifest {
            val properties =
                Properties().apply {
                    load(StringReader(text))
                }
            val schema = required(properties, "schema", source).toIntOrNull()
                ?: error("KraftOS artifact manifest schema must be an integer: $source")
            check(schema == SUPPORTED_SCHEMA) {
                "unsupported KraftOS artifact manifest schema: $schema in $source"
            }
            val target = required(properties, "target", source)
            check(target == SUPPORTED_TARGET) {
                "unsupported KraftOS artifact manifest target: $target in $source"
            }
            val profile = required(properties, "profile", source)
            check(profile == PRODUCTION_PROFILE || profile == DEVELOPMENT_PROFILE) {
                "unsupported KraftOS artifact manifest profile: $profile in $source"
            }
            return KraftOsArtifactManifest(
                schema = schema,
                target = target,
                profile = profile,
                biosFlash =
                    artifactRef(
                        properties = properties,
                        name = "biosFlash",
                        expectedFormat = BIOS_FLASH_FORMAT,
                        source = source,
                    ),
                systemStorage0 =
                    artifactRef(
                        properties = properties,
                        name = "systemStorage0",
                        expectedFormat = SYSTEM_STORAGE0_FORMAT,
                        source = source,
                    ),
            )
        }

        private fun artifactRef(
            properties: Properties,
            name: String,
            expectedFormat: String,
            source: String,
        ): KraftOsArtifactRef {
            val resourceKey = "artifact.$name.resource"
            val formatKey = "artifact.$name.format"
            val resource = required(properties, resourceKey, source)
            val format = required(properties, formatKey, source)
            check(format == expectedFormat) {
                "unsupported $formatKey: $format in $source"
            }
            return KraftOsArtifactRef(resource = resource, format = format)
        }

        private fun required(
            properties: Properties,
            key: String,
            source: String,
        ): String {
            val value = properties.getProperty(key)?.trim().orEmpty()
            check(value.isNotEmpty()) {
                "KraftOS artifact manifest missing $key: $source"
            }
            return value
        }
    }
}
