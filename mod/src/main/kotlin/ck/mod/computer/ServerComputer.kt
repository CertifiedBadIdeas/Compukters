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

package ck.mod.computer

import ck.lang.frontend.FrontendSeverity
import ck.lang.runtime.BytecodeComputerProgram
import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.HostCall
import ck.lang.runtime.HostResult
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
import ck.mod.LOGGER
import ck.mod.computer.vm.ComputerProfileRegistry
import ck.mod.computer.vm.ComputerVmCallbacks
import ck.mod.computer.vm.ComputerVmLogger
import ck.mod.context.ServerContext
import ck.mod.gui.NetworkedTerminal
import ck.mod.gui.TerminalState
import ck.mod.language.LanguageServices
import ck.mod.menu.ComputerMenu
import ck.mod.network.client.ComputerTerminalClientMessage
import ck.mod.network.server.ServerNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    val blockPos: BlockPos,
    properties: ComputerProperties,
) : ComputerEvents.Receiver,
    ComputerVmCallbacks {
    val family = properties.family
    private val profile = ComputerProfileRegistry.forFamily(family)

    @Volatile
    private var terminalDirty = true
    val terminal =
        NetworkedTerminal(
            profile.terminalWidth,
            profile.terminalHeight,
            profile.colorTerminal,
            Runnable { terminalDirty = true },
        )
    private val logger = ComputerVmLogger { message -> LOGGER.info { message } }
    private var label: String? = properties.label
    private var vmHandle: ComputerVmHandle? = null
    private var rebootRequested = false

    init {
        LOGGER.info { "ComputerID: $instanceID init" }
    }

    var isOn = false
        private set

    fun checkUsable(player: Player) = family.checkUsable(player)

    fun updateLabel(value: String?) {
        label = value
    }

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        if (!isOn) return
        val accepted = vmHandle?.enqueueEvent(VmEvent(event, arguments.toList())) == true
        if (!accepted) {
            LOGGER.warn { "ComputerID: $instanceID dropped event $event" }
        }
    }

    fun shutdown() {
        LOGGER.info { "ComputerID: $instanceID shutdown" }
        vmHandle?.stop(VmStopReason.REQUESTED)
    }

    fun turnOn() {
        if (isOn) return
        LOGGER.info { "ComputerID: $instanceID turnOn" }
        ServerContext.vmSupervisor.ensureWorkspaceInitialized(instanceID)
        val bootScriptDocument = ServerContext.vmSupervisor.workspace.readDocument(instanceID, profile.bootScriptName)
        if (bootScriptDocument == null) {
            writeLineToTerminal("Missing boot script in workspace: ${profile.bootScriptName}")
            return
        }

        ServerContext.vmSupervisor.remove(instanceID, VmStopReason.CLOSED)
        val handle = ServerContext.vmSupervisor.getOrCreate(instanceID, profile, this, logger)
        val artifact = LanguageServices.frontend.compile(bootScriptDocument.path, bootScriptDocument.text)
        val module = artifact.module
        if (module == null || artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR }) {
            val message = artifact.analysis.diagnostics.joinToString { it.message }
            writeLineToTerminal("Compilation Error: $message")
            LOGGER.error { message }
            ServerContext.vmSupervisor.remove(instanceID, VmStopReason.CLOSED)
            return
        }

        terminal.reset()
        vmHandle = handle
        rebootRequested = false
        isOn = handle.start(BytecodeComputerProgram(module))
        if (isOn) {
            handle.enqueueEvent(VmEvent("boot"))
        }
    }

    fun reboot() {
        LOGGER.info { "ComputerID: $instanceID reboot" }
        rebootRequested = true
        vmHandle?.stop(VmStopReason.REBOOT) ?: turnOn()
    }

    fun close() {
        LOGGER.info { "ComputerID: $instanceID close" }
        ServerContext.vmSupervisor.remove(instanceID, VmStopReason.CLOSED)
        vmHandle = null
        isOn = false
        terminalDirty = true
    }

    fun serverTick() {
        val handle = vmHandle
        if (handle == null) {
            syncTerminal()
            return
        }
        handle.requestSlice(level.gameTime)

        val results = handle.drainHostCalls().map(::applyHostCall)
        if (results.isNotEmpty()) {
            handle.deliverHostResults(results)
        }

        syncTerminal()

        val snapshot = handle.snapshot()
        if (snapshot.state == VmState.STOPPED || snapshot.state == VmState.CRASHED) {
            if (snapshot.state == VmState.CRASHED && snapshot.errorMessage != null) {
                writeLineToTerminal("VM crash: ${snapshot.errorMessage}")
            }

            ServerContext.vmSupervisor.remove(instanceID, VmStopReason.CLOSED)
            vmHandle = null
            isOn = false

            if (snapshot.stopReason == VmStopReason.REBOOT || rebootRequested) {
                rebootRequested = false
                turnOn()
            }
        }
    }

    override fun currentLabel(): String? = label

    override fun onVmStop(reason: VmStopReason) = Unit

    override fun onVmRebootRequested() {
        rebootRequested = true
    }

    private fun applyHostCall(call: HostCall): HostResult =
        try {
            when (call) {
                is HostCall.TerminalWrite -> {
                    if (call.newLine) {
                        writeLineToTerminal(call.text)
                    } else {
                        terminal.write(call.text)
                    }
                    HostResult.Success(call.id)
                }

                is HostCall.TerminalClear -> {
                    terminal.clear()
                    terminal.setCursorPos(0, 0)
                    HostResult.Success(call.id)
                }

                is HostCall.TerminalSetCursor -> {
                    terminal.setCursorPos(call.x, call.y)
                    HostResult.Success(call.id)
                }

                is HostCall.FileExists -> {
                    HostResult.Success(call.id, ServerContext.vmSupervisor.workspace.readDocument(instanceID, call.path) != null)
                }

                is HostCall.FileReadText -> {
                    HostResult.Success(
                        call.id,
                        ServerContext.vmSupervisor.workspace
                            .readDocument(instanceID, call.path)
                            ?.text,
                    )
                }

                is HostCall.FileWriteText -> {
                    ServerContext.vmSupervisor.workspace.writeDocument(instanceID, call.path, call.text)
                    HostResult.Success(call.id)
                }

                is HostCall.FileList -> {
                    HostResult.Success(call.id, ServerContext.vmSupervisor.workspace.list(instanceID, call.path))
                }
            }
        } catch (failure: Throwable) {
            HostResult.Failure(call.id, failure.message ?: failure.javaClass.simpleName)
        }

    private fun writeLineToTerminal(text: String) {
        terminal.write(text.take(terminal.width))
        if (terminal.cursorY >= terminal.height - 1) {
            terminal.scroll(1)
            terminal.setCursorPos(0, terminal.height - 1)
        } else {
            terminal.setCursorPos(0, terminal.cursorY + 1)
        }
    }

    private fun syncTerminal() {
        if (!terminalDirty) return
        val terminalState = TerminalState.create(terminal)
        for (player in watchingPlayers()) {
            ServerNetworking.sendToPlayer(ComputerTerminalClientMessage(player.containerMenu, terminalState), player)
        }
        terminalDirty = false
    }

    private fun watchingPlayers(): List<ServerPlayer> =
        ServerContext.server.playerList.players.filter { player ->
            val menu = player.containerMenu
            menu is ComputerMenu && menu.getComputerPublic().instanceID == instanceID
        }
}
