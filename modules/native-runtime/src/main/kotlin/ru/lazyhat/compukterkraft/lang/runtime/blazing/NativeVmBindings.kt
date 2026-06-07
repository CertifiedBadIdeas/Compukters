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

import java.nio.file.Path

data class NativeK16ComputerControl(
    val status: Int,
    val exitCode: Int,
    val panicCode: Int,
) {
    fun isTerminal(): Boolean = status == STATUS_HALTED || status == STATUS_PANIC

    companion object {
        const val STATUS_RESET: Int = 0
        const val STATUS_BOOTING: Int = 1
        const val STATUS_READY: Int = 2
        const val STATUS_HALTED: Int = 3
        const val STATUS_PANIC: Int = 4

        fun from(values: LongArray): NativeK16ComputerControl =
            NativeK16ComputerControl(
                status = values.getOrElse(0) { 0L }.toInt(),
                exitCode = values.getOrElse(1) { 0L }.toInt(),
                panicCode = values.getOrElse(2) { 0L }.toInt(),
            )
    }
}

sealed interface NativeK16ComputerSignal {
    data object Halt : NativeK16ComputerSignal

    data object Yield : NativeK16ComputerSignal

    data object Pause : NativeK16ComputerSignal

    companion object {
        fun from(values: LongArray): NativeK16ComputerSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> Halt
                7L -> Yield
                6L -> Pause
                else -> error("Unknown native K16 computer signal tag: $tag")
            }
    }
}

object NativeVmBindings {
    private val lock = Any()
    private var loadedPath: String? = null

    fun createK16ComputerFromBiosFlash(
        libraryPath: String,
        biosFlashPath: Path,
        memorySize: Int,
        maxSteps: Long,
        storage0Path: Path,
    ): Long {
        load(libraryPath)
        val handle =
            createK16ComputerFromBiosFlashNative(
                biosFlashPath.toAbsolutePath().normalize().toString(),
                memorySize.coerceAtLeast(1),
                maxSteps.coerceAtLeast(1),
                storage0Path.toAbsolutePath().normalize().toString(),
            )
        check(handle != 0L) { "Native K16 BIOS flash computer create returned a zero handle" }
        return handle
    }

    fun restoreK16ComputerFromBiosFlashSnapshot(
        libraryPath: String,
        biosFlashPath: Path,
        memorySize: Int,
        storage0Path: Path,
        snapshot: ByteArray,
    ): Long {
        load(libraryPath)
        require(snapshot.isNotEmpty()) { "K16 computer snapshot must not be empty" }
        val handle =
            restoreK16ComputerFromBiosFlashSnapshotNative(
                biosFlashPath.toAbsolutePath().normalize().toString(),
                memorySize.coerceAtLeast(1),
                storage0Path.toAbsolutePath().normalize().toString(),
                snapshot,
            )
        check(handle != 0L) { "Native K16 BIOS flash computer restore returned a zero handle" }
        return handle
    }

    fun runK16ComputerUntilSignal(handle: Long): NativeK16ComputerSignal {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return NativeK16ComputerSignal.from(runK16ComputerUntilSignalNative(handle))
    }

    fun advanceK16ComputerGameTick(handle: Long) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        advanceK16ComputerGameTickNative(handle)
    }

    fun k16ComputerControl(handle: Long): NativeK16ComputerControl {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return NativeK16ComputerControl.from(k16ComputerControlNative(handle))
    }

    fun k16ComputerDebugOutput(handle: Long): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return k16ComputerDebugOutputNative(handle)
    }

    fun drainK16ComputerDebugOutput(handle: Long): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return drainK16ComputerDebugOutputNative(handle)
    }

    fun drainK16ComputerGpu0Frames(handle: Long): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return drainK16ComputerGpu0FramesNative(handle)
    }

    fun k16ComputerStorage0MediaSnapshot(handle: Long): ByteArray? {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return k16ComputerStorage0MediaSnapshotNative(handle).takeIf { it.isNotEmpty() }
    }

    fun k16ComputerMachineSnapshot(handle: Long): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return k16ComputerMachineSnapshotNative(handle)
    }

    fun pushK16ComputerSerialInput(
        handle: Long,
        bytes: ByteArray,
    ) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        pushK16ComputerSerialInputNative(handle, bytes)
    }

    fun pushK16ComputerKeyboardKeyDown(
        handle: Long,
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    ) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        pushK16ComputerKeyboardKeyDownNative(handle, key, repeat, modifiers)
    }

    fun pushK16ComputerKeyboardKeyUp(
        handle: Long,
        key: Int,
        modifiers: Int,
    ) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        pushK16ComputerKeyboardKeyUpNative(handle, key, modifiers)
    }

    fun pushK16ComputerKeyboardChar(
        handle: Long,
        value: Byte,
    ) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        pushK16ComputerKeyboardCharNative(handle, value)
    }

    fun pushK16ComputerKeyboardPasteBytes(
        handle: Long,
        bytes: ByteArray,
    ) {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        pushK16ComputerKeyboardPasteBytesNative(handle, bytes)
    }

    fun freeK16Computer(handle: Long) {
        if (handle != 0L) {
            freeK16ComputerNative(handle)
        }
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

    @JvmStatic
    private external fun createK16ComputerFromBiosFlashNative(
        biosFlashPath: String,
        memorySize: Int,
        maxSteps: Long,
        storage0Path: String,
    ): Long

    @JvmStatic
    private external fun restoreK16ComputerFromBiosFlashSnapshotNative(
        biosFlashPath: String,
        memorySize: Int,
        storage0Path: String,
        snapshot: ByteArray,
    ): Long

    @JvmStatic
    private external fun runK16ComputerUntilSignalNative(handle: Long): LongArray

    @JvmStatic
    private external fun advanceK16ComputerGameTickNative(handle: Long)

    @JvmStatic
    private external fun k16ComputerControlNative(handle: Long): LongArray

    @JvmStatic
    private external fun k16ComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun drainK16ComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun drainK16ComputerGpu0FramesNative(handle: Long): ByteArray

    @JvmStatic
    private external fun k16ComputerStorage0MediaSnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun k16ComputerMachineSnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun pushK16ComputerSerialInputNative(
        handle: Long,
        bytes: ByteArray,
    )

    @JvmStatic
    private external fun pushK16ComputerKeyboardKeyDownNative(
        handle: Long,
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    )

    @JvmStatic
    private external fun pushK16ComputerKeyboardKeyUpNative(
        handle: Long,
        key: Int,
        modifiers: Int,
    )

    @JvmStatic
    private external fun pushK16ComputerKeyboardCharNative(
        handle: Long,
        value: Byte,
    )

    @JvmStatic
    private external fun pushK16ComputerKeyboardPasteBytesNative(
        handle: Long,
        bytes: ByteArray,
    )

    @JvmStatic
    private external fun freeK16ComputerNative(handle: Long)
}
