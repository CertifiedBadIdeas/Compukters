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
import ru.lazyhat.compukters.addon.api.CanonicalAddonRecordSchemas

internal val guestMathematicalTypes = CanonicalAddonRecordSchemas.schemas.keys + setOf("kotlin.math.Rotation3f", "kotlin.math.Rotation3d")

/** The SDK owns these adapters. Addon declarations cannot supply serialization or reconstruction code. */
internal class GuestBoundaryTypes(
    private val contract: DeclarativeGuestContract,
) {
    private val records = contract.boundaryRecords + contract.types.filter { it.record }.associateBy { "${it.packageName}.${it.name}" }
    private val resources = contract.types.filter { it.resource }.mapTo(mutableSetOf()) { "${it.packageName}.${it.name}" }

    fun fields(type: String): List<GuestContractParameter>? {
        if (type in resources) return listOf(GuestContractParameter("handle", "Int", emptyList()))
        records[type]?.let { return it.fields }
        if (contract.nativeSources.isNotEmpty()) return null
        CanonicalAddonRecordSchemas.schemas[type]?.let { schema ->
            return schema.fields.map { field ->
                val scalar =
                    when (field.type) {
                        AddonCapabilityValueType.I32 -> "Int"
                        AddonCapabilityValueType.F32 -> "Float"
                        AddonCapabilityValueType.F64 -> "Double"
                        else -> error("Unsupported mathematical component")
                    }
                GuestContractParameter(field.name, scalar, emptyList())
            }
        }
        if (type == "kotlin.math.Rotation3f" || type == "kotlin.math.Rotation3d") {
            return listOf(GuestContractParameter("quaternion", "kotlin.math.Quaternion${type.last()}", emptyList()))
        }
        return null
    }

    fun wireType(
        type: String,
        active: Set<String> = emptySet(),
    ): String {
        require(type !in active) { "Cyclic contract record: $type" }
        require(active.size <= 8) { "Contract record nesting exceeds limit" }
        if (type in resources) return "Int"
        if (contract.nativeSources.isEmpty() && (type == "kotlin.math.Rotation3f" || type == "kotlin.math.Rotation3d")) {
            return "kotlin.math.Quaternion${type.last()}"
        }
        val record = records[type] ?: return type
        val fieldTypes = record.fields.map { wireType(it.type, active + type) }
        return if (record.fields.indices.any { fieldTypes[it] != record.fields[it].type }) "${type}Reply" else type
    }

    fun decode(
        type: String,
        expression: String,
    ): String =
        when {
            wireType(type) == type -> expression
            type in resources -> "${if (contract.nativeSources.isEmpty()) type else nativeImplementation(type)}($expression)"
            type == "kotlin.math.Rotation3f" || type == "kotlin.math.Rotation3d" -> "$type.fromQuaternion($expression)"
            else -> "$type(${requireNotNull(fields(type)).joinToString { decode(it.type, "$expression.${it.name}") }})"
        }

    fun arguments(
        operation: GuestContractOperation,
        handleExpression: String = "handle",
    ): List<GuestWireArgument> {
        val result = mutableListOf<GuestWireArgument>()

        fun flatten(
            name: String,
            expression: String,
            type: String,
            constraints: List<GuestArgumentConstraint>,
            active: Set<String>,
        ) {
            require(type !in active && active.size <= 8) { "Cyclic or excessively nested contract argument: $type" }
            if (contract.nativeSources.isNotEmpty() && type in resources) {
                result +=
                    GuestWireArgument(
                        "${name}_handle",
                        "Int",
                        "($expression as ${nativeImplementation(type)}).__compuktersHandle",
                        emptyList(),
                    )
                return
            }
            val members = fields(type)
            if (members == null) {
                result += GuestWireArgument(name, type, expression, constraints)
            } else {
                members.forEach { flatten("${name}_${it.name}", "$expression.${it.name}", it.type, emptyList(), active + type) }
            }
        }
        if (operation.device) result += GuestWireArgument("handle", "Int", handleExpression, emptyList())
        operation.parameters.forEach { flatten(it.name, it.name, it.type, it.constraints, emptySet()) }
        require(result.size <= 32) { "${operation.name}: flattened operation exceeds 32 wire arguments" }
        require(result.distinctBy { it.name }.size == result.size) { "${operation.name}: flattened parameter name collision" }
        return result
    }
}

internal data class GuestWireArgument(
    val name: String,
    val type: String,
    val expression: String,
    val constraints: List<GuestArgumentConstraint>,
)
