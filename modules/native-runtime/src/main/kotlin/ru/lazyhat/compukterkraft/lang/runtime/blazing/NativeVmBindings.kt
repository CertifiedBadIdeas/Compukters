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
    fun isTerminal(): Boolean =
        status == STATUS_HALTED || status == STATUS_PANIC

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

class NativeK16ComputerDisplaySnapshot(
    val columns: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val sequence: Long,
    val cells: ByteArray,
) {
    companion object {
        fun from(bytes: ByteArray): NativeK16ComputerDisplaySnapshot? {
            if (bytes.isEmpty()) {
                return null
            }
            val reader = NativeK16ComputerDisplaySnapshotReader(bytes)
            val columns = reader.readU32AsInt("columns")
            val rows = reader.readU32AsInt("rows")
            val cursorX = reader.readU32AsInt("cursorX")
            val cursorY = reader.readU32AsInt("cursorY")
            val sequence = reader.readI64("sequence")
            val cellCount = reader.readU32AsInt("cellCount")
            val cells = reader.readBytes(cellCount, "cells")
            reader.requireEnd()
            return NativeK16ComputerDisplaySnapshot(
                columns = columns,
                rows = rows,
                cursorX = cursorX,
                cursorY = cursorY,
                sequence = sequence,
                cells = cells,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is NativeK16ComputerDisplaySnapshot &&
                    columns == other.columns &&
                    rows == other.rows &&
                    cursorX == other.cursorX &&
                    cursorY == other.cursorY &&
                    sequence == other.sequence &&
                    cells.contentEquals(other.cells)
            )

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + rows
        result = 31 * result + cursorX
        result = 31 * result + cursorY
        result = 31 * result + sequence.hashCode()
        result = 31 * result + cells.contentHashCode()
        return result
    }

    override fun toString(): String =
        "NativeK16ComputerDisplaySnapshot(columns=$columns, rows=$rows, cursorX=$cursorX, " +
            "cursorY=$cursorY, sequence=$sequence, cells=${cells.size} bytes)"
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

    fun k16ComputerDisplay0Snapshot(handle: Long): NativeK16ComputerDisplaySnapshot? {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return NativeK16ComputerDisplaySnapshot.from(k16ComputerDisplay0SnapshotNative(handle))
    }

    fun drainK16ComputerFramebuffer0Frames(handle: Long): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return drainK16ComputerFramebuffer0FramesNative(handle)
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
    private external fun k16ComputerControlNative(handle: Long): LongArray

    @JvmStatic
    private external fun k16ComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun drainK16ComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun k16ComputerDisplay0SnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun drainK16ComputerFramebuffer0FramesNative(handle: Long): ByteArray

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
    private external fun freeK16ComputerNative(handle: Long)
}

private class NativeK16ComputerDisplaySnapshotReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun readU32AsInt(fieldName: String): Int {
        val value = readU32(fieldName)
        require(value <= Int.MAX_VALUE.toUInt()) {
            "Native K16 computer display snapshot field $fieldName does not fit Int: $value"
        }
        return value.toInt()
    }

    fun readI64(fieldName: String): Long {
        require(offset + 8 <= bytes.size) {
            "Unexpected end of native K16 computer display snapshot while reading $fieldName"
        }
        var value = 0L
        repeat(8) { index ->
            value = value or (((bytes[offset++].toLong() and 0xffL) shl (index * 8)))
        }
        return value
    }

    fun readBytes(
        length: Int,
        fieldName: String,
    ): ByteArray {
        require(length >= 0) { "Negative native K16 computer display snapshot length for $fieldName: $length" }
        require(offset + length <= bytes.size) {
            "Unexpected end of native K16 computer display snapshot while reading $fieldName"
        }
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    fun requireEnd() {
        require(offset == bytes.size) {
            "Trailing native K16 computer display snapshot bytes: ${bytes.size - offset}"
        }
    }

    private fun readU32(fieldName: String): UInt {
        require(offset + 4 <= bytes.size) {
            "Unexpected end of native K16 computer display snapshot while reading $fieldName"
        }
        val b0 = bytes[offset++].toUInt() and 0xffu
        val b1 = bytes[offset++].toUInt() and 0xffu
        val b2 = bytes[offset++].toUInt() and 0xffu
        val b3 = bytes[offset++].toUInt() and 0xffu
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
