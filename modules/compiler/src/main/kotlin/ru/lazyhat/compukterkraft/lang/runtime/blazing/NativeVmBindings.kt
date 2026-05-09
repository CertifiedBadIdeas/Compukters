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

    fun setImageWorkingDirectory(
        imageHandle: Long,
        workingDirectory: String,
    )
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
