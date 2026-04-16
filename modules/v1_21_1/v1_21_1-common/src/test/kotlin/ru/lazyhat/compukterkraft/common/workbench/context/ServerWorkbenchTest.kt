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

package ru.lazyhat.compukterkraft.common.workbench.context

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.common.utils.computerFamilyId
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.updateComputerData
import ru.lazyhat.compukterkraft.common.workbench.test.TestMinecraftBootstrap
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerWorkbenchTest {
    @Test
    fun extractsTargetDescriptorFromComputerItem() {
        TestMinecraftBootstrap.ensureInitialized()

        val root = createTempDirectory("server-workbench-target")
        val workspace = ComputerWorkspaceHost(root)
        val stack =
            ItemStack(Items.STONE).apply {
                updateComputerData {
                    computerID = 73
                    computerFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Pocket Dev"))
            }

        val workbench = ServerWorkbench(workspaceId = 11, workspace = workspace)
        workbench.setTarget(stack)

        assertEquals(73, workbench.targetDescriptor().computerId)
        assertEquals("Pocket Dev", workbench.targetState().displayName)
        assertEquals("advanced", workbench.targetState().familyId)
        assertTrue(workbench.targetState().connected)
    }

    @Test
    fun targetWithoutComputerIdStaysDisconnected() {
        TestMinecraftBootstrap.ensureInitialized()

        val stack =
            ItemStack(Items.STONE).apply {
                updateComputerData {
                    computerFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Unbound Pocket"))
            }

        val workbench = ServerWorkbench(workspaceId = 11, workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-unbound")))
        workbench.setTarget(stack)

        assertEquals("Unbound Pocket", workbench.targetState().displayName)
        assertEquals("advanced", workbench.targetState().familyId)
        assertFalse(workbench.targetState().connected)
    }

    @Test
    fun keepsAuthoringWorkspaceSeparateByWorkbenchId() {
        val root = createTempDirectory("server-workbench-workspace")
        val workspace = ComputerWorkspaceHost(root)
        val workbench = ServerWorkbench(workspaceId = 41, workspace = workspace)

        workbench.write("main.ck", "fun main() {}")

        val localDocument = workbench.read("main.ck")
        val unrelatedDocument = workspace.readDocument(7, "main.ck")

        assertNotNull(localDocument)
        assertEquals("fun main() {}", localDocument.text)
        assertEquals(emptyList(), workspace.list(7, ""))
        assertEquals(null, unrelatedDocument)
    }

    @Test
    fun writeMarksLocalWorkspaceDirtyUntilPushOrPull() {
        val root = createTempDirectory("server-workbench-sync")
        val workspace = ComputerWorkspaceHost(root)
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = workspace,
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )

        workbench.write("main.ck", "print(1)")
        assertTrue(workbench.snapshot("main.ck").sync.dirtyLocal)

        workbench.pushToTarget()
        assertFalse(workbench.snapshot("main.ck").sync.dirtyLocal)

        workbench.write("main.ck", "print(2)")
        workbench.pullFromTarget()
        assertFalse(workbench.snapshot("main.ck").sync.dirtyLocal)
    }

    @Test
    fun pullAndPushMirrorWorkspaceByTargetComputerId() {
        val root = createTempDirectory("server-workbench-mirror")
        val workspace = ComputerWorkspaceHost(root)
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = workspace,
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )

        workspace.writeDocument(5, "main.ck", "print(1)")
        workspace.writeDocument(5, "nested/program.ck", "print(2)")

        workbench.pullFromTarget()

        assertEquals("print(1)", workspace.readDocument(17, "main.ck")?.text)
        assertEquals("print(2)", workspace.readDocument(17, "nested/program.ck")?.text)

        workbench.write("main.ck", "print(3)")
        workbench.pushToTarget()

        assertEquals("print(3)", workspace.readDocument(5, "main.ck")?.text)
        assertEquals("print(2)", workspace.readDocument(5, "nested/program.ck")?.text)
    }

    @Test
    fun runAndAttachDelegateToRuntimeBridge() {
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-runtime")),
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )
        val bridge = FakeRuntimeBridge()
        workbench.bindRuntimeBridge(bridge)

        workbench.runTargetProgram()
        workbench.attachTerminal()
        workbench.handleInput(ru.lazyhat.compukterkraft.core.computer.input.KeyInputEvent.Down(28, repeat = false))

        assertEquals(listOf("run:5", "attach:5", "event:key"), bridge.calls)
        assertNotNull(workbench.currentScreenSnapshot())
    }

    private class FakeRuntimeBridge : WorkbenchTargetRuntimeBridge {
        val calls = mutableListOf<String>()
        private val snapshot = ScreenBuffer(16, 8, true).forceSnapshot()

        override fun runTargetProgram(target: ServerWorkbench.TargetDescriptor) {
            calls += "run:${target.computerId}"
        }

        override fun attachTerminal(target: ServerWorkbench.TargetDescriptor) {
            calls += "attach:${target.computerId}"
        }

        override fun queueEvent(
            target: ServerWorkbench.TargetDescriptor,
            event: String,
            arguments: Array<Any>,
        ): Boolean {
            calls += "event:$event"
            return true
        }

        override fun currentScreenSnapshot(target: ServerWorkbench.TargetDescriptor) = snapshot
    }
}