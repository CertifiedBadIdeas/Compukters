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

package ru.lazyhat.compukterkraft.common.network

import net.minecraft.client.Minecraft
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.text.ClientTableFormatter
import ru.lazyhat.compukterkraft.common.network.text.TableBuilder
import ru.lazyhat.compukterkraft.common.serial.menu.SerialTerminalMenu
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.core.workbench.EditorPresence
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.workbench.crdt.TextRun
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta

class ClientNetworkContextImpl : ClientNetworkContext {
    private val minecraft: Minecraft
        get() = Minecraft.getInstance()

    private inline fun withCheckedContainerMenu(
        deviceId: Int,
        block: ComputerMenu.() -> Unit,
    ) {
        minecraft
            .player
            ?.containerMenu
            ?.takeIf { it.containerId == deviceId }
            ?.let { it as? ComputerMenu }
            ?.run(block)
    }

    private inline fun withCheckedWorkbenchMenu(
        containerId: Int,
        block: AbstractWorkbenchMenu.() -> Unit,
    ) {
        minecraft
            .player
            ?.containerMenu
            ?.takeIf { it.containerId == containerId }
            ?.let { it as? AbstractWorkbenchMenu }
            ?.run(block)
    }

    private inline fun withCheckedSerialTerminalMenu(
        containerId: Int,
        block: SerialTerminalMenu.() -> Unit,
    ) {
        minecraft
            .player
            ?.containerMenu
            ?.takeIf { it.containerId == containerId }
            ?.let { it as? SerialTerminalMenu }
            ?.run(block)
    }

    override fun handleChatTable(table: TableBuilder) {
        ClientTableFormatter(minecraft).display(table)
    }

    override fun handleDisplayFrame(
        containerId: Int,
        frame: DisplayFrameDelta,
    ) = withCheckedContainerMenu(containerId) {
        handleDisplayFrame(frame)
    }

    override fun handleNativeDisplayFrameBytes(
        containerId: Int,
        payload: ByteArray,
    ) = withCheckedContainerMenu(containerId) {
        for (frame in NativeDisplayFrameCodec.decodeFrames(payload)) {
            handleDisplayFrame(frame)
        }
    }

    override fun handleSerialConsoleOutput(
        containerId: Int,
        bytes: ByteArray,
        reset: Boolean,
    ) = withCheckedSerialTerminalMenu(containerId) {
        applySerialOutput(bytes, reset)
    }

    override fun handleWorkbenchWorkspace(
        containerId: Int,
        remoteState: WorkbenchRemoteState,
    ) = withCheckedWorkbenchMenu(containerId) {
        updateRemoteState(remoteState)
    }

    override fun handleWorkbenchOps(
        containerId: Int,
        path: String,
        ops: List<Op>,
        ackedClock: Int,
    ) = withCheckedWorkbenchMenu(containerId) {
        applyOpsAck(path, ops, ackedClock)
    }

    override fun handleWorkbenchDocumentSnapshot(
        containerId: Int,
        path: String,
        runs: List<TextRun>,
        versionVector: Map<SiteId, Int>,
    ) = withCheckedWorkbenchMenu(containerId) {
        applyDocumentSnapshot(path, runs, versionVector)
    }

    override fun handleWorkbenchPresence(
        containerId: Int,
        presences: List<EditorPresence>,
    ) = withCheckedWorkbenchMenu(containerId) {
        updatePresences(presences)
    }

    override fun handleWorkbenchCursor(
        containerId: Int,
        path: String,
        siteId: SiteId,
        cursor: CursorAnchor?,
    ) = withCheckedWorkbenchMenu(containerId) {
        applyRemoteCursor(path, siteId, cursor)
    }
}
