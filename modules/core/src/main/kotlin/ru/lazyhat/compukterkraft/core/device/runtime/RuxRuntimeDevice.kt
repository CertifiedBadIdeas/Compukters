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
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerEndpoint
import java.nio.ByteBuffer
import java.util.UUID

interface RuntimeDeviceSerialEndpoint {
    fun pushSerialInput(bytes: ByteArray)

    fun serialOutputSnapshot(): ByteArray

    fun clearSerialOutput()
}

class RuxRuntimeDevice(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val endpointFactory: () -> RuxComputerEndpoint,
    private val stateSink: DeviceStateSink,
    private val displayNetwork: DisplayNetworkBridge = NoopDisplayNetworkBridge,
) : RuntimeDevice,
    RuntimeDeviceSerialEndpoint,
    RuntimeDeviceSnapshotPersistence,
    RuntimeDeviceFailureState {
    override val family: DeviceFamily = properties.family

    private var endpoint: RuxComputerEndpoint? = null
    private val displaySessions = DisplaySessionTracker()
    private val renderers = mutableMapOf<Int, SerialTextDisplayRenderer>()
    private val displaySnapshotRefreshDisplayIds = mutableSetOf<Int>()
    private var labelBacking: String? = properties.label
    private var renderedSerialBytes = 0
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
        endpoint =
            try {
                endpointFactory()
            } catch (error: Throwable) {
                runtimeFailureMessageBacking = error.message ?: error::class.java.name
                LOGGER.error(error) {
                    "RuxRuntimeDevice $deviceId failed to start: $runtimeFailureMessageBacking"
                }
                stateSink.onPowerStateChanged(false)
                return
            }
        runtimeFailureMessageBacking = null
        stateSink.onPowerStateChanged(true)
    }

    override fun shutdown() {
        val current = endpoint ?: return
        endpoint = null
        renderedSerialBytes = 0
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
        current.tick()
        if (!flushRuxDisplaySnapshot(current)) {
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

    private fun flushSerialOutput(current: RuxComputerEndpoint) {
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

    private fun flushRuxDisplaySnapshot(current: RuxComputerEndpoint): Boolean {
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

    companion object {
        const val STATUS_RESET: Int = 0
        const val STATUS_BOOTING: Int = 1
        const val STATUS_READY: Int = 2
        const val STATUS_HALTED: Int = 3
        const val STATUS_PANIC: Int = 4
    }
}
