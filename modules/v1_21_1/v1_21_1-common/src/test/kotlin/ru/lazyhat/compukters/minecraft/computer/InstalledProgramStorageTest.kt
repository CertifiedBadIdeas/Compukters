/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledProgramStorageTest {
    @Test
    fun `install and reads are defensive copies`() {
        val storage = InstalledProgramStorage(maximumArtifactBytes = 3)
        val input = byteArrayOf(1, 2, 3)

        storage.install(input)
        input[0] = 9
        val firstRead = storage.artifact()
        assertContentEquals(byteArrayOf(1, 2, 3), firstRead)
        requireNotNull(firstRead)[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), storage.artifact())
    }

    @Test
    fun `invalid install is rejected before replacing current artifact`() {
        val storage = InstalledProgramStorage(maximumArtifactBytes = 3)
        storage.install(byteArrayOf(1, 2, 3))

        assertFailsWith<IllegalArgumentException> { storage.install(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { storage.install(byteArrayOf(1, 2, 3, 4)) }

        assertContentEquals(byteArrayOf(1, 2, 3), storage.artifact())
    }

    @Test
    fun `versioned NBT round trip uses one namespaced compound`() {
        val storage = InstalledProgramStorage(maximumArtifactBytes = 3)
        storage.install(byteArrayOf(1, 2, 3))
        val root = CompoundTag()

        storage.save(root)

        assertEquals(setOf("compukters"), root.allKeys)
        val payload = root.getCompound("compukters")
        assertEquals(setOf("schema", "artifact"), payload.allKeys)
        assertEquals(1, payload.getInt("schema"))
        assertContentEquals(byteArrayOf(1, 2, 3), payload.getByteArray("artifact"))

        val restored = InstalledProgramStorage(maximumArtifactBytes = 3)
        restored.load(root)
        assertContentEquals(byteArrayOf(1, 2, 3), restored.artifact())
    }

    @Test
    fun `missing artifact saves nothing and clear reports actual changes`() {
        val storage = InstalledProgramStorage(maximumArtifactBytes = 3)
        val root = CompoundTag()

        storage.save(root)
        assertTrue(root.isEmpty)
        assertFalse(storage.clear())
        storage.install(byteArrayOf(1))
        assertTrue(storage.clear())
        assertNull(storage.artifact())
        assertFalse(storage.clear())
    }

    @Test
    fun `load rejects malformed unsupported empty and oversized payloads`() {
        val storage = InstalledProgramStorage(maximumArtifactBytes = 3)
        storage.install(byteArrayOf(9))

        val invalidRoots =
            listOf(
                CompoundTag(),
                rootTag(schema = 2, artifact = byteArrayOf(1)),
                rootTag(schema = 1, artifact = byteArrayOf()),
                rootTag(schema = 1, artifact = byteArrayOf(1, 2, 3, 4)),
                CompoundTag().apply { putInt("compukters", 1) },
            )

        invalidRoots.forEach { root ->
            storage.install(byteArrayOf(9))
            storage.load(root)
            assertNull(storage.artifact())
        }
    }

    private fun rootTag(
        schema: Int,
        artifact: ByteArray,
    ): CompoundTag =
        CompoundTag().apply {
            put(
                "compukters",
                CompoundTag().apply {
                    putInt("schema", schema)
                    putByteArray("artifact", artifact)
                },
            )
        }
}
