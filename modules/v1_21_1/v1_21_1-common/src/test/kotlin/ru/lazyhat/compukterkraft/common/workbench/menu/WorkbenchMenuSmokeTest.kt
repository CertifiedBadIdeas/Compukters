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

package ru.lazyhat.compukterkraft.common.workbench.menu

import net.minecraft.world.inventory.MenuType
import ru.lazyhat.compukterkraft.common.workbench.context.ServerWorkbench
import ru.lazyhat.compukterkraft.common.workbench.data.WorkbenchContainerData
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage
import ru.lazyhat.compukterkraft.common.workbench.test.TestInventoryFactory
import ru.lazyhat.compukterkraft.common.workbench.test.TestMinecraftBootstrap
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkbenchMenuSmokeTest {
    @Test
    fun exposesActiveTargetSlotForWorkbenchHeader() {
        TestMinecraftBootstrap.ensureInitialized()

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                5,
                TestInventoryFactory.create(),
                WorkbenchContainerData(),
            )

        assertTrue(menu.slots.first().isActive)
    }

    @Test
    fun constructsWorkbenchMenuWithoutTarget() {
        TestMinecraftBootstrap.ensureInitialized()

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                1,
                TestInventoryFactory.create(),
                WorkbenchContainerData(),
            )

        assertEquals(1, menu.containerId)
        assertTrue(!menu.workspaceStateFlow.value.target.connected)
    }

    @Test
    fun constructsWorkbenchMenuWithTargetDescriptor() {
        TestMinecraftBootstrap.ensureInitialized()

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                2,
                TestInventoryFactory.create(),
                WorkbenchContainerData.from(
                    WorkbenchTargetState(
                        connected = true,
                        displayName = "Pocket Dev",
                        familyId = "advanced",
                    ),
                ),
            )

        assertEquals("Pocket Dev", menu.workspaceStateFlow.value.target.displayName)
        assertEquals("advanced", menu.workspaceStateFlow.value.target.familyId)
        assertTrue(menu.workspaceStateFlow.value.target.connected)
    }

    @Test
    fun handlesRebootActionAgainstServerWorkbenchSession() {
        TestMinecraftBootstrap.ensureInitialized()

        val workbench =
            ServerWorkbench(
                workspaceId = 22,
                workspace = ComputerWorkspaceHost(createTempDirectory("workbench-menu-reboot")),
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 9, displayName = "Pocket Dev", familyId = "advanced"),
            )

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                6,
                TestInventoryFactory.create(),
                WorkbenchContainerData.from(workbench.targetState()),
                workbench,
            )

        val remoteState = menu.handleWorkspaceAction(WorkbenchWorkspaceServerMessage.Action.REBOOT, "")

        assertNotNull(remoteState)
        assertTrue(remoteState.target.connected)
    }

    @Test
    fun updatesTerminalSnapshotLocally() {
        TestMinecraftBootstrap.ensureInitialized()

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                4,
                TestInventoryFactory.create(),
                WorkbenchContainerData(),
            )

        val snapshot = ScreenBuffer(16, 8, true).forceSnapshot()
        menu.updateScreenSnapshot(snapshot)

        assertEquals(snapshot, menu.screenSnapshot)
    }

    @Test
    fun exposesPlayerInventorySlotsInsideWorkbenchMenu() {
        TestMinecraftBootstrap.ensureInitialized()

        val menu =
            WorkbenchMenuWithoutInventory(
                MenuType.GENERIC_9x1,
                7,
                TestInventoryFactory.create(),
                WorkbenchContainerData(),
            )

        assertEquals(37, menu.slots.size)
        assertTrue(menu.slots.drop(1).all { it.isActive })
    }
}
