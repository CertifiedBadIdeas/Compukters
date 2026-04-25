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

private data class GeneratedLocalizationEntry(
    val fullKey: String,
    val value: String,
    val objectPath: List<String>,
    val constantName: String,
    val propertyName: String,
)

class LocalizationApiGenerator(
    private val packageName: String,
) {
    fun generate(entries: Map<String, String>): Map<String, String> {
        require(entries.isNotEmpty()) { "Localization entries must not be empty" }

        val generatedEntries =
            entries.toSortedMap().map { (key, value) ->
                GeneratedLocalizationEntry(
                    fullKey = key,
                    value = value,
                    objectPath = objectPathFor(key),
                    constantName = constantNameFor(key),
                    propertyName = propertyNameFor(key),
                )
            }

        validateNameCollisions(generatedEntries)

        return mapOf(
            "CompukterKeys.kt" to renderKeys(generatedEntries),
            "CompukterTranslatable.kt" to renderTranslatable(generatedEntries),
            "CompukterComponents.kt" to renderComponents(generatedEntries),
        )
    }

    private fun renderKeys(entries: List<GeneratedLocalizationEntry>): String =
        buildString {
            appendLine("package $packageName")
            appendLine()
            append(
                renderObject(
                    rootObjectName = "CompukterKeys",
                    objectPath = emptyList(),
                    entries = entries,
                    indent = "",
                    renderEntry = { entry, entryIndent ->
                        listOf("${entryIndent}const val ${entry.constantName} = \"${entry.fullKey}\"")
                    },
                ),
            )
        }

    private fun renderTranslatable(entries: List<GeneratedLocalizationEntry>): String =
        buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import ru.lazyhat.compukterkraft.core.ui.foundation.Value")
            appendLine("import ru.lazyhat.compukterkraft.common.ui.dsl.translatable")
            appendLine()
            append(
                renderObject(
                    rootObjectName = "CompukterTranslatable",
                    objectPath = emptyList(),
                    entries = entries,
                    indent = "",
                    renderEntry = { entry, entryIndent ->
                        if (canGenerateValueHelper(entry.value)) {
                            listOf(
                                "${entryIndent}val ${entry.propertyName}: Value<String>",
                                "$entryIndent    get() = translatable(CompukterKeys.${
                                    entry.objectPath.joinToString(
                                        ".",
                                    )
                                }.${entry.constantName})",
                            )
                        } else {
                            emptyList()
                        }
                    },
                ),
            )
        }

    private fun renderComponents(entries: List<GeneratedLocalizationEntry>): String =
        buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import net.minecraft.network.chat.Component")
            appendLine()
            append(
                renderObject(
                    rootObjectName = "CompukterComponents",
                    objectPath = emptyList(),
                    entries = entries,
                    indent = "",
                    renderEntry = { entry, entryIndent ->
                        if (canGenerateValueHelper(entry.value)) {
                            listOf(
                                "${entryIndent}val ${entry.propertyName}: Component",
                                "$entryIndent    get() = Component.translatable(CompukterKeys.${
                                    entry.objectPath.joinToString(
                                        ".",
                                    )
                                }.${entry.constantName})",
                            )
                        } else {
                            listOf(
                                "${entryIndent}fun ${entry.propertyName}(vararg args: Any): Component = Component.translatable(CompukterKeys.${
                                    entry.objectPath.joinToString(
                                        ".",
                                    )
                                }.${entry.constantName}, *args)",
                            )
                        }
                    },
                ),
            )
        }

    private fun renderObject(
        rootObjectName: String,
        objectPath: List<String>,
        entries: List<GeneratedLocalizationEntry>,
        indent: String,
        renderEntry: (GeneratedLocalizationEntry, String) -> List<String>,
    ): String =
        buildString {
            val objectName = if (objectPath.isEmpty()) rootObjectName else objectPath.last()
            appendLine("${indent}object $objectName {")

            val directEntries = entries.filter { it.objectPath == objectPath }
            val renderedDirectEntries = directEntries.flatMap { entry -> renderEntry(entry, "$indent    ") }
            val childObjects =
                entries
                    .filter { it.objectPath.size > objectPath.size }
                    .filter { candidate ->
                        objectPath.indices.all { index -> candidate.objectPath[index] == objectPath[index] }
                    }.map { candidate -> candidate.objectPath[objectPath.size] }
                    .distinct()

            renderedDirectEntries.forEach { line -> appendLine(line) }

            childObjects.forEachIndexed { index, childName ->
                if (renderedDirectEntries.isNotEmpty() || index > 0) {
                    appendLine()
                }
                append(
                    renderObject(
                        rootObjectName = rootObjectName,
                        objectPath = objectPath + childName,
                        entries = entries,
                        indent = "$indent    ",
                        renderEntry = renderEntry,
                    ),
                )
            }

            appendLine("$indent}")
        }

    private fun objectPathFor(key: String): List<String> =
        key
            .split('.')
            .dropLast(1)
            .let { segments ->
                if (segments.firstOrNull() == "compukterkraft") {
                    segments.drop(1)
                } else {
                    segments
                }
            }.map(::pascalCase)

    private fun constantNameFor(key: String): String = key.substringAfterLast('.').uppercase().replace('-', '_')

    private fun propertyNameFor(key: String): String =
        key
            .substringAfterLast('.')
            .split('-', '_')
            .filter(String::isNotBlank)
            .joinToString(separator = "") { segment ->
                segment.replaceFirstChar { char -> char.uppercase() }
            }.replaceFirstChar { char -> char.lowercase() }

    private fun pascalCase(segment: String): String =
        segment
            .split('-', '_')
            .filter(String::isNotBlank)
            .joinToString(separator = "") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }

    private fun canGenerateValueHelper(localizedText: String): Boolean = !PLACEHOLDER_PATTERN.containsMatchIn(localizedText)

    private fun validateNameCollisions(entries: List<GeneratedLocalizationEntry>) {
        entries
            .groupBy { it.objectPath to it.constantName }
            .filterValues { it.size > 1 }
            .takeIf { it.isNotEmpty() }
            ?.let { collisions ->
                val details = collisions.values.flatten().joinToString { it.fullKey }
                throw IllegalArgumentException("Localization API name collision: $details")
            }
    }

    private companion object {
        val PLACEHOLDER_PATTERN = Regex("%(?:\\d+\\$)?[a-zA-Z]")
    }
}
