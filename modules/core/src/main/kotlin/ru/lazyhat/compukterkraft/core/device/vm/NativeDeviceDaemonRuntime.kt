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

package ru.lazyhat.compukterkraft.core.device.vm

import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonBootSummary
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonHostRequest
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonTickSummary
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings

internal class NativeDeviceDaemonRuntime(
    private val daemonHandle: Long,
    private val profile: DeviceProfile,
    private val bindings: NativeDaemonBindings = NativeVmDaemonBindings,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    private val hostBridge: suspend (NativeDeviceDaemonHostRequest) -> ByteArray,
) {
    fun boot(
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary =
        bindings.bootDeviceDaemon(daemonHandle, image, programPath, argument, workingDirectory)

    fun enqueueEvent(event: VmEvent): Boolean =
        bindings.enqueueDeviceDaemonEvent(daemonHandle, event.name, event.arguments)

    suspend fun requestSlice(serverTick: Long): NativeDeviceDaemonTickSummary {
        val started = System.nanoTime()
        val summary =
            bindings.tickDeviceDaemon(
                daemonHandle = daemonHandle,
                instructions = profile.resources.cpu.instructionsPerSlice.toLong(),
                wallNanos = profile.resources.cpu.wallTimeGuardNanosPerSlice,
                serverTick = serverTick,
            )
        runtimeMetricsCollector.recordNativeDaemonTick(
            activeNanos = System.nanoTime() - started,
            turns = summary.turns,
            halted = summary.halted,
            hostRequests = summary.hostRequests,
            idle = summary.idle,
        )
        serviceHostRequests()
        return summary
    }

    private suspend fun serviceHostRequests() {
        for (request in bindings.drainDeviceDaemonHostRequests(daemonHandle)) {
            val result = hostBridge(request)
            bindings.completeDeviceDaemonHostRequest(daemonHandle, request.requestId, result)
        }
    }
}

interface NativeDaemonBindings {
    fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        instructionBudget: Int,
    ): Long

    fun freeDeviceDaemon(daemonHandle: Long)

    fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary

    fun tickDeviceDaemon(
        daemonHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): NativeDeviceDaemonTickSummary

    fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest>

    fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean
}

object NativeVmDaemonBindings : NativeDaemonBindings {
    override fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        instructionBudget: Int,
    ): Long =
        NativeVmBindings.createDeviceDaemon(maxEventQueueSize, maxBufferedBytesPerChannel, instructionBudget)

    override fun freeDeviceDaemon(daemonHandle: Long) = NativeVmBindings.freeDeviceDaemon(daemonHandle)

    override fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary =
        NativeVmBindings.bootDeviceDaemon(daemonHandle, image, programPath, argument, workingDirectory)

    override fun tickDeviceDaemon(
        daemonHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): NativeDeviceDaemonTickSummary =
        NativeVmBindings.tickDeviceDaemon(daemonHandle, instructions, wallNanos, serverTick)

    override fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest> =
        NativeVmBindings.drainDeviceDaemonHostRequests(daemonHandle)

    override fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean = NativeVmBindings.completeDeviceDaemonHostRequest(daemonHandle, requestId, value)

    override fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean = NativeVmBindings.enqueueDeviceDaemonEvent(daemonHandle, eventName, arguments)
}
