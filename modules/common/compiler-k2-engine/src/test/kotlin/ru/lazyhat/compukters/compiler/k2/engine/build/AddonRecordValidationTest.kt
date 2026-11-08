/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.addon.api.AddonCapabilityIdentity
import ru.lazyhat.compukters.addon.api.AddonCapabilityOperation
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.addon.api.AddonRecordField
import ru.lazyhat.compukters.addon.api.AddonRecordSchema
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AddonRecordValidationTest {
    @Test
    fun `record layout admission resolves the actual selected native dependency`() {
        val vector =
            AddonRecordSchema("kotlin.math.Vector3d", listOf("x", "y", "z").map { AddonRecordField(it, AddonCapabilityValueType.F64) })
        validate(vector)
        for (invalid in listOf(
            AddonRecordSchema(vector.typeName, vector.fields.reversed()),
            AddonRecordSchema(vector.typeName, vector.fields.map { it.copy(type = AddonCapabilityValueType.F32) }),
            AddonRecordSchema("kotlin.math.Rotation3d", vector.fields),
            AddonRecordSchema("missing.Record", vector.fields),
        )) {
            assertFailsWith<IllegalArgumentException>(invalid.typeName) { validate(invalid) }
        }
    }

    private fun validate(record: AddonRecordSchema) {
        val platform =
            PlatformBundleCodec.decode(
                Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.native.contract.platform")))),
            )
        val identity = AddonCapabilityIdentity("fixture", "fixture", 1, 0)
        validateAddonRecordResults(
            listOf(platform.builtins) + platform.modules,
            listOf(
                AddonCapabilitySchema(
                    identity,
                    listOf(AddonCapabilityOperation(emptyList(), AddonCapabilityValueType.RECORD, true, record)),
                ),
            ),
            listOf(AddonGuestApiBinding("fixture", "Bindings", "read", "fun():${record.typeName}", identity, 0)),
        )
    }
}
