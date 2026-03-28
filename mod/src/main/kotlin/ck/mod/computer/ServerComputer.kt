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

import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.ScreenBufferSnapshot
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
import ck.mod.LOGGER
import ck.mod.application.runtime.ComputerProgramCompiler
import ck.mod.application.runtime.HostCallDispatcher
import ck.mod.application.runtime.WorkspaceProgramLoader
import ck.mod.computer.vm.ComputerProfileRegistry
import ck.mod.computer.vm.ComputerVmCallbacks
import ck.mod.computer.vm.ComputerVmLogger
import ck.mod.context.ServerContext
import ck.mod.language.LanguageServices
import ck.mod.menu.ComputerMenu
import ck.mod.network.client.ComputerTerminalClientMessage
import ck.mod.network.server.ServerNetworking
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * Server-side representation of a single computer instance.
 *
 * Owns the [ComputerVmHandle] and orchestrates the VM lifecycle:
 * boot → tick → sync screen → detect stop/crash/reboot.
 *
 * Terminal output is read from the VM's [ScreenBuffer] as an immutable
 * [ScreenBufferSnapshot] each tick and forwarded to watching players.
 */
class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    properties: ComputerProperties,
) : ComputerEvents.Receiver,
    ComputerVmCallbacks {
    val family = properties.family
    private val profile = ComputerProfileRegistry.forFamily(family)

    private val logger = ComputerVmLogger { message -> LOGGER.info { message } }
    private var label: String? = properties.label
    private var vmHandle: ComputerVmHandle? = null

    /** Last known screen snapshot — used for initial sync when new players open the GUI. */
    @Volatile
    var lastScreenSnapshot: ScreenBufferSnapshot? = null
        private set

    /**
     * Set by [onVmRebootRequested] from the VM coroutine, consumed by [serverTick].
     */
    @Volatile
    private var rebootRequested = false

    private val computerManager get() = ServerContext.computerManager

    private val programLoader by lazy {
        WorkspaceProgramLoader(computerManager.workspace, LanguageServices::bundledScript)
    }
    private val hostCallDispatcher by lazy {
        HostCallDispatcher(instanceID, computerManager.workspace)
    }

    init {
        LOGGER.info { "ComputerID: $instanceID init" }
    }

    /**
     * Whether the VM is currently running.
     * Derived from the VM handle snapshot — no separate boolean to keep in sync.
     */
    val isOn: Boolean
        get() {
            val handle = vmHandle ?: return false
            val state = handle.snapshot().state
            return state != VmState.COLD && state != VmState.STOPPED && state != VmState.CRASHED
        }

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
        computerManager.ensureWorkspaceInitialized(instanceID)
        val bootScript = programLoader.load(instanceID, profile.bootScriptName)
        if (bootScript == null) {
            LOGGER.error { "Missing boot script in workspace: ${profile.bootScriptName}" }
            return
        }

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        val handle = computerManager.getOrCreateVm(instanceID, profile, this, logger)
        val compiledProgram = ComputerProgramCompiler.compile(bootScript.path, bootScript.source)
        val program = compiledProgram.program
        if (program == null) {
            val message = compiledProgram.errorMessage.orEmpty()
            LOGGER.error { "Compilation Error: $message" }
            computerManager.removeVm(instanceID, VmStopReason.CLOSED)
            return
        }

        vmHandle = handle
        rebootRequested = false
        val started = handle.start(program)
        if (started) {
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
        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        vmHandle = null
    }

    fun serverTick() {
        val handle = vmHandle
        if (handle == null) {
            return
        }
        handle.requestSlice(level.gameTime)

        // Dispatch filesystem host calls (terminal writes no longer go through HostCall)
        val results = handle.drainHostCalls().map(hostCallDispatcher::dispatch)
        if (results.isNotEmpty()) {
            handle.deliverHostResults(results)
        }

        // Sync screen buffer to watching players
        syncScreen(handle)

        // Check for VM stop / crash / reboot
        val snapshot = handle.snapshot()
        if (snapshot.state == VmState.STOPPED || snapshot.state == VmState.CRASHED) {
            if (snapshot.state == VmState.CRASHED && snapshot.errorMessage != null) {
                LOGGER.warn { "ComputerID: $instanceID VM crash: ${snapshot.errorMessage}" }
            }

            computerManager.removeVm(instanceID, VmStopReason.CLOSED)
            vmHandle = null

            if (snapshot.stopReason == VmStopReason.REBOOT || rebootRequested) {
                rebootRequested = false
                turnOn()
            }
        }
    }

    // ── ComputerVmCallbacks ─────────────────────────────────────────

    override fun currentLabel(): String? = label

    override fun onVmStop(reason: VmStopReason) = Unit

    override fun onVmRebootRequested() {
        rebootRequested = true
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun syncScreen(handle: ComputerVmHandle) {
        val screenSnapshot = handle.readScreenSnapshot() ?: return
        lastScreenSnapshot = screenSnapshot
        val players = watchingPlayers()
        if (players.isEmpty()) return
        for (player in players) {
            ServerNetworking.sendToPlayer(
                ComputerTerminalClientMessage(player.containerMenu, screenSnapshot),
                player,
            )
        }
    }

    private fun watchingPlayers(): List<ServerPlayer> =
        ServerContext.server.playerList.players.filter { player ->
            val menu = player.containerMenu
            menu is ComputerMenu && menu.serverSide.computer.instanceID == instanceID
        }
}
