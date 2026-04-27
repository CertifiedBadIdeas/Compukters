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
 * along with this program.  If not, see <https://www.gnu.org/licenses/\>.
 */

package ru.lazyhat.compukterkraft.common.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.core.computer.vm.api.PeripheralCallResult
import ru.lazyhat.compukterkraft.core.computer.vm.api.PeripheralMethods
import ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralDevice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlockPeripheralRegistryTest {
    @BeforeTest
    @AfterTest
    fun reset() {
        BlockPeripheralRegistry.resetForTesting()
    }

    @Test
    fun startsEmpty() {
        assertEquals(0, BlockPeripheralRegistry.registeredCount)
    }

    @Test
    fun registerAddsProviderAndReturnsItFromLookup() {
        val descriptor =
            BlockPeripheralDescriptor(
                device = VmPeripheralDevice(id = "test-1", type = "fake"),
                methods =
                    PeripheralMethods { method, _ ->
                        if (method == "ping") PeripheralCallResult.success("pong") else PeripheralCallResult.failure(method)
                    },
            )
        BlockPeripheralRegistry.register { descriptor }

        val resolved = BlockPeripheralRegistry.lookup(stubContext())

        assertNotNull(resolved)
        assertEquals("test-1", resolved.device.id)
        assertEquals(PeripheralCallResult.Success(listOf("pong")), resolved.methods.call("ping", emptyList()))
    }

    @Test
    fun lookupReturnsNullWhenNoProviderMatches() {
        BlockPeripheralRegistry.register { null }
        BlockPeripheralRegistry.register { null }

        val resolved = BlockPeripheralRegistry.lookup(stubContext())

        assertNull(resolved)
    }

    @Test
    fun firstMatchingProviderWins() {
        val first = BlockPeripheralDescriptor(VmPeripheralDevice(id = "first", type = "fake"))
        val second = BlockPeripheralDescriptor(VmPeripheralDevice(id = "second", type = "fake"))

        BlockPeripheralRegistry.register { first }
        BlockPeripheralRegistry.register { second }

        val resolved = BlockPeripheralRegistry.lookup(stubContext())

        assertNotNull(resolved)
        assertEquals("first", resolved.device.id)
    }

    private fun stubContext(): BlockPeripheralContext =
        object : BlockPeripheralContext {
            override val level: Level
                get() = error("BlockPeripheralRegistryTest providers must not dereference level")
            override val pos: BlockPos = BlockPos.ZERO
            override val side: Direction = Direction.UP
        }
}
