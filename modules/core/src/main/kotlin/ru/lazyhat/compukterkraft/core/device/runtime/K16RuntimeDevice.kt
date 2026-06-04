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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.runtime.ports.NoopDisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

interface RuntimeDeviceSerialEndpoint {
    fun pushSerialInput(bytes: ByteArray)

    fun serialOutputSnapshot(): ByteArray

    fun clearSerialOutput()
}

class K16RuntimeDevice(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val endpointFactory: () -> K16ComputerEndpoint,
    private val stateSink: DeviceStateSink,
    private val displayNetwork: DisplayNetworkBridge = NoopDisplayNetworkBridge,
) : RuntimeDevice,
    RuntimeDeviceSerialEndpoint,
    RuntimeDeviceSnapshotPersistence,
    RuntimeDeviceFailureState {
    override val family: DeviceFamily = properties.family

    private var endpoint: K16EndpointWorker? = null
    private val displaySessions = DisplaySessionTracker()
    private val renderers = mutableMapOf<Int, SerialTextDisplayRenderer>()
    private val displaySnapshotRefreshDisplayIds = mutableSetOf<Int>()
    private var labelBacking: String? = properties.label
    private var renderedSerialBytes = 0
    private var terminalControlReached = false
    private var runtimeFailureMessageBacking: String? = null

    override var label: String?
        get() = labelBacking
        set(value) {
            labelBacking = value
        }

    override val isOn: Boolean
        get() = endpoint != null

    override val runtimeFailureMessage: String?
        get() = runtimeFailureMessageBacking

    override fun turnOn() {
        if (endpoint != null) return
        val worker =
            try {
                K16EndpointWorker(deviceId, endpointFactory).also { it.start() }
            } catch (error: Throwable) {
                runtimeFailureMessageBacking = error.message ?: error::class.java.name
                LOGGER.error(error) {
                    "K16RuntimeDevice $deviceId failed to start: $runtimeFailureMessageBacking"
                }
                stateSink.onPowerStateChanged(false)
                return
            }
        endpoint = worker
        runtimeFailureMessageBacking = null
        terminalControlReached = false
        stateSink.onPowerStateChanged(true)
    }

    override fun shutdown() {
        val current = endpoint ?: return
        endpoint = null
        renderedSerialBytes = 0
        terminalControlReached = false
        renderers.clear()
        displaySnapshotRefreshDisplayIds.clear()
        current.close()
        stateSink.onPowerStateChanged(false)
    }

    override fun reboot() {
        shutdown()
        turnOn()
    }

    override fun serverTick() {
        val current = endpoint ?: return
        current.requestTick()
        terminalControlReached = current.terminalControlReached
        if (!flushFramebufferFrames(current) && !flushK16DisplaySnapshot(current)) {
            flushSerialOutput(current)
        }
    }

    override fun close() =
        shutdown()

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        when (event) {
            "turn_on" -> turnOn()
            "shutdown", "terminate" -> shutdown()
            "reboot" -> reboot()
            "char" -> pushSerialInput(argumentBytes(arguments.firstOrNull()) ?: return)
            "paste" -> pushSerialInput(argumentBytes(arguments.firstOrNull()) ?: return)
            "key" -> pushSerialInput(keySerialBytes(arguments.firstOrNull()) ?: return)
        }
    }

    override fun pushSerialInput(bytes: ByteArray) {
        endpoint?.pushInput(bytes)
    }

    override fun serialOutputSnapshot(): ByteArray =
        endpoint?.outputSnapshot() ?: ByteArray(0)

    override fun clearSerialOutput() {
        endpoint?.clearOutput()
    }

    override fun snapshotRuntimeState(): ByteArray? =
        endpoint?.machineSnapshot()

    override fun attachDisplaySession(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        displaySessions.attach(playerUuid, containerId, displayId, width, height)
        displaySnapshotRefreshDisplayIds += displayId
    }

    override fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        displaySessions.resize(playerUuid, displayId, width, height)
        renderers.remove(displayId)
        displaySnapshotRefreshDisplayIds += displayId
    }

    override fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    ) {
        val detachedDisplayId = displaySessions.detach(playerUuid, displayId) ?: return
        renderers.remove(detachedDisplayId)
        displaySnapshotRefreshDisplayIds.remove(detachedDisplayId)
    }

    private fun argumentBytes(value: Any?): ByteArray? =
        when (value) {
            is ByteArray -> value.copyOf()
            is ByteBuffer -> {
                val duplicate = value.asReadOnlyBuffer()
                ByteArray(duplicate.remaining()).also(duplicate::get)
            }
            is String -> value.encodeToByteArray()
            else -> null
        }

    private fun keySerialBytes(value: Any?): ByteArray? =
        when (value as? Int) {
            KeyCodes.KEY_ENTER, KeyCodes.KEY_KP_ENTER -> byteArrayOf('\n'.code.toByte())
            KeyCodes.KEY_BACKSPACE -> byteArrayOf(0x08)
            else -> null
        }

    private fun flushSerialOutput(current: K16EndpointWorker) {
        if (displaySessions.isEmpty()) return
        val output = current.outputSnapshot()
        if (output.size <= renderedSerialBytes) return
        val newBytes = output.copyOfRange(renderedSerialBytes, output.size)
        renderedSerialBytes = output.size
        for (endpoint in displaySessions.activeEndpoints()) {
            val renderer =
                renderers.getOrPut(endpoint.displayId) {
                    SerialTextDisplayRenderer(
                        columns = (endpoint.width / TerminalFontConstants.FONT_WIDTH).coerceAtLeast(1),
                        rows = (endpoint.height / TerminalFontConstants.FONT_HEIGHT).coerceAtLeast(1),
                    )
                }
            renderer.append(newBytes)
            val frame = renderer.renderFrame(endpoint.displayId, endpoint.width, endpoint.height)
            sendFrame(endpoint.displayId, frame)
        }
    }

    private fun flushK16DisplaySnapshot(current: K16EndpointWorker): Boolean {
        if (current.display0Snapshot() == null) return false
        if (displaySessions.isEmpty()) return true
        val refreshDisplayIds = displaySnapshotRefreshDisplayIds.toSet()
        val snapshot =
            if (refreshDisplayIds.isNotEmpty()) {
                current.display0Snapshot().also { current.pollDisplay0Snapshot() }
            } else {
                current.pollDisplay0Snapshot()
            } ?: return true
        for (endpoint in displaySessions.activeEndpoints()) {
            val renderer = SerialTextDisplayRenderer(snapshot.columns, snapshot.rows)
            renderer.replaceCells(snapshot.cells)
            val frame =
                renderer.renderFrame(
                    displayId = endpoint.displayId,
                    pixelWidth = endpoint.width,
                    pixelHeight = endpoint.height,
                    sequence = snapshot.sequence,
                )
            sendFrame(endpoint.displayId, frame)
        }
        displaySnapshotRefreshDisplayIds.removeAll(refreshDisplayIds)
        return true
    }

    private fun flushFramebufferFrames(current: K16EndpointWorker): Boolean {
        val frames = current.drainDisplayFrames()
        if (frames.isEmpty()) return false
        for (frame in frames) {
            sendFrame(frame.displayId, frame)
        }
        return true
    }

    private fun sendFrame(
        displayId: Int,
        frame: ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta,
    ) {
        val toDetach = mutableListOf<Pair<UUID, Int>>()
        for (session in displaySessions.sessionsSnapshot().filter { it.displayId == displayId }) {
            if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
                toDetach += session.playerUuid to session.displayId
                continue
            }
            displayNetwork.sendDisplayFrame(session.playerUuid, session.containerId, frame)
        }
        toDetach.forEach { (playerUuid, detachedDisplayId) -> detachDisplaySession(playerUuid, detachedDisplayId) }
    }

    private class K16EndpointWorker(
        deviceId: Int,
        private val endpointFactory: () -> K16ComputerEndpoint,
    ) : AutoCloseable {
        private val commands = LinkedBlockingQueue<Command>()
        private val startup = CompletableFuture<Unit>()
        private val closed = AtomicBoolean(false)
        private val tickRequested = AtomicBoolean(false)
        private val workerThread =
            Thread(::runWorker, "compukterkraft-k16-$deviceId").apply {
                isDaemon = true
            }
        @Volatile
        private var outputCache: ByteArray = ByteArray(0)
        @Volatile
        private var display0Cache: NativeK16ComputerDisplaySnapshot? = null
        private val displayFrameCache = ConcurrentLinkedQueue<DisplayFrameDelta>()
        @Volatile
        var terminalControlReached: Boolean = false
            private set
        private var lastPolledDisplay0Sequence: Long? = null

        fun start() {
            workerThread.start()
            try {
                startup.join()
            } catch (error: CompletionException) {
                throw error.cause ?: error
            }
        }

        fun requestTick() {
            if (!closed.get() && !terminalControlReached && tickRequested.compareAndSet(false, true)) {
                commands.offer(Command.Tick)
            }
        }

        fun pushInput(bytes: ByteArray) {
            if (!closed.get() && bytes.isNotEmpty()) {
                commands.offer(Command.PushInput(bytes.copyOf()))
            }
        }

        fun outputSnapshot(): ByteArray = outputCache.copyOf()

        fun display0Snapshot(): NativeK16ComputerDisplaySnapshot? = display0Cache

        fun drainDisplayFrames(): List<DisplayFrameDelta> =
            buildList {
                while (true) {
                    add(displayFrameCache.poll() ?: break)
                }
            }

        fun pollDisplay0Snapshot(): NativeK16ComputerDisplaySnapshot? {
            val snapshot = display0Cache ?: run {
                lastPolledDisplay0Sequence = null
                return null
            }
            if (lastPolledDisplay0Sequence == snapshot.sequence) {
                return null
            }
            lastPolledDisplay0Sequence = snapshot.sequence
            return snapshot
        }

        fun clearOutput() {
            outputCache = ByteArray(0)
            if (!closed.get()) {
                commands.offer(Command.ClearOutput)
            }
        }

        fun machineSnapshot(): ByteArray {
            check(!closed.get()) { "K16 endpoint worker is closed" }
            val response = CompletableFuture<ByteArray>()
            commands.offer(Command.MachineSnapshot(response))
            return response.join()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                commands.offer(Command.Close)
                workerThread.join()
            }
        }

        private fun runWorker() {
            var endpoint: K16ComputerEndpoint? = null
            try {
                endpoint = endpointFactory()
                refreshCaches(endpoint)
                startup.complete(Unit)
            } catch (error: Throwable) {
                startup.completeExceptionally(error)
                endpoint?.close()
                return
            }
            try {
                while (true) {
                    when (val command = commands.take()) {
                        Command.Tick -> {
                            tickRequested.set(false)
                            if (!terminalControlReached) {
                                val control = endpoint.tick()
                                terminalControlReached = control.isTerminal()
                                refreshCaches(endpoint)
                            }
                        }
                        is Command.PushInput -> endpoint.pushInput(command.bytes)
                        Command.ClearOutput -> {
                            endpoint.clearOutput()
                            refreshCaches(endpoint)
                        }
                        is Command.MachineSnapshot -> command.response.complete(endpoint.machineSnapshot())
                        Command.Close -> break
                    }
                }
            } finally {
                endpoint.close()
            }
        }

        private fun refreshCaches(endpoint: K16ComputerEndpoint) {
            outputCache = endpoint.outputSnapshot()
            display0Cache = endpoint.display0Snapshot()
            val frameBytes = endpoint.drainFramebuffer0Frames()
            if (frameBytes.isNotEmpty()) {
                displayFrameCache.addAll(NativeDisplayFrameCodec.decodeFrames(frameBytes))
            }
        }

        private sealed interface Command {
            data object Tick : Command
            data class PushInput(val bytes: ByteArray) : Command
            data object ClearOutput : Command
            data class MachineSnapshot(val response: CompletableFuture<ByteArray>) : Command
            data object Close : Command
        }
    }

    companion object {
        const val STATUS_RESET: Int = NativeK16ComputerControl.STATUS_RESET
        const val STATUS_BOOTING: Int = NativeK16ComputerControl.STATUS_BOOTING
        const val STATUS_READY: Int = NativeK16ComputerControl.STATUS_READY
        const val STATUS_HALTED: Int = NativeK16ComputerControl.STATUS_HALTED
        const val STATUS_PANIC: Int = NativeK16ComputerControl.STATUS_PANIC
    }
}
