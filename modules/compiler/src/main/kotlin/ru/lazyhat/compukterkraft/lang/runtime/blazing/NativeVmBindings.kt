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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.runtime.VmValue

data class NativeProcessSchedulerTick(
    val currentTick: Long,
    val selectedPid: Int?,
    val wokenPids: List<Int>,
)

data class NativeDeviceExecutionQuota(
    val instructions: Long,
    val wallNanos: Long,
    val serverTick: Long,
)

data class NativeDeviceSchedulerDryRun(
    val serverTick: Long,
    val turns: Long,
    val remainingInstructions: Long,
    val selectedPids: List<Int>,
)

data class NativeDeviceSchedulerStep(
    val serverTick: Long,
    val selectedPid: Int?,
    val selectedImageHandle: Long?,
    val remainingInstructions: Long,
    val quotaExhausted: Boolean,
    val wokenPids: List<Int>,
)

data class NativeDeviceDaemonTickSummary(
    val serverTick: Long,
    val turns: Long,
    val remainingInstructions: Long,
    val idle: Boolean,
    val halted: Long,
    val hostRequests: Long,
)

data class NativeDeviceDaemonBootSummary(
    val pid: Int,
    val imageAttached: Boolean,
)

data class NativeDeviceDaemonHostRequest(
    val requestId: Long,
    val pid: Int,
    val kind: String,
    val moduleName: String?,
    val functionName: String?,
    val arguments: List<VmValue>,
    val path: String?,
    val workingDirectory: String?,
)

internal interface NativeVmBindingsFacade {
    fun createImage(
        libraryPath: String,
        image: ByteArray,
        instructionBudget: Int,
    ): Long

    fun runImageUntilSignal(handle: Long): ByteArray

    fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    )

    fun freeImage(handle: Long)

    fun attachImageToKernel(
        imageHandle: Long,
        kernelHandle: Long,
    )

    fun attachProcessImage(
        kernelHandle: Long,
        pid: Int,
        imageHandle: Long,
    ): Boolean = false

    fun setImageWorkingDirectory(
        imageHandle: Long,
        workingDirectory: String,
    )

    fun waitForDeviceWake(
        handle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    fun waitForProcessWake(
        handle: Long,
        pid: Int,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    fun registerProcess(
        kernelHandle: Long,
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean

    fun completeProcess(
        kernelHandle: Long,
        pid: Int,
        exitCode: Int,
    ): Boolean
}

object NativeVmBindings : NativeVmBindingsFacade {
    private val lock = Any()
    private var loadedPath: String? = null

    override fun createImage(
        libraryPath: String,
        image: ByteArray,
        instructionBudget: Int,
    ): Long {
        load(libraryPath)
        val handle = createImageNative(image, instructionBudget.coerceAtLeast(1))
        check(handle != 0L) { "Native image VM create returned a zero handle" }
        return handle
    }

    override fun runImageUntilSignal(handle: Long): ByteArray {
        require(handle != 0L) { "Native image VM handle is zero" }
        return runImageUntilSignalForHandleNative(handle)
    }

    override fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    ) {
        require(handle != 0L) { "Native image VM handle is zero" }
        resumeImageWithNative(handle, value)
    }

    override fun freeImage(handle: Long) {
        if (handle != 0L) {
            freeImageNative(handle)
        }
    }

    fun createDeviceKernel(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
    ): Long {
        load(requireConfiguredLibraryPath())
        val handle = createDeviceKernelNative(maxEventQueueSize.coerceAtLeast(1), maxBufferedBytesPerChannel.coerceAtLeast(1))
        check(handle != 0L) { "Native device runtime kernel create returned a zero handle" }
        return handle
    }

    fun freeDeviceKernel(handle: Long) {
        if (handle != 0L) {
            freeDeviceKernelNative(handle)
        }
    }

    fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        instructionBudget: Int,
    ): Long {
        load(requireConfiguredLibraryPath())
        val handle =
            createDeviceDaemonNative(
                maxEventQueueSize.coerceAtLeast(1),
                maxBufferedBytesPerChannel.coerceAtLeast(1),
                instructionBudget.coerceAtLeast(1),
            )
        check(handle != 0L) { "Native device daemon create returned a zero handle" }
        return handle
    }

    fun freeDeviceDaemon(handle: Long) {
        if (handle != 0L) {
            freeDeviceDaemonNative(handle)
        }
    }

    fun tickDeviceDaemon(
        daemonHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): NativeDeviceDaemonTickSummary {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return tickDeviceDaemonNative(
            daemonHandle,
            instructions,
            wallNanos,
            serverTick,
        ).toNativeDeviceDaemonTickSummary()
    }

    fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return bootDeviceDaemonNative(
            daemonHandle,
            image,
            programPath,
            argument,
            workingDirectory,
        ).toNativeDeviceDaemonBootSummary()
    }

    fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest> {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return drainDeviceDaemonHostRequestsNative(daemonHandle).toNativeDeviceDaemonHostRequests()
    }

    fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return completeDeviceDaemonHostRequestNative(daemonHandle, requestId, value)
    }

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean = enqueueDeviceDaemonEvent(daemonHandle, eventName, nativeEventPayload(arguments))

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return enqueueDeviceDaemonEventNative(daemonHandle, eventName, payload)
    }

    fun attachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        attachDeviceDaemonDisplayNative(daemonHandle, displayId, width, height)
    }

    fun detachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        detachDeviceDaemonDisplayNative(daemonHandle, displayId)
    }

    fun drainDeviceDaemonDisplayFrames(daemonHandle: Long): ByteArray {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return drainDeviceDaemonDisplayFramesNative(daemonHandle)
    }

    fun deviceDaemonDisplayWakeSequence(daemonHandle: Long): Long {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return deviceDaemonDisplayWakeSequenceNative(daemonHandle)
    }

    fun waitForDeviceDaemonDisplayWake(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return waitForDeviceDaemonDisplayWakeNative(daemonHandle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
    }

    fun enqueueDeviceEvent(
        handle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean = enqueueDeviceEvent(handle, eventName, nativeEventPayload(arguments))

    fun enqueueDeviceEvent(
        handle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return enqueueDeviceEventNative(handle, eventName, payload)
    }

    fun writeDeviceIpc(
        handle: Long,
        channel: Int,
        text: String,
    ): Boolean {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return writeDeviceIpcNative(handle, channel, text)
    }

    fun tryReadDeviceIpc(
        handle: Long,
        channel: Int,
    ): String? {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return tryReadDeviceIpcNative(handle, channel)
    }

    fun deviceKernelWakeSequence(handle: Long): Long {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return deviceKernelWakeSequenceNative(handle)
    }

    override fun waitForDeviceWake(
        handle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return waitForDeviceWakeNative(handle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
    }

    override fun waitForProcessWake(
        handle: Long,
        pid: Int,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return waitForProcessWakeNative(handle, pid, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
    }

    override fun registerProcess(
        kernelHandle: Long,
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return registerProcessNative(kernelHandle, pid, parentPid, programPath)
    }

    override fun completeProcess(
        kernelHandle: Long,
        pid: Int,
        exitCode: Int,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return completeProcessNative(kernelHandle, pid, exitCode)
    }

    fun markProcessRunnable(
        kernelHandle: Long,
        pid: Int,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessRunnableNative(kernelHandle, pid)
    }

    fun markProcessWaitingForProcess(
        kernelHandle: Long,
        pid: Int,
        targetPid: Int,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessWaitingForProcessNative(kernelHandle, pid, targetPid)
    }

    fun markProcessWaitingForEvent(
        kernelHandle: Long,
        pid: Int,
        filter: String?,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessWaitingForEventNative(kernelHandle, pid, filter)
    }

    fun markProcessWaitingForIpc(
        kernelHandle: Long,
        pid: Int,
        channelId: Int,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessWaitingForIpcNative(kernelHandle, pid, channelId)
    }

    fun markProcessSleeping(
        kernelHandle: Long,
        pid: Int,
        untilTick: Long,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessSleepingNative(kernelHandle, pid, untilTick)
    }

    fun markProcessCrashed(
        kernelHandle: Long,
        pid: Int,
        message: String,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return markProcessCrashedNative(kernelHandle, pid, message)
    }

    fun processSchedulerTick(
        kernelHandle: Long,
        currentTick: Long,
    ): NativeProcessSchedulerTick {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return processSchedulerTickNative(kernelHandle, currentTick).toNativeProcessSchedulerTick()
    }

    fun addDeviceExecutionQuota(
        kernelHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): NativeDeviceExecutionQuota {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return addDeviceExecutionQuotaNative(
            kernelHandle,
            instructions,
            wallNanos,
            serverTick,
        ).toNativeDeviceExecutionQuota()
    }

    fun runDeviceSchedulerDryRun(
        kernelHandle: Long,
        maxTurns: Int,
    ): NativeDeviceSchedulerDryRun {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return runDeviceSchedulerDryRunNative(
            kernelHandle,
            maxTurns.coerceAtLeast(0),
        ).toNativeDeviceSchedulerDryRun()
    }

    fun runDeviceSchedulerStep(kernelHandle: Long): NativeDeviceSchedulerStep {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return runDeviceSchedulerStepNative(kernelHandle).toNativeDeviceSchedulerStep()
    }

    override fun attachProcessImage(
        kernelHandle: Long,
        pid: Int,
        imageHandle: Long,
    ): Boolean {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return attachProcessImageNative(kernelHandle, pid, imageHandle)
    }

    override fun attachImageToKernel(
        imageHandle: Long,
        kernelHandle: Long,
    ) {
        require(imageHandle != 0L) { "Native image VM handle is zero" }
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        attachImageToKernelNative(imageHandle, kernelHandle)
    }

    override fun setImageWorkingDirectory(
        imageHandle: Long,
        workingDirectory: String,
    ) {
        require(imageHandle != 0L) { "Native image VM handle is zero" }
        setImageWorkingDirectoryNative(imageHandle, workingDirectory)
    }

    fun attachNativeFilesystem(
        kernelHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    ) {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        attachNativeFilesystemNative(kernelHandle, rootPath, quotaBytes)
    }

    fun attachNativeDisplay(
        kernelHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        attachNativeDisplayNative(kernelHandle, displayId, width, height)
    }

    fun detachNativeDisplay(
        kernelHandle: Long,
        displayId: Int,
    ) {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        detachNativeDisplayNative(kernelHandle, displayId)
    }

    fun nativeDisplayFillRect(
        kernelHandle: Long,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        nativeDisplayFillRectNative(kernelHandle, displayId, x, y, width, height, rgb565)
    }

    fun nativeDisplayPresent(
        kernelHandle: Long,
        displayId: Int,
    ) {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        nativeDisplayPresentNative(kernelHandle, displayId)
    }

    fun drainNativeDisplayFrames(kernelHandle: Long): ByteArray {
        require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
        return drainNativeDisplayFramesNative(kernelHandle)
    }

    fun displayWakeSequence(handle: Long): Long {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return displayWakeSequenceNative(handle)
    }

    fun waitForDisplayWake(
        handle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long {
        require(handle != 0L) { "Native device runtime kernel handle is zero" }
        return waitForDisplayWakeNative(handle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
    }

    private fun load(libraryPath: String) {
        synchronized(lock) {
            val current = loadedPath
            if (current == libraryPath) {
                return
            }
            require(current == null) {
                "Native VM library already loaded from $current; cannot load $libraryPath in the same JVM"
            }
            System.load(libraryPath)
            loadedPath = libraryPath
        }
    }

    private fun requireConfiguredLibraryPath(): String =
        System.getProperty("ckl.vm.native.library")
            ?.takeIf { it.isNotBlank() }
            ?: error("Rust image VM runner requires -Dckl.vm.native.library=/absolute/path/to/libckl_vm.so")

    private fun nativeEventPayload(arguments: List<Any?>): ByteArray =
        VmValue
            .RecordValue(
                typeName = "EventPayload",
                fields = arguments.toNativeEventFields(),
            ).toNativeBytes("events", "enqueue")

    private fun List<Any?>.toNativeEventFields(): LinkedHashMap<String, VmValue> {
        val fields = LinkedHashMap<String, VmValue>()
        forEachIndexed { index, value ->
            fields["arg$index"] = value.toNativeEventValue()
        }
        return fields
    }

    private fun Any?.toNativeEventValue(): VmValue =
        when (this) {
            null -> VmValue.NullValue
            is Int -> VmValue.IntValue(this)
            is Boolean -> VmValue.BoolValue(this)
            is String -> VmValue.StringValue(this)
            is ByteArray -> VmValue.StringValue(decodeToString())
            else -> VmValue.StringValue(toString())
        }

    private fun LongArray.toNativeProcessSchedulerTick(): NativeProcessSchedulerTick {
        val currentTick = getOrElse(0) { 0L }
        val selectedPid =
            getOrElse(1) { 0L }
                .toInt()
                .takeIf { it > 0 }
        val wokenCount =
            getOrElse(2) { 0L }
                .toInt()
                .coerceAtLeast(0)
        val wokenPids =
            drop(3)
                .take(wokenCount)
                .map { it.toInt() }
        return NativeProcessSchedulerTick(
            currentTick = currentTick,
            selectedPid = selectedPid,
            wokenPids = wokenPids,
        )
    }

    private fun LongArray.toNativeDeviceExecutionQuota(): NativeDeviceExecutionQuota =
        NativeDeviceExecutionQuota(
            instructions = getOrElse(0) { 0L },
            wallNanos = getOrElse(1) { 0L },
            serverTick = getOrElse(2) { 0L },
        )

    private fun LongArray.toNativeDeviceSchedulerDryRun(): NativeDeviceSchedulerDryRun {
        val selectedCount =
            getOrElse(3) { 0L }
                .toInt()
                .coerceAtLeast(0)
        return NativeDeviceSchedulerDryRun(
            serverTick = getOrElse(0) { 0L },
            turns = getOrElse(1) { 0L },
            remainingInstructions = getOrElse(2) { 0L },
            selectedPids =
                drop(4)
                    .take(selectedCount)
                    .map { it.toInt() },
        )
    }

    private fun LongArray.toNativeDeviceSchedulerStep(): NativeDeviceSchedulerStep {
        val selectedPid =
            getOrElse(1) { 0L }
                .toInt()
                .takeIf { it > 0 }
        val selectedImageHandle =
            getOrElse(2) { 0L }
                .takeIf { it > 0L }
        val wokenCount =
            getOrElse(5) { 0L }
                .toInt()
                .coerceAtLeast(0)
        return NativeDeviceSchedulerStep(
            serverTick = getOrElse(0) { 0L },
            selectedPid = selectedPid,
            selectedImageHandle = selectedImageHandle,
            remainingInstructions = getOrElse(3) { 0L },
            quotaExhausted = getOrElse(4) { 0L } != 0L,
            wokenPids =
                drop(6)
                    .take(wokenCount)
                    .map { it.toInt() },
        )
    }

    private fun LongArray.toNativeDeviceDaemonTickSummary(): NativeDeviceDaemonTickSummary =
        NativeDeviceDaemonTickSummary(
            serverTick = getOrElse(0) { 0L },
            turns = getOrElse(1) { 0L },
            remainingInstructions = getOrElse(2) { 0L },
            idle = getOrElse(3) { 0L } != 0L,
            halted = getOrElse(4) { 0L },
            hostRequests = getOrElse(5) { 0L },
        )

    private fun LongArray.toNativeDeviceDaemonBootSummary(): NativeDeviceDaemonBootSummary =
        NativeDeviceDaemonBootSummary(
            pid =
                getOrElse(0) { 0L }
                    .toInt(),
            imageAttached = getOrElse(1) { 0L } != 0L,
        )

    private fun ByteArray.toNativeDeviceDaemonHostRequests(): List<NativeDeviceDaemonHostRequest> {
        val reader = NativeDeviceDaemonHostRequestReader(this)
        return List(reader.i32()) {
            val requestId = reader.i64()
            val pid = reader.i32()
            val kind =
                when (reader.u8()) {
                    0 -> "hostCall"
                    1 -> "compileProgram"
                    2 -> "crash"
                    else -> "unknown"
                }
            val moduleName = reader.string().takeIf { it.isNotEmpty() }
            val functionName = reader.string().takeIf { it.isNotEmpty() }
            val arguments =
                List(reader.i32()) {
                    reader.value().toVmValue(moduleName.orEmpty(), functionName.orEmpty())
                }
            val path = reader.string().takeIf { it.isNotEmpty() }
            val workingDirectory = reader.string().takeIf { it.isNotEmpty() }
            NativeDeviceDaemonHostRequest(
                requestId = requestId,
                pid = pid,
                kind = kind,
                moduleName = moduleName,
                functionName = functionName,
                arguments = arguments,
                path = path,
                workingDirectory = workingDirectory,
            )
        }
    }

    @JvmStatic
    private external fun createImageNative(
        image: ByteArray,
        instructionBudget: Int,
    ): Long

    @JvmStatic
    private external fun runImageUntilSignalForHandleNative(handle: Long): ByteArray

    @JvmStatic
    private external fun resumeImageWithNative(
        handle: Long,
        value: ByteArray,
    )

    @JvmStatic
    private external fun freeImageNative(handle: Long)

    @JvmStatic
    private external fun createDeviceKernelNative(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
    ): Long

    @JvmStatic
    private external fun freeDeviceKernelNative(handle: Long)

    @JvmStatic
    private external fun createDeviceDaemonNative(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        instructionBudget: Int,
    ): Long

    @JvmStatic
    private external fun freeDeviceDaemonNative(handle: Long)

    @JvmStatic
    private external fun tickDeviceDaemonNative(
        daemonHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): LongArray

    @JvmStatic
    private external fun bootDeviceDaemonNative(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): LongArray

    @JvmStatic
    private external fun drainDeviceDaemonHostRequestsNative(daemonHandle: Long): ByteArray

    @JvmStatic
    private external fun completeDeviceDaemonHostRequestNative(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun enqueueDeviceDaemonEventNative(
        daemonHandle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun attachDeviceDaemonDisplayNative(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    )

    @JvmStatic
    private external fun detachDeviceDaemonDisplayNative(
        daemonHandle: Long,
        displayId: Int,
    )

    @JvmStatic
    private external fun drainDeviceDaemonDisplayFramesNative(daemonHandle: Long): ByteArray

    @JvmStatic
    private external fun deviceDaemonDisplayWakeSequenceNative(daemonHandle: Long): Long

    @JvmStatic
    private external fun waitForDeviceDaemonDisplayWakeNative(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    @JvmStatic
    private external fun enqueueDeviceEventNative(
        handle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun writeDeviceIpcNative(
        handle: Long,
        channel: Int,
        text: String,
    ): Boolean

    @JvmStatic
    private external fun tryReadDeviceIpcNative(
        handle: Long,
        channel: Int,
    ): String?

    @JvmStatic
    private external fun deviceKernelWakeSequenceNative(handle: Long): Long

    @JvmStatic
    private external fun waitForDeviceWakeNative(
        handle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    private external fun waitForProcessWakeNative(
        handle: Long,
        pid: Int,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    private external fun registerProcessNative(
        kernelHandle: Long,
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean

    private external fun attachProcessImageNative(
        kernelHandle: Long,
        pid: Int,
        imageHandle: Long,
    ): Boolean

    private external fun completeProcessNative(
        kernelHandle: Long,
        pid: Int,
        exitCode: Int,
    ): Boolean

    private external fun markProcessRunnableNative(
        kernelHandle: Long,
        pid: Int,
    ): Boolean

    private external fun markProcessWaitingForProcessNative(
        kernelHandle: Long,
        pid: Int,
        targetPid: Int,
    ): Boolean

    private external fun markProcessWaitingForEventNative(
        kernelHandle: Long,
        pid: Int,
        filter: String?,
    ): Boolean

    private external fun markProcessWaitingForIpcNative(
        kernelHandle: Long,
        pid: Int,
        channelId: Int,
    ): Boolean

    private external fun markProcessSleepingNative(
        kernelHandle: Long,
        pid: Int,
        untilTick: Long,
    ): Boolean

    private external fun markProcessCrashedNative(
        kernelHandle: Long,
        pid: Int,
        message: String,
    ): Boolean

    private external fun processSchedulerTickNative(
        kernelHandle: Long,
        currentTick: Long,
    ): LongArray

    private external fun addDeviceExecutionQuotaNative(
        kernelHandle: Long,
        instructions: Long,
        wallNanos: Long,
        serverTick: Long,
    ): LongArray

    private external fun runDeviceSchedulerDryRunNative(
        kernelHandle: Long,
        maxTurns: Int,
    ): LongArray

    private external fun runDeviceSchedulerStepNative(kernelHandle: Long): LongArray

    @JvmStatic
    private external fun displayWakeSequenceNative(handle: Long): Long

    @JvmStatic
    private external fun waitForDisplayWakeNative(
        handle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

    @JvmStatic
    private external fun attachImageToKernelNative(
        imageHandle: Long,
        kernelHandle: Long,
    )

    @JvmStatic
    private external fun setImageWorkingDirectoryNative(
        imageHandle: Long,
        workingDirectory: String,
    )

    @JvmStatic
    private external fun attachNativeFilesystemNative(
        kernelHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    )

    @JvmStatic
    private external fun attachNativeDisplayNative(
        kernelHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    )

    @JvmStatic
    private external fun detachNativeDisplayNative(
        kernelHandle: Long,
        displayId: Int,
    )

    @JvmStatic
    private external fun nativeDisplayFillRectNative(
        kernelHandle: Long,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    )

    @JvmStatic
    private external fun nativeDisplayPresentNative(
        kernelHandle: Long,
        displayId: Int,
    )

    @JvmStatic
    private external fun drainNativeDisplayFramesNative(kernelHandle: Long): ByteArray
}

private class NativeDeviceDaemonHostRequestReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun u8(): Int {
        require(offset < bytes.size) { "Unexpected end of native device daemon host request payload" }
        return bytes[offset++].toInt() and 0xff
    }

    fun i32(): Int {
        val b0 = u8()
        val b1 = u8()
        val b2 = u8()
        val b3 = u8()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun i64(): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((u8().toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    fun string(): String {
        val length = i32()
        require(length >= 0) { "Negative native device daemon string length $length" }
        require(offset + length <= bytes.size) { "Unexpected end of native device daemon string" }
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }

    fun value(): NativeVmValue =
        when (val tag = u8()) {
            0 -> NativeVmValue.UnitValue
            1 -> NativeVmValue.NullValue
            2 -> NativeVmValue.BoolValue(u8() != 0)
            3 -> NativeVmValue.IntValue(i32())
            4 -> NativeVmValue.LongValue(i64())
            5 -> NativeVmValue.StringValue(string())
            6 -> {
                val typeName = string()
                val fieldCount = i32()
                require(fieldCount >= 0) { "Negative native VM record field count $fieldCount" }
                NativeVmValue.RecordValue(
                    typeName = typeName,
                    fields =
                        LinkedHashMap<String, NativeVmValue>().apply {
                            repeat(fieldCount) {
                                this[string()] = value()
                            }
                        },
                )
            }
            else -> error("Unknown native VM value tag $tag")
        }
}
