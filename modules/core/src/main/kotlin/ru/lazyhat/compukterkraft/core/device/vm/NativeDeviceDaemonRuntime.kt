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
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonBootSummary
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonHostRequest
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonTickSummary
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

internal class NativeDeviceDaemonRuntime(
    private val daemonHandle: Long,
    private val profile: DeviceProfile,
    private val bindings: NativeDaemonBindings = NativeVmDaemonBindings,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    private val hostBridge: suspend (NativeDeviceDaemonHostRequest) -> ByteArray,
    private val compileBridge: suspend (NativeDeviceDaemonHostRequest) -> NativeDaemonCompileResult = {
        NativeDaemonCompileResult(image = null, exitCode = 1)
    },
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

    fun attachDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat,
    ): DisplayInfo {
        require(pixelFormat == DisplayPixelFormat.RGB565) { "Native daemon display supports RGB565 only" }
        bindings.attachDeviceDaemonDisplay(daemonHandle, displayId, width, height)
        return DisplayInfo(displayId, width, height, pixelFormat)
    }

    fun detachDisplay(displayId: Int) {
        bindings.detachDeviceDaemonDisplay(daemonHandle, displayId)
    }

    fun drainDisplayFrameBytes(): ByteArray = bindings.drainDeviceDaemonDisplayFrames(daemonHandle)

    fun drainDisplayFrames(): List<DisplayFrameDelta> =
        NativeDisplayFrameCodec.decodeFrames(drainDisplayFrameBytes())

    fun displayWakeSequence(): Long = bindings.deviceDaemonDisplayWakeSequence(daemonHandle)

    fun waitForDisplayWake(
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long = bindings.waitForDeviceDaemonDisplayWake(daemonHandle, observedWakeSequence, timeoutMillis)

    fun refillQuota(serverTick: Long) {
        val wallNanos = profile.resources.cpu.wallTimeGuardNanosPerSlice
        bindings.refillDeviceDaemonQuota(
            daemonHandle = daemonHandle,
            wallNanos = wallNanos,
            serverTick = serverTick,
        )
        runtimeMetricsCollector.recordNativeExecutionQuotaRefill(
            wallNanos = wallNanos,
            serverTick = serverTick,
        )
    }

    suspend fun runReadyUntilBlocked(): NativeDeviceDaemonTickSummary {
        val started = System.nanoTime()
        val summary =
            bindings.runDeviceDaemonReady(
                daemonHandle = daemonHandle,
                maxTurns = MAX_SCHEDULER_TURNS_PER_RUN,
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
            when (request.kind) {
                "hostCall" -> {
                    val result = hostBridge(request)
                    bindings.completeDeviceDaemonHostRequest(daemonHandle, request.requestId, result)
                }
                "compileProgram" -> {
                    val result = compileBridge(request)
                    bindings.completeDeviceDaemonCompileProgram(
                        daemonHandle,
                        request.requestId,
                        result.image,
                        result.exitCode,
                    )
                }
            }
        }
    }
}

internal data class NativeDaemonCompileResult(
    val image: ByteArray?,
    val exitCode: Int,
)

private const val MAX_SCHEDULER_TURNS_PER_RUN = 128L

interface NativeDaemonBindings {
    fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        imageSliceBudgetNanos: Long,
        memoryQuotaBytes: Long,
        deviceId: Int,
        profileName: String,
    ): Long

    fun freeDeviceDaemon(daemonHandle: Long)

    fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary

    fun refillDeviceDaemonQuota(
        daemonHandle: Long,
        wallNanos: Long,
        serverTick: Long,
    )

    fun runDeviceDaemonReady(
        daemonHandle: Long,
        maxTurns: Long,
    ): NativeDeviceDaemonTickSummary

    fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest>

    fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean

    fun completeDeviceDaemonCompileProgram(
        daemonHandle: Long,
        requestId: Long,
        image: ByteArray?,
        exitCode: Int,
    ): Boolean

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean

    fun attachDeviceDaemonFilesystem(
        daemonHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    )

    fun attachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun detachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
    )

    fun drainDeviceDaemonDisplayFrames(daemonHandle: Long): ByteArray

    fun deviceDaemonDisplayWakeSequence(daemonHandle: Long): Long

    fun waitForDeviceDaemonDisplayWake(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long
}

object NativeVmDaemonBindings : NativeDaemonBindings {
    override fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        imageSliceBudgetNanos: Long,
        memoryQuotaBytes: Long,
        deviceId: Int,
        profileName: String,
    ): Long =
        NativeVmBindings.createDeviceDaemon(
            maxEventQueueSize,
            maxBufferedBytesPerChannel,
            imageSliceBudgetNanos,
            memoryQuotaBytes,
            deviceId,
            profileName,
        )

    override fun freeDeviceDaemon(daemonHandle: Long) = NativeVmBindings.freeDeviceDaemon(daemonHandle)

    override fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary =
        NativeVmBindings.bootDeviceDaemon(daemonHandle, image, programPath, argument, workingDirectory)

    override fun refillDeviceDaemonQuota(
        daemonHandle: Long,
        wallNanos: Long,
        serverTick: Long,
    ) {
        NativeVmBindings.refillDeviceDaemonQuota(daemonHandle, wallNanos, serverTick)
    }

    override fun runDeviceDaemonReady(
        daemonHandle: Long,
        maxTurns: Long,
    ): NativeDeviceDaemonTickSummary =
        NativeVmBindings.runDeviceDaemonReady(daemonHandle, maxTurns)

    override fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest> =
        NativeVmBindings.drainDeviceDaemonHostRequests(daemonHandle)

    override fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean = NativeVmBindings.completeDeviceDaemonHostRequest(daemonHandle, requestId, value)

    override fun completeDeviceDaemonCompileProgram(
        daemonHandle: Long,
        requestId: Long,
        image: ByteArray?,
        exitCode: Int,
    ): Boolean = NativeVmBindings.completeDeviceDaemonCompileProgram(daemonHandle, requestId, image, exitCode)

    override fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean = NativeVmBindings.enqueueDeviceDaemonEvent(daemonHandle, eventName, arguments)

    override fun attachDeviceDaemonFilesystem(
        daemonHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    ) {
        NativeVmBindings.attachDeviceDaemonFilesystem(daemonHandle, rootPath, quotaBytes)
    }

    override fun attachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        NativeVmBindings.attachDeviceDaemonDisplay(daemonHandle, displayId, width, height)
    }

    override fun detachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
    ) {
        NativeVmBindings.detachDeviceDaemonDisplay(daemonHandle, displayId)
    }

    override fun drainDeviceDaemonDisplayFrames(daemonHandle: Long): ByteArray =
        NativeVmBindings.drainDeviceDaemonDisplayFrames(daemonHandle)

    override fun deviceDaemonDisplayWakeSequence(daemonHandle: Long): Long =
        NativeVmBindings.deviceDaemonDisplayWakeSequence(daemonHandle)

    override fun waitForDeviceDaemonDisplayWake(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long =
        NativeVmBindings.waitForDeviceDaemonDisplayWake(daemonHandle, observedWakeSequence, timeoutMillis)
}
