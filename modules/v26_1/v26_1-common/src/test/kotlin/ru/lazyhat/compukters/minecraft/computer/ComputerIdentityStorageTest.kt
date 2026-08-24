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

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ComputerIdentityStorageTest {
    @Test
    fun `new identities are nonzero distinct and survive an NBT round trip`() {
        val first = ComputerIdentityStorage()
        val second = ComputerIdentityStorage()
        assertTrue(first.id().toByteArray().any { it != 0.toByte() })
        assertNotEquals(first.id(), second.id())

        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, EMPTY_PROVIDER)
        first.save(output)
        val restored = ComputerIdentityStorage()
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, EMPTY_PROVIDER, output.buildResult()))

        assertEquals(first.id(), restored.id())
    }

    @Test
    fun `missing partial and zero identities are replaced atomically`() {
        val replacements =
            ArrayDeque(
                listOf(
                    ComputerId.fromLongs(99, 99),
                    ComputerId.fromLongs(1, 2),
                    ComputerId.fromLongs(3, 4),
                    ComputerId.fromLongs(5, 6),
                ),
            )
        val storage = ComputerIdentityStorage { replacements.removeFirst() }

        storage.load(input(CompoundTag()))
        assertEquals(ComputerId.fromLongs(1, 2), storage.id())
        storage.load(input(CompoundTag().also { it.putLong("computer_id_high", 9) }))
        assertEquals(ComputerId.fromLongs(3, 4), storage.id())
        storage.load(
            input(
                CompoundTag().also {
                    it.putLong("computer_id_high", 0)
                    it.putLong("computer_id_low", 0)
                },
            ),
        )
        assertEquals(ComputerId.fromLongs(5, 6), storage.id())
    }

    private fun input(tag: CompoundTag) = TagValueInput.create(ProblemReporter.DISCARDING, EMPTY_PROVIDER, tag)

    private companion object {
        val EMPTY_PROVIDER = HolderLookup.Provider.create(Stream.empty())
    }
}
