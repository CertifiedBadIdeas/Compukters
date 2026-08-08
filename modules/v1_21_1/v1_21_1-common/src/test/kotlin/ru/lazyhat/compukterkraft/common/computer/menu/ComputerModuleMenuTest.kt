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

package ru.lazyhat.compukterkraft.common.computer.menu

import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponentType
import net.minecraft.server.Bootstrap
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.module.SDK_ARTIFACT_IDENTITY_CODEC
import ru.lazyhat.compukterkraft.common.computer.module.SDK_ARTIFACT_IDENTITY_STREAM_CODEC
import ru.lazyhat.compukterkraft.common.computer.module.SdkModuleBay
import ru.lazyhat.compukterkraft.common.computer.module.sdkArtifactIdentity
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerModuleMenuTest {
    private val component =
        DataComponentType
            .builder<String>()
            .persistent(SDK_ARTIFACT_IDENTITY_CODEC)
            .networkSynchronized(SDK_ARTIFACT_IDENTITY_STREAM_CODEC)
            .build()

    init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @BeforeTest
    fun bindComponent() {
        ModObjects.sdkArtifactIdentityComponentType = { component }
        ModObjects.isKnownSdkArtifactIdentity = { it == "sdk_fixture_v1" }
    }

    @Test
    fun `menu has one module slot plus main inventory and hotbar`() {
        val fixture = Fixture()

        assertEquals(1 + Inventory.INVENTORY_SIZE, fixture.menu.slots.size)
        assertEquals(NotebookComputerMenu.MODULE_SLOT_X, fixture.menu.slots[0].x)
        assertEquals(NotebookComputerMenu.MODULE_SLOT_Y, fixture.menu.slots[0].y)
    }

    @Test
    fun `player inventory shift click moves one module into bay and back`() {
        val fixture = Fixture()
        fixture.playerInventory.setItem(9, module(count = 3))

        assertFalse(fixture.menu.quickMoveStack(1).isEmpty)
        assertEquals("sdk_fixture_v1", fixture.menu.moduleStack.sdkArtifactIdentity)
        assertEquals(2, fixture.playerInventory.getItem(9).count)

        assertFalse(fixture.menu.quickMoveStack(0).isEmpty)
        assertTrue(fixture.menu.moduleStack.isEmpty)
        assertEquals(3, fixture.playerInventory.getItem(9).count)
    }

    @Test
    fun `shift click cannot mutate module bay while runtime is on`() {
        val fixture = Fixture()
        fixture.playerInventory.setItem(9, module())
        fixture.powered = true

        assertTrue(fixture.menu.quickMoveStack(1).isEmpty)
        assertTrue(fixture.menu.moduleStack.isEmpty)
        assertEquals(1, fixture.playerInventory.getItem(9).count)
    }

    @Test
    fun `failed module commit does not consume carried stack`() {
        val fixture = Fixture(commitSucceeds = false)
        val carried = module(count = 3)

        fixture.menu.slots[0].safeInsert(carried)

        assertEquals(3, carried.count)
        assertTrue(fixture.menu.moduleStack.isEmpty)
    }

    @Test
    fun `slot extraction cannot bypass live power guard`() {
        val fixture = Fixture()
        fixture.bay.restoreStoredItem(module())
        fixture.powered = true

        val removed =
            fixture.menu
                .slots[0]
                .remove(1)
        assertTrue(removed.isEmpty)
        assertEquals("sdk_fixture_v1", fixture.menu.moduleStack.sdkArtifactIdentity)
    }

    private fun module(count: Int = 1): ItemStack =
        ItemStack(Items.PAPER, count).also {
            it.sdkArtifactIdentity = "sdk_fixture_v1"
        }

    private inner class Fixture(
        private val commitSucceeds: Boolean = true,
    ) {
        var powered: Boolean = false
        val playerInventory = SimpleContainer(Inventory.INVENTORY_SIZE)
        val bay =
            SdkModuleBay(
                artifactIdentity = { it.sdkArtifactIdentity },
                isKnownArtifact = ModObjects.isKnownSdkArtifactIdentity,
                isRuntimeOn = { powered },
                commitMutation = { mutation ->
                    if (commitSucceeds) mutation()
                    commitSucceeds
                },
            )
        val menu =
            NotebookComputerMenu(
                menuType = menuType(),
                containerId = 1,
                playerInventory = playerInventory,
                family = DeviceFamily.NORMAL,
                moduleBay = bay,
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun menuType(): MenuType<NotebookComputerMenu> = MenuType.GENERIC_9x1 as MenuType<NotebookComputerMenu>
}
