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

import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonRecordField
import ru.lazyhat.compukters.addon.api.AddonRecordSchema
import ru.lazyhat.compukters.platform.k2.build.PlatformRecordCandidate

internal fun resolveAddonRecordSchemas(
    addon: String,
    resultTypes: Set<String>,
    candidates: List<PlatformRecordCandidate>,
): Map<String, AddonRecordSchema> {
    val byName = candidates.associateBy { it.symbol }
    val completed = linkedMapOf<String, AddonRecordSchema>()
    val active = mutableSetOf<String>()

    fun resolve(name: String): AddonRecordSchema {
        completed[name]?.let { return it }
        require(name.startsWith("$addon.")) { "record response escapes addon namespace: $name" }
        require(active.add(name)) { "cyclic record response: $name" }
        require(active.size <= AddonRecordSchema.MAXIMUM_DEPTH) { "record nesting exceeds limit" }
        val candidate = requireNotNull(byName[name]) { "unknown record response type: $name" }
        require(candidate.rejection == null) { "record response $name ${candidate.rejection}" }
        val fields =
            candidate.fields.map { field ->
                val scalar =
                    when (field.type) {
                        "kotlin.Int" -> AddonCapabilityValueType.I32
                        "kotlin.Long" -> AddonCapabilityValueType.I64
                        "kotlin.Float" -> AddonCapabilityValueType.F32
                        "kotlin.Double" -> AddonCapabilityValueType.F64
                        "kotlin.Boolean" -> AddonCapabilityValueType.BOOL
                        "kotlin.Char" -> AddonCapabilityValueType.CHAR
                        "kotlin.String" -> AddonCapabilityValueType.STRING
                        else -> null
                    }
                if (scalar != null) {
                    AddonRecordField(field.name, scalar)
                } else {
                    AddonRecordField(field.name, AddonCapabilityValueType.RECORD, resolve(field.type))
                }
            }
        active.remove(name)
        return AddonRecordSchema(name, fields).also { completed[name] = it }
    }
    resultTypes.sorted().forEach { raw ->
        val matches = byName.keys.filter { it == raw || it.substringAfterLast('.') == raw }
        require(matches.size == 1) { "record response type is unknown or ambiguous: $raw" }
        val schema = resolve(matches.single())
        completed[raw] = schema
    }
    return completed
}
