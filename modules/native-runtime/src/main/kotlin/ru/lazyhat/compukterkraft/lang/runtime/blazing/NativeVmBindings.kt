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

data class NativeK16BusTraffic(
    val loads: Long = 0,
    val stores: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
)

data class NativeK16StorageStats(
    val readCommands: Long = 0,
    val writeCommands: Long = 0,
    val flushCommands: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    val failedCommands: Long = 0,
)

data class NativeK16GpuStats(
    val blitBufferCommands: Long = 0,
    val blitPixels: Long = 0,
    val blitSourceBytes: Long = 0,
    val presentCommands: Long = 0,
    val frames: Long = 0,
    val frameTiles: Long = 0,
    val framePayloadBytes: Long = 0,
)

data class NativeK16OsStats(
    val pathLookups: Long = 0,
    val inodeLoads: Long = 0,
    val dirEntryScans: Long = 0,
    val fileOpens: Long = 0,
    val fileReads: Long = 0,
    val statCalls: Long = 0,
)

data class NativeK16MmioDeviceStats(
    val deviceId: Long,
    val base: Long,
    val size: Long,
    val traffic: NativeK16BusTraffic,
    val storage: NativeK16StorageStats = NativeK16StorageStats(),
    val gpu: NativeK16GpuStats = NativeK16GpuStats(),
)

data class NativeK16ComputerStatsSnapshot(
    val ram: NativeK16BusTraffic = NativeK16BusTraffic(),
    val mmio: NativeK16BusTraffic = NativeK16BusTraffic(),
    val os: NativeK16OsStats = NativeK16OsStats(),
    val devices: List<NativeK16MmioDeviceStats> = emptyList(),
) {
    companion object {
        private const val VERSION_V2: Long = 2
        private const val VERSION_V3: Long = 3
        private const val VERSION_V4: Long = 4
        private const val HEADER_LONGS_V2: Int = 10
        private const val HEADER_LONGS_V4: Int = 16
        private const val DEVICE_LONGS_V2: Int = 13
        private const val DEVICE_LONGS_V3: Int = 20

        fun from(values: LongArray): NativeK16ComputerStatsSnapshot {
            require(values.size >= HEADER_LONGS_V2) {
                "Native K16 stats snapshot is too short: ${values.size} longs"
            }
            val version = values[0]
            require(version == VERSION_V2 || version == VERSION_V3 || version == VERSION_V4) {
                "Unsupported native K16 stats snapshot version: $version"
            }
            val headerLongs = if (version == VERSION_V4) HEADER_LONGS_V4 else HEADER_LONGS_V2
            val deviceCount = values[headerLongs - 1].toInt()
            require(deviceCount >= 0) { "Native K16 stats snapshot device count is negative: $deviceCount" }
            val deviceLongs = if (version == VERSION_V2) DEVICE_LONGS_V2 else DEVICE_LONGS_V3
            val expectedSize = headerLongs + deviceCount * deviceLongs
            require(values.size == expectedSize) {
                "Native K16 stats snapshot has ${values.size} longs but expected $expectedSize"
            }
            val devices =
                (0 until deviceCount).map { index ->
                    val offset = headerLongs + index * deviceLongs
                    NativeK16MmioDeviceStats(
                        deviceId = values[offset],
                        base = values[offset + 1],
                        size = values[offset + 2],
                        traffic =
                            NativeK16BusTraffic(
                                loads = values[offset + 3],
                                stores = values[offset + 4],
                                bytesRead = values[offset + 5],
                                bytesWritten = values[offset + 6],
                            ),
                        storage =
                            NativeK16StorageStats(
                                readCommands = values[offset + 7],
                                writeCommands = values[offset + 8],
                                flushCommands = values[offset + 9],
                                bytesRead = values[offset + 10],
                                bytesWritten = values[offset + 11],
                                failedCommands = values[offset + 12],
                            ),
                        gpu =
                            if (version != VERSION_V2) {
                                NativeK16GpuStats(
                                    blitBufferCommands = values[offset + 13],
                                    blitPixels = values[offset + 14],
                                    blitSourceBytes = values[offset + 15],
                                    presentCommands = values[offset + 16],
                                    frames = values[offset + 17],
                                    frameTiles = values[offset + 18],
                                    framePayloadBytes = values[offset + 19],
                                )
                            } else {
                                NativeK16GpuStats()
                            },
                    )
                }
            return NativeK16ComputerStatsSnapshot(
                ram =
                    NativeK16BusTraffic(
                        loads = values[1],
                        stores = values[2],
                        bytesRead = values[3],
                        bytesWritten = values[4],
                    ),
                mmio =
                    NativeK16BusTraffic(
                        loads = values[5],
                        stores = values[6],
                        bytesRead = values[7],
                        bytesWritten = values[8],
                    ),
                os =
                    if (version == VERSION_V4) {
                        NativeK16OsStats(
                            pathLookups = values[9],
                            inodeLoads = values[10],
                            dirEntryScans = values[11],
                            fileOpens = values[12],
                            fileReads = values[13],
                            statCalls = values[14],
                        )
                    } else {
                        NativeK16OsStats()
                    },
                devices = devices,
            )
        }
    }
}

sealed interface NativeK16ComputerSignal {
    data object Halt : NativeK16ComputerSignal

    data object Wait : NativeK16ComputerSignal

    data object Yield : NativeK16ComputerSignal

    data object Pause : NativeK16ComputerSignal

    companion object {
        fun from(values: LongArray): NativeK16ComputerSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> Halt
                8L -> Wait
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

    fun k16ComputerStatsSnapshot(handle: Long): NativeK16ComputerStatsSnapshot {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        return NativeK16ComputerStatsSnapshot.from(k16ComputerStatsSnapshotNative(handle))
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
    private external fun k16ComputerStatsSnapshotNative(handle: Long): LongArray

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
