/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.addon.api.AddonRecordSchema
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
import ru.lazyhat.compukters.worker.value.ImmutableBytes

/** Validate actual record layouts against selected native modules, rather than a list of type names. */
fun validateAddonRecordResults(
    modules: List<PlatformModule>,
    schemas: List<AddonCapabilitySchema>,
    bindings: List<AddonGuestApiBinding>,
) {
    val byCapability = schemas.associateBy { it.identity }
    // Tombstone schemas remain in ABI history even after their source types disappear.
    val records = bindings.mapNotNull { byCapability.getValue(it.capability).operations[it.operation].resultRecord }.distinct()
    if (records.isEmpty()) return
    val candidates = modules.flatMap { PlatformMetadataCompiler().compile(it.id, it.sources).recordCandidates }.associateBy { it.symbol }
    CompuktersFirBuildEnvironment.create().use { environment ->
        val output =
            environment.compileGuest(
                PlatformModuleId("validation", "records"),
                listOf(PlatformSource("validation/Records.kt", ImmutableBytes.of("package validation".encodeToByteArray()))),
                modules,
            )
        val errors = output.diagnostics.diagnostics.filter { it.severity.name == "ERROR" }
        require(errors.isEmpty()) { "Cannot resolve selected record dependencies: ${errors.joinToString { it.renderMessage() }}" }
        val resolver = NativeBoundaryResolver(output.frontendOutput.session, candidates)
        val checked = mutableSetOf<AddonRecordSchema>()

        fun validate(schema: AddonRecordSchema) {
            if (!checked.add(schema)) return
            resolver.type(ClassId.topLevel(FqName(schema.typeName)).constructClassLikeType(emptyArray(), false))
            val record = resolver.records.getValue(schema.typeName)
            require(record.construction == null) { "Raw record ${schema.typeName} requires a validated factory representation" }
            require(record.fields.map { it.name } == schema.fields.map { it.name }) { "Record field layout mismatch: ${schema.typeName}" }
            record.fields.zip(schema.fields).forEach { (actual, encoded) ->
                val expected = encoded.record?.typeName ?: encoded.type.guestTypeName()
                require(actual.type == expected) { "Record field type mismatch: ${schema.typeName}.${actual.name}" }
                encoded.record?.let(::validate)
            }
        }
        records.forEach(::validate)
    }
}

private fun AddonCapabilityValueType.guestTypeName(): String =
    when (this) {
        AddonCapabilityValueType.I32 -> "Int"
        AddonCapabilityValueType.I64 -> "Long"
        AddonCapabilityValueType.F32 -> "Float"
        AddonCapabilityValueType.F64 -> "Double"
        AddonCapabilityValueType.BOOL -> "Boolean"
        AddonCapabilityValueType.CHAR -> "Char"
        AddonCapabilityValueType.STRING -> "String"
        else -> error("Not a scalar record field: $this")
    }
