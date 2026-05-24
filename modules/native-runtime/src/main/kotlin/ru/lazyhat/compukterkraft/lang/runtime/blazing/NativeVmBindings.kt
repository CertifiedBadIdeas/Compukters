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

import ru.lazyhat.compukterkraft.lang.runtime.VmValue

data class NativeLowImageVmMetrics(
    val runInvocations: Long = 0,
    val elapsedNanos: Long = 0,
    val pauseSignals: Long = 0,
) {
    companion object {
        val EMPTY = NativeLowImageVmMetrics()

        fun from(values: LongArray): NativeLowImageVmMetrics =
            NativeLowImageVmMetrics(
                runInvocations = values.getOrElse(0) { 0L },
                elapsedNanos = values.getOrElse(1) { 0L },
                pauseSignals = values.getOrElse(2) { 0L },
            )
    }
}

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

sealed interface NativeLowImageVmSignal {
    data object HaltUnit : NativeLowImageVmSignal

    data class HaltI32(val value: Int) : NativeLowImageVmSignal

    data class HaltI64(val value: Long) : NativeLowImageVmSignal

    data class HaltAddr(val value: UInt) : NativeLowImageVmSignal

    data class HaltBool(val value: Boolean) : NativeLowImageVmSignal

    data object Pause : NativeLowImageVmSignal

    companion object {
        fun from(values: LongArray): NativeLowImageVmSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> HaltUnit
                2L -> HaltI32(values.getOrElse(1) { 0L }.toInt())
                3L -> HaltI64(values.getOrElse(1) { 0L })
                4L -> HaltAddr(values.getOrElse(1) { 0L }.toUInt())
                5L -> HaltBool(values.getOrElse(1) { 0L } != 0L)
                6L -> Pause
                else -> error("Unknown native low image VM signal tag: $tag")
            }
    }
}

object NativeVmBindings {
    private val lock = Any()
    private var loadedPath: String? = null

    fun createLowImage(
        libraryPath: String,
        image: ByteArray,
        sliceBudgetNanos: Int,
    ): Long {
        load(libraryPath)
        val handle = createLowImageNative(image, sliceBudgetNanos.coerceAtLeast(1))
        check(handle != 0L) { "Native low image VM create returned a zero handle" }
        return handle
    }

    fun runLowImageUntilSignal(handle: Long): NativeLowImageVmSignal {
        require(handle != 0L) { "Native low image VM handle is zero" }
        return NativeLowImageVmSignal.from(runLowImageUntilSignalNative(handle))
    }

    fun lowImageMetrics(handle: Long): NativeLowImageVmMetrics {
        require(handle != 0L) { "Native low image VM handle is zero" }
        return NativeLowImageVmMetrics.from(lowImageMetricsNative(handle))
    }

    fun freeLowImage(handle: Long) {
        if (handle != 0L) {
            freeLowImageNative(handle)
        }
    }

    fun createRuxComputer(
        libraryPath: String,
        image: ByteArray,
        memorySize: Int,
        sliceBudgetNanos: Long,
        storage0Media: ByteArray? = null,
        storage0Path: Path? = null,
    ): Long {
        require(storage0Media == null || storage0Path == null) {
            "storage0Media and storage0Path are mutually exclusive"
        }
        load(libraryPath)
        val handle =
            createRuxComputerNative(
                image,
                memorySize.coerceAtLeast(1),
                sliceBudgetNanos.coerceAtLeast(1),
                storage0Media,
                storage0Path?.toAbsolutePath()?.normalize()?.toString(),
            )
        check(handle != 0L) { "Native Rux computer create returned a zero handle" }
        return handle
    }

    fun runRuxComputerUntilSignal(handle: Long): NativeLowImageVmSignal {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        return NativeLowImageVmSignal.from(runRuxComputerUntilSignalNative(handle))
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
    private external fun createLowImageNative(
        image: ByteArray,
        sliceBudgetNanos: Int,
    ): Long

    @JvmStatic
    private external fun runLowImageUntilSignalNative(handle: Long): LongArray

    @JvmStatic
    private external fun lowImageMetricsNative(handle: Long): LongArray

    @JvmStatic
    private external fun freeLowImageNative(handle: Long)

    @JvmStatic
    private external fun createRuxComputerNative(
        image: ByteArray,
        memorySize: Int,
        sliceBudgetNanos: Long,
        storage0Media: ByteArray?,
        storage0Path: String?,
    ): Long

    @JvmStatic
    private external fun runRuxComputerUntilSignalNative(handle: Long): LongArray

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
