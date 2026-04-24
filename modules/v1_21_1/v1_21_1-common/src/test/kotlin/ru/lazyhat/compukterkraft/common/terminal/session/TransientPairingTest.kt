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
package ru.lazyhat.compukterkraft.common.terminal.session

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransientPairingTest {
    private val dim: ResourceKey<net.minecraft.world.level.Level> =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"))

    @AfterTest
    fun cleanup() {
        TransientPairing.clearAll()
    }

    @Test
    fun `set and get round-trip`() {
        val uuid = UUID.randomUUID()
        val binding = TransientPairing.Binding(instanceId = 42, blockPos = BlockPos(1, 2, 3), dimensionId = dim)
        TransientPairing.set(uuid, binding)
        assertEquals(binding, TransientPairing.get(uuid))
    }

    @Test
    fun `clear removes binding`() {
        val uuid = UUID.randomUUID()
        TransientPairing.set(uuid, TransientPairing.Binding(1, BlockPos.ZERO, dim))
        TransientPairing.clear(uuid)
        assertNull(TransientPairing.get(uuid))
    }

    @Test
    fun `bindings are per-player`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        TransientPairing.set(a, TransientPairing.Binding(1, BlockPos(0, 0, 0), dim))
        TransientPairing.set(b, TransientPairing.Binding(2, BlockPos(10, 10, 10), dim))
        assertEquals(1, TransientPairing.get(a)?.instanceId)
        assertEquals(2, TransientPairing.get(b)?.instanceId)
    }

    @Test
    fun `clearAll resets everything`() {
        TransientPairing.set(UUID.randomUUID(), TransientPairing.Binding(1, BlockPos.ZERO, dim))
        TransientPairing.set(UUID.randomUUID(), TransientPairing.Binding(2, BlockPos.ZERO, dim))
        TransientPairing.clearAll()
        assertEquals(0, TransientPairing.size())
    }

    @Test
    fun `set overwrites existing binding`() {
        val uuid = UUID.randomUUID()
        TransientPairing.set(uuid, TransientPairing.Binding(1, BlockPos(0, 0, 0), dim))
        TransientPairing.set(uuid, TransientPairing.Binding(2, BlockPos(5, 5, 5), dim))
        assertEquals(2, TransientPairing.get(uuid)?.instanceId)
        assertEquals(BlockPos(5, 5, 5), TransientPairing.get(uuid)?.blockPos)
    }
}
