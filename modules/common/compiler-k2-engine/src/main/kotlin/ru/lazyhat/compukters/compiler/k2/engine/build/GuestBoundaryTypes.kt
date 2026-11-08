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

/** The SDK owns these adapters. Addon declarations cannot supply serialization or reconstruction code. */
internal class GuestBoundaryTypes(
    private val contract: DeclarativeGuestContract,
) {
    private val records = contract.boundaryRecords + contract.types.filter { it.record }.associateBy { "${it.packageName}.${it.name}" }
    private val resources = contract.types.filter { it.resource }.mapTo(mutableSetOf()) { "${it.packageName}.${it.name}" }

    fun construction(type: String): GuestRecordConstruction? = records[type]?.construction

    fun fields(type: String): List<GuestContractParameter>? {
        if (type in resources) return listOf(GuestContractParameter("handle", "Int", emptyList()))
        records[type]?.let { return it.fields }
        return null
    }

    fun wireType(
        type: String,
        active: Set<String> = emptySet(),
    ): String {
        require(type !in active) { "Cyclic contract record: $type" }
        require(active.size <= 8) { "Contract record nesting exceeds limit" }
        if (type in resources) return "Int"
        val record = records[type] ?: return type
        if (record.construction?.factory?.isNotEmpty() == true && record.fields.size == 1) {
            return wireType(record.fields.single().type, active + type)
        }
        val fieldTypes = record.fields.map { wireType(it.type, active + type) }
        if (record.construction == null && record.fields.indices.none { fieldTypes[it] != record.fields[it].type }) return type
        val addon =
            contract.nativeSources.keys
                .first()
                .substringBefore('/')
        return if (type.startsWith("$addon.")) "${type}Reply" else "$addon.__compukters_wire.${record.name}Reply"
    }

    fun decode(
        type: String,
        expression: String,
    ): String =
        when {
            wireType(type) == type -> {
                expression
            }

            type in resources -> {
                "${nativeImplementation(type)}($expression)"
            }

            construction(type) != null -> {
                val record = records.getValue(type)
                val arguments =
                    if (record.construction?.factory?.isNotEmpty() == true && record.fields.size == 1) {
                        decode(record.fields.single().type, expression)
                    } else {
                        record.fields.joinToString { decode(it.type, "$expression.${it.name}") }
                    }
                val factory =
                    requireNotNull(record.construction)
                        .factory
                        .takeIf { it.isNotEmpty() }
                        ?.let { ".$it" }
                        .orEmpty()
                "$type$factory($arguments)"
            }

            else -> {
                "$type(${requireNotNull(fields(type)).joinToString { decode(it.type, "$expression.${it.name}") }})"
            }
        }

    /** Read the ordinary response shape before invoking the original Guest constructors/factories. */
    fun decodeCommand(
        type: String,
        reader: String,
    ): String =
        if (type == "Unit") {
            "$reader.unit()"
        } else {
            buildString {
                var next = 0

                fun read(type: String): String {
                    val expression =
                        if (type in resources) {
                            "${nativeImplementation(type)}($reader.int())"
                        } else {
                            val record = records[type]
                            if (record == null) {
                                val method = if (type == "ByteArray") "bytes" else type.replaceFirstChar(Char::lowercase)
                                "$reader.$method()"
                            } else {
                                val factory =
                                    record.construction
                                        ?.factory
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { ".$it" }
                                        .orEmpty()
                                if (factory.isEmpty() || record.fields.size != 1) appendLine("$reader.record(${record.fields.size})")
                                val arguments = record.fields.map { read(it.type) }.joinToString()
                                "$type$factory($arguments)"
                            }
                        }
                    val name = "__reply${next++}"
                    appendLine("val $name = $expression")
                    return name
                }
                val result = read(type)
                append(result)
            }
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
            if (type in resources) {
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
