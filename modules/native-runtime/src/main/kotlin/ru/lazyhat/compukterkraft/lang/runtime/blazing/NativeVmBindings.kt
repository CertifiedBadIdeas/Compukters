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

data class NativeRuxComputerControl(
    val status: Int,
    val exitCode: Int,
    val panicCode: Int,
) {
    companion object {
        fun from(values: LongArray): NativeRuxComputerControl =
            NativeRuxComputerControl(
                status = values.getOrElse(0) { 0L }.toInt(),
                exitCode = values.getOrElse(1) { 0L }.toInt(),
                panicCode = values.getOrElse(2) { 0L }.toInt(),
            )
    }
}

class NativeRuxComputerDisplaySnapshot(
    val columns: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val sequence: Long,
    val cells: ByteArray,
) {
    companion object {
        fun from(bytes: ByteArray): NativeRuxComputerDisplaySnapshot? {
            if (bytes.isEmpty()) {
                return null
            }
            val reader = NativeRuxComputerDisplaySnapshotReader(bytes)
            val columns = reader.readU32AsInt("columns")
            val rows = reader.readU32AsInt("rows")
            val cursorX = reader.readU32AsInt("cursorX")
            val cursorY = reader.readU32AsInt("cursorY")
            val sequence = reader.readI64("sequence")
            val cellCount = reader.readU32AsInt("cellCount")
            val cells = reader.readBytes(cellCount, "cells")
            reader.requireEnd()
            return NativeRuxComputerDisplaySnapshot(
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
            other is NativeRuxComputerDisplaySnapshot &&
            columns == other.columns &&
            rows == other.rows &&
            cursorX == other.cursorX &&
            cursorY == other.cursorY &&
            sequence == other.sequence &&
            cells.contentEquals(other.cells)

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
        "NativeRuxComputerDisplaySnapshot(columns=$columns, rows=$rows, cursorX=$cursorX, " +
            "cursorY=$cursorY, sequence=$sequence, cells=${cells.size} bytes)"
}

sealed interface NativeRuxComputerSignal {
    data object Halt : NativeRuxComputerSignal

    data object Pause : NativeRuxComputerSignal

    companion object {
        fun from(values: LongArray): NativeRuxComputerSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> Halt
                6L -> Pause
                else -> error("Unknown native Rux computer signal tag: $tag")
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
            createRuxComputerFromBiosFlashNative(
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
        require(snapshot.isNotEmpty()) { "Rux computer snapshot must not be empty" }
        val handle =
            restoreRuxComputerFromBiosFlashSnapshotNative(
                biosFlashPath.toAbsolutePath().normalize().toString(),
                memorySize.coerceAtLeast(1),
                storage0Path.toAbsolutePath().normalize().toString(),
                snapshot,
            )
        check(handle != 0L) { "Native K16 BIOS flash computer restore returned a zero handle" }
        return handle
    }

    fun runK16ComputerUntilSignal(handle: Long): NativeRuxComputerSignal {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return NativeRuxComputerSignal.from(runRux16ComputerUntilSignalNative(handle))
    }

    fun ruxComputerControl(handle: Long): NativeRuxComputerControl {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return NativeRuxComputerControl.from(ruxComputerControlNative(handle))
    }

    fun ruxComputerDebugOutput(handle: Long): ByteArray {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return ruxComputerDebugOutputNative(handle)
    }

    fun drainRuxComputerDebugOutput(handle: Long): ByteArray {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return drainRuxComputerDebugOutputNative(handle)
    }

    fun ruxComputerDisplay0Snapshot(handle: Long): NativeRuxComputerDisplaySnapshot? {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return NativeRuxComputerDisplaySnapshot.from(ruxComputerDisplay0SnapshotNative(handle))
    }

    fun ruxComputerStorage0MediaSnapshot(handle: Long): ByteArray? {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return ruxComputerStorage0MediaSnapshotNative(handle).takeIf { it.isNotEmpty() }
    }

    fun ruxComputerMachineSnapshot(handle: Long): ByteArray {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return ruxComputerMachineSnapshotNative(handle)
    }

    fun pushRuxComputerSerialInput(
        handle: Long,
        bytes: ByteArray,
    ) {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        pushRuxComputerSerialInputNative(handle, bytes)
    }

    fun freeRuxComputer(handle: Long) {
        if (handle != 0L) {
            freeRuxComputerNative(handle)
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
    private external fun createRuxComputerFromBiosFlashNative(
        biosFlashPath: String,
        memorySize: Int,
        maxSteps: Long,
        storage0Path: String,
    ): Long

    @JvmStatic
    private external fun restoreRuxComputerFromBiosFlashSnapshotNative(
        biosFlashPath: String,
        memorySize: Int,
        storage0Path: String,
        snapshot: ByteArray,
    ): Long

    @JvmStatic
    private external fun runRux16ComputerUntilSignalNative(handle: Long): LongArray

    @JvmStatic
    private external fun ruxComputerControlNative(handle: Long): LongArray

    @JvmStatic
    private external fun ruxComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun drainRuxComputerDebugOutputNative(handle: Long): ByteArray

    @JvmStatic
    private external fun ruxComputerDisplay0SnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun ruxComputerStorage0MediaSnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun ruxComputerMachineSnapshotNative(handle: Long): ByteArray

    @JvmStatic
    private external fun pushRuxComputerSerialInputNative(
        handle: Long,
        bytes: ByteArray,
    )

    @JvmStatic
    private external fun freeRuxComputerNative(handle: Long)
}

private class NativeRuxComputerDisplaySnapshotReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun readU32AsInt(fieldName: String): Int {
        val value = readU32(fieldName)
        require(value <= Int.MAX_VALUE.toUInt()) {
            "Native Rux computer display snapshot field $fieldName does not fit Int: $value"
        }
        return value.toInt()
    }

    fun readI64(fieldName: String): Long {
        require(offset + 8 <= bytes.size) {
            "Unexpected end of native Rux computer display snapshot while reading $fieldName"
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
        require(length >= 0) { "Negative native Rux computer display snapshot length for $fieldName: $length" }
        require(offset + length <= bytes.size) {
            "Unexpected end of native Rux computer display snapshot while reading $fieldName"
        }
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    fun requireEnd() {
        require(offset == bytes.size) {
            "Trailing native Rux computer display snapshot bytes: ${bytes.size - offset}"
        }
    }

    private fun readU32(fieldName: String): UInt {
        require(offset + 4 <= bytes.size) {
            "Unexpected end of native Rux computer display snapshot while reading $fieldName"
        }
        val b0 = bytes[offset++].toUInt() and 0xffu
        val b1 = bytes[offset++].toUInt() and 0xffu
        val b2 = bytes[offset++].toUInt() and 0xffu
        val b3 = bytes[offset++].toUInt() and 0xffu
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
