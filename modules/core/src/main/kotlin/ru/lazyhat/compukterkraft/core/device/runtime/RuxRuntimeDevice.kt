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

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
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
) : RuntimeDevice,
    RuntimeDeviceSerialEndpoint {
    override val family: DeviceFamily = properties.family

    private var endpoint: RuxComputerEndpoint? = null
    private var labelBacking: String? = properties.label

    override var label: String?
        get() = labelBacking
        set(value) {
            labelBacking = value
        }

    override val isOn: Boolean
        get() = endpoint != null

    override fun turnOn() {
        if (endpoint != null) return
        endpoint = endpointFactory()
        stateSink.onPowerStateChanged(true)
    }

    override fun shutdown() {
        val current = endpoint ?: return
        endpoint = null
        current.close()
        stateSink.onPowerStateChanged(false)
    }

    override fun reboot() {
        shutdown()
        turnOn()
    }

    override fun serverTick() {
        endpoint?.tick()
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

    override fun attachDisplaySession(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    ) = Unit

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

    companion object {
        const val STATUS_RESET: Int = 0
        const val STATUS_BOOTING: Int = 1
        const val STATUS_READY: Int = 2
        const val STATUS_HALTED: Int = 3
        const val STATUS_PANIC: Int = 4
    }
}
