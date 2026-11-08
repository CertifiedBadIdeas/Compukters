/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonRecordField
import ru.lazyhat.compukters.addon.api.AddonRecordSchema

/** The native analyzer has already checked provenance, resolved fields and constructor safety. */
internal fun nativeResultSchemas(contract: DeclarativeGuestContract): Map<String, AddonRecordSchema> {
    val boundary = GuestBoundaryTypes(contract)
    val records = linkedMapOf<String, AddonRecordSchema>()

    fun resolve(type: String): AddonRecordSchema {
        records[type]?.let { return it }
        if (boundary.construction(type)?.factory?.isNotEmpty() == true && boundary.fields(type)?.size == 1) {
            return resolve(requireNotNull(boundary.fields(type)).single().type)
        }
        val name = boundary.wireType(type)
        val fields = requireNotNull(boundary.fields(type)) { "Unknown native record: $type" }
        val schema =
            AddonRecordSchema(
                name,
                fields.map { field ->
                    val wireType = boundary.wireType(field.type)
                    val scalar =
                        when (wireType) {
                            "Int" -> AddonCapabilityValueType.I32
                            "Long" -> AddonCapabilityValueType.I64
                            "Float" -> AddonCapabilityValueType.F32
                            "Double" -> AddonCapabilityValueType.F64
                            "Boolean" -> AddonCapabilityValueType.BOOL
                            "Char" -> AddonCapabilityValueType.CHAR
                            "String" -> AddonCapabilityValueType.STRING
                            else -> null
                        }
                    if (scalar != null) {
                        AddonRecordField(field.name, scalar)
                    } else {
                        AddonRecordField(field.name, AddonCapabilityValueType.RECORD, resolve(field.type))
                    }
                },
            )
        records[type] = schema
        return schema
    }
    contract.operations.forEach { operation ->
        if (operation.result in contract.boundaryRecords && boundary.wireType(operation.result).contains('.')) resolve(operation.result)
    }
    val result = records.values.associateBy { it.typeName }.toMutableMap()
    records.values.groupBy { it.typeName.substringAfterLast('.') }.forEach { (name, schemas) ->
        require(schemas.distinct().size == 1) { "Ambiguous wire record name: $name" }
        result[name] = schemas.first()
    }
    return result
}
