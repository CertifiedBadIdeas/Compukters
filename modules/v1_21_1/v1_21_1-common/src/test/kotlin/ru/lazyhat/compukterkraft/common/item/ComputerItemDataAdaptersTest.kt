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

package ru.lazyhat.compukterkraft.common.item

import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.common.utils.computerDataTag
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabelByHoverName
import ru.lazyhat.compukterkraft.common.utils.updateComputerData
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ComputerItemDataAdaptersTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun roundTripsComputerItemDataThroughComponentBackedStack() {
        val stack = ItemStack(Items.STONE)
        val expected = ComputerItemData(computerId = 5, label = "alpha")

        stack.writeComputerItemData(expected)

        assertEquals(expected, stack.readComputerItemData())
        assertEquals("alpha", stack.computerLabelByHoverName)
    }

    @Test
    fun readsLabelFromCurrentComputerItemCreatePath() {
        val stack = ItemStack(Items.STONE).apply {
            updateComputerData { computerID = 5 }
            set(DataComponents.CUSTOM_NAME, Component.literal("alpha"))
        }

        assertEquals(ComputerItemData(computerId = 5, label = "alpha"), stack.readComputerItemData())
    }

    @Test
    fun overwritingWithNullClearsStoredAndVisibleLabel() {
        val stack = ItemStack(Items.STONE).apply {
            updateComputerData { computerID = 5 }
            set(DataComponents.CUSTOM_NAME, Component.literal("alpha"))
        }

        stack.writeComputerItemData(ComputerItemData(computerId = null, label = null))

        assertEquals(ComputerItemData(computerId = null, label = null), stack.readComputerItemData())
        assertFalse(stack.computerDataTag?.contains("ComputerID") == true)
        assertFalse(stack.computerDataTag?.contains("Label") == true)
        assertFalse(stack.has(DataComponents.CUSTOM_NAME))
        assertEquals(null, stack.computerLabelByHoverName)
    }
}