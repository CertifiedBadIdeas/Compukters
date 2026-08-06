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
    val mediaReadBlocks: Long = 0,
    val mediaWriteBlocks: Long = 0,
    val uniqueReadBlocks: Long = 0,
    val repeatedReadBlocks: Long = 0,
    val partitionTableReadBlocks: Long = 0,
    val bootMetadataReadBlocks: Long = 0,
    val bootDataReadBlocks: Long = 0,
    val rootMetadataReadBlocks: Long = 0,
    val rootDataReadBlocks: Long = 0,
    val unknownReadBlocks: Long = 0,
    val requestedReadBlocks: Long = 0,
    val requestedReadBytes: Long = 0,
)

data class NativeK16GpuStats(
    val blitBufferCommands: Long = 0,
    val blitPixels: Long = 0,
    val blitSourceBytes: Long = 0,
    val blitMonoCommands: Long = 0,
    val blitMonoPixels: Long = 0,
    val blitMonoSourceBytes: Long = 0,
    val presentCommands: Long = 0,
    val frames: Long = 0,
    val frameTiles: Long = 0,
    val framePayloadBytes: Long = 0,
    val frameMonoPayloadBytes: Long = 0,
)

data class NativeK16OsStats(
    val pathLookups: Long = 0,
    val inodeLoads: Long = 0,
    val dirEntryScans: Long = 0,
    val fileOpens: Long = 0,
    val fileReads: Long = 0,
    val statCalls: Long = 0,
    val processSpawns: Long = 0,
    val programLoads: Long = 0,
    val dynamicImportLoads: Long = 0,
    val libraryLoads: Long = 0,
    val readDirCalls: Long = 0,
    val programLoadBytes: Long = 0,
    val dynamicImportBytes: Long = 0,
    val libraryLoadBytes: Long = 0,
    val genericFileDataReadBlocks: Long = 0,
    val genericFileDataReadBytes: Long = 0,
    val readDirDataReadBlocks: Long = 0,
    val readDirDataReadBytes: Long = 0,
    val programDataReadBlocks: Long = 0,
    val programDataReadBytes: Long = 0,
    val dynamicImportDataReadBlocks: Long = 0,
    val dynamicImportDataReadBytes: Long = 0,
    val libraryDataReadBlocks: Long = 0,
    val libraryDataReadBytes: Long = 0,
    val blockCacheHits: Long = 0,
    val blockCacheMisses: Long = 0,
    val blockCacheBatchReads: Long = 0,
    val initProgramFileDataReadBlocks: Long = 0,
    val initProgramFileDataReadBytes: Long = 0,
    val shellProgramFileDataReadBlocks: Long = 0,
    val shellProgramFileDataReadBytes: Long = 0,
    val otherProgramFileDataReadBlocks: Long = 0,
    val otherProgramFileDataReadBytes: Long = 0,
    val libkraftLibraryFileDataReadBlocks: Long = 0,
    val libkraftLibraryFileDataReadBytes: Long = 0,
    val otherLibraryFileDataReadBlocks: Long = 0,
    val otherLibraryFileDataReadBytes: Long = 0,
)

data class NativeK16MmioDeviceStats(
    val deviceId: Long,
    val base: Long,
    val size: Long,
    val traffic: NativeK16BusTraffic,
    val storage: NativeK16StorageStats = NativeK16StorageStats(),
    val gpu: NativeK16GpuStats = NativeK16GpuStats(),
)

data class NativeK16DecodeCacheStats(
    val entries: Long = 0,
    val hits: Long = 0,
    val misses: Long = 0,
)

data class NativeK16ComputerStatsSnapshot(
    val ram: NativeK16BusTraffic = NativeK16BusTraffic(),
    val mmio: NativeK16BusTraffic = NativeK16BusTraffic(),
    val os: NativeK16OsStats = NativeK16OsStats(),
    val decodeCache: NativeK16DecodeCacheStats = NativeK16DecodeCacheStats(),
    val devices: List<NativeK16MmioDeviceStats> = emptyList(),
) {
    companion object {
        private const val VERSION_V2: Long = 2
        private const val VERSION_V3: Long = 3
        private const val VERSION_V4: Long = 4
        private const val VERSION_V5: Long = 5
        private const val VERSION_V6: Long = 6
        private const val VERSION_V7: Long = 7
        private const val VERSION_V8: Long = 8
        private const val VERSION_V9: Long = 9
        private const val VERSION_V10: Long = 10
        private const val VERSION_V11: Long = 11
        private const val VERSION_V12: Long = 12
        private const val VERSION_V13: Long = 13
        private const val VERSION_V14: Long = 14
        private const val VERSION_V15: Long = 15
        private const val HEADER_LONGS_V2: Int = 10
        private const val HEADER_LONGS_V4: Int = 16
        private const val HEADER_LONGS_V5: Int = 21
        private const val HEADER_LONGS_V6: Int = 24
        private const val HEADER_LONGS_V7: Int = 27
        private const val HEADER_LONGS_V10: Int = 37
        private const val HEADER_LONGS_V12: Int = 40
        private const val HEADER_LONGS_V14: Int = 50
        private const val DEVICE_LONGS_V2: Int = 13
        private const val DEVICE_LONGS_V3: Int = 20
        private const val DEVICE_LONGS_V8: Int = 22
        private const val DEVICE_LONGS_V9: Int = 28
        private const val DEVICE_LONGS_V11: Int = 30
        private const val DEVICE_LONGS_V13: Int = 32
        private const val DEVICE_LONGS_V15: Int = 36

        fun from(values: LongArray): NativeK16ComputerStatsSnapshot {
            require(values.size >= HEADER_LONGS_V2) {
                "Native K16 stats snapshot is too short: ${values.size} longs"
            }
            val version = values[0]
            require(
                version == VERSION_V2 ||
                    version == VERSION_V3 ||
                    version == VERSION_V4 ||
                    version == VERSION_V5 ||
                    version == VERSION_V6 ||
                    version == VERSION_V7 ||
                    version == VERSION_V8 ||
                    version == VERSION_V9 ||
                    version == VERSION_V10 ||
                    version == VERSION_V11 ||
                    version == VERSION_V12 ||
                    version == VERSION_V13 ||
                    version == VERSION_V14 ||
                    version == VERSION_V15,
            ) {
                "Unsupported native K16 stats snapshot version: $version"
            }
            val headerLongs =
                when (version) {
                    VERSION_V15, VERSION_V14 -> HEADER_LONGS_V14
                    VERSION_V13, VERSION_V12 -> HEADER_LONGS_V12
                    VERSION_V11, VERSION_V10 -> HEADER_LONGS_V10
                    VERSION_V9, VERSION_V8, VERSION_V7 -> HEADER_LONGS_V7
                    VERSION_V6 -> HEADER_LONGS_V6
                    VERSION_V5 -> HEADER_LONGS_V5
                    VERSION_V4 -> HEADER_LONGS_V4
                    else -> HEADER_LONGS_V2
                }
            val deviceCount = values[headerLongs - 1].toInt()
            require(deviceCount >= 0) { "Native K16 stats snapshot device count is negative: $deviceCount" }
            val deviceLongs =
                when (version) {
                    VERSION_V2 -> DEVICE_LONGS_V2
                    VERSION_V15 -> DEVICE_LONGS_V15
                    VERSION_V14, VERSION_V13 -> DEVICE_LONGS_V13
                    VERSION_V12, VERSION_V11 -> DEVICE_LONGS_V11
                    VERSION_V10, VERSION_V9 -> DEVICE_LONGS_V9
                    VERSION_V8 -> DEVICE_LONGS_V8
                    else -> DEVICE_LONGS_V3
                }
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
                                mediaReadBlocks = if (version >= VERSION_V11) values[offset + 13] else 0,
                                mediaWriteBlocks = if (version >= VERSION_V11) values[offset + 14] else 0,
                                uniqueReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 15]
                                        version >= VERSION_V8 -> values[offset + 13]
                                        else -> 0
                                    },
                                repeatedReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 16]
                                        version >= VERSION_V8 -> values[offset + 14]
                                        else -> 0
                                    },
                                partitionTableReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 17]
                                        version >= VERSION_V9 -> values[offset + 15]
                                        else -> 0
                                    },
                                bootMetadataReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 18]
                                        version >= VERSION_V9 -> values[offset + 16]
                                        else -> 0
                                    },
                                bootDataReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 19]
                                        version >= VERSION_V9 -> values[offset + 17]
                                        else -> 0
                                    },
                                rootMetadataReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 20]
                                        version >= VERSION_V9 -> values[offset + 18]
                                        else -> 0
                                    },
                                rootDataReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 21]
                                        version >= VERSION_V9 -> values[offset + 19]
                                        else -> 0
                                    },
                                unknownReadBlocks =
                                    when {
                                        version >= VERSION_V11 -> values[offset + 22]
                                        version >= VERSION_V9 -> values[offset + 20]
                                        else -> 0
                                    },
                                requestedReadBlocks = if (version >= VERSION_V13) values[offset + 23] else 0,
                                requestedReadBytes = if (version >= VERSION_V13) values[offset + 24] else 0,
                            ),
                        gpu =
                            if (version != VERSION_V2) {
                                val gpuOffset =
                                    when {
                                        version >= VERSION_V13 -> offset + 25
                                        version >= VERSION_V11 -> offset + 23
                                        version >= VERSION_V9 -> offset + 21
                                        version >= VERSION_V8 -> offset + 15
                                        else -> offset + 13
                                    }
                                NativeK16GpuStats(
                                    blitBufferCommands = values[gpuOffset],
                                    blitPixels = values[gpuOffset + 1],
                                    blitSourceBytes = values[gpuOffset + 2],
                                    blitMonoCommands = if (version >= VERSION_V15) values[gpuOffset + 3] else 0,
                                    blitMonoPixels = if (version >= VERSION_V15) values[gpuOffset + 4] else 0,
                                    blitMonoSourceBytes = if (version >= VERSION_V15) values[gpuOffset + 5] else 0,
                                    presentCommands =
                                        if (version >= VERSION_V15) values[gpuOffset + 6] else values[gpuOffset + 3],
                                    frames =
                                        if (version >= VERSION_V15) values[gpuOffset + 7] else values[gpuOffset + 4],
                                    frameTiles =
                                        if (version >= VERSION_V15) values[gpuOffset + 8] else values[gpuOffset + 5],
                                    framePayloadBytes =
                                        if (version >= VERSION_V15) values[gpuOffset + 9] else values[gpuOffset + 6],
                                    frameMonoPayloadBytes =
                                        if (version >= VERSION_V15) values[gpuOffset + 10] else 0,
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
                    if (version == VERSION_V4 ||
                        version == VERSION_V5 ||
                        version == VERSION_V6 ||
                        version == VERSION_V7 ||
                        version == VERSION_V8 ||
                        version == VERSION_V9 ||
                        version == VERSION_V10 ||
                        version == VERSION_V11 ||
                        version == VERSION_V12 ||
                        version == VERSION_V13 ||
                        version == VERSION_V14 ||
                        version == VERSION_V15
                    ) {
                        NativeK16OsStats(
                            pathLookups = values[9],
                            inodeLoads = values[10],
                            dirEntryScans = values[11],
                            fileOpens = values[12],
                            fileReads = values[13],
                            statCalls = values[14],
                            processSpawns = if (version >= VERSION_V5) values[15] else 0,
                            programLoads = if (version >= VERSION_V5) values[16] else 0,
                            dynamicImportLoads = if (version >= VERSION_V5) values[17] else 0,
                            libraryLoads = if (version >= VERSION_V5) values[18] else 0,
                            readDirCalls = if (version >= VERSION_V5) values[19] else 0,
                            programLoadBytes = if (version >= VERSION_V7) values[20] else 0,
                            dynamicImportBytes = if (version >= VERSION_V7) values[21] else 0,
                            libraryLoadBytes = if (version >= VERSION_V7) values[22] else 0,
                            genericFileDataReadBlocks = if (version >= VERSION_V10) values[23] else 0,
                            genericFileDataReadBytes = if (version >= VERSION_V10) values[24] else 0,
                            readDirDataReadBlocks = if (version >= VERSION_V10) values[25] else 0,
                            readDirDataReadBytes = if (version >= VERSION_V10) values[26] else 0,
                            programDataReadBlocks = if (version >= VERSION_V10) values[27] else 0,
                            programDataReadBytes = if (version >= VERSION_V10) values[28] else 0,
                            dynamicImportDataReadBlocks = if (version >= VERSION_V10) values[29] else 0,
                            dynamicImportDataReadBytes = if (version >= VERSION_V10) values[30] else 0,
                            libraryDataReadBlocks = if (version >= VERSION_V10) values[31] else 0,
                            libraryDataReadBytes = if (version >= VERSION_V10) values[32] else 0,
                            blockCacheHits = if (version >= VERSION_V12) values[33] else 0,
                            blockCacheMisses = if (version >= VERSION_V12) values[34] else 0,
                            blockCacheBatchReads = if (version >= VERSION_V12) values[35] else 0,
                            initProgramFileDataReadBlocks = if (version >= VERSION_V14) values[36] else 0,
                            initProgramFileDataReadBytes = if (version >= VERSION_V14) values[37] else 0,
                            shellProgramFileDataReadBlocks = if (version >= VERSION_V14) values[38] else 0,
                            shellProgramFileDataReadBytes = if (version >= VERSION_V14) values[39] else 0,
                            otherProgramFileDataReadBlocks = if (version >= VERSION_V14) values[40] else 0,
                            otherProgramFileDataReadBytes = if (version >= VERSION_V14) values[41] else 0,
                            libkraftLibraryFileDataReadBlocks = if (version >= VERSION_V14) values[42] else 0,
                            libkraftLibraryFileDataReadBytes = if (version >= VERSION_V14) values[43] else 0,
                            otherLibraryFileDataReadBlocks = if (version >= VERSION_V14) values[44] else 0,
                            otherLibraryFileDataReadBytes = if (version >= VERSION_V14) values[45] else 0,
                        )
                    } else {
                        NativeK16OsStats()
                    },
                decodeCache =
                    if (
                        version == VERSION_V6 ||
                        version == VERSION_V7 ||
                        version == VERSION_V8 ||
                        version == VERSION_V9 ||
                        version == VERSION_V10 ||
                        version == VERSION_V11 ||
                        version == VERSION_V12 ||
                        version == VERSION_V13 ||
                        version == VERSION_V14 ||
                        version == VERSION_V15
                    ) {
                        val offset =
                            when {
                                version >= VERSION_V14 -> 46
                                version >= VERSION_V12 -> 36
                                version >= VERSION_V10 -> 33
                                version >= VERSION_V7 -> 23
                                else -> 20
                            }
                        NativeK16DecodeCacheStats(
                            entries = values[offset],
                            hits = values[offset + 1],
                            misses = values[offset + 2],
                        )
                    } else {
                        NativeK16DecodeCacheStats()
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

    fun attachK16ComputerRetainedDisplayViewer(
        handle: Long,
        viewerToken: Long,
        computerId: Int,
    ): Long {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        require(viewerToken > 0) { "Retained display viewer token must be positive" }
        require(computerId > 0) { "Retained display computer id must be positive" }
        return attachK16ComputerRetainedDisplayViewerNative(handle, viewerToken, computerId)
    }

    fun detachK16ComputerRetainedDisplayViewer(
        handle: Long,
        viewerToken: Long,
    ): Boolean {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        require(viewerToken > 0) { "Retained display viewer token must be positive" }
        return detachK16ComputerRetainedDisplayViewerNative(handle, viewerToken)
    }

    fun acceptK16ComputerRetainedDisplayServerbound(
        handle: Long,
        viewerToken: Long,
        payload: ByteArray,
    ): Int {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        require(viewerToken > 0) { "Retained display viewer token must be positive" }
        return acceptK16ComputerRetainedDisplayServerboundNative(handle, viewerToken, payload)
    }

    fun drainK16ComputerRetainedDisplayPayload(
        handle: Long,
        viewerToken: Long,
    ): ByteArray {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        require(viewerToken > 0) { "Retained display viewer token must be positive" }
        return drainK16ComputerRetainedDisplayPayloadNative(handle, viewerToken)
    }

    fun drainK16ComputerRetainedDisplayPayloads(handle: Long): List<NativeRetainedDisplayPayload> {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        val batch = drainK16ComputerRetainedDisplayPayloadsNative(handle) ?: return emptyList()
        return decodeRetainedDisplayPayloadBatch(batch)
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

    internal fun decodeRetainedDisplayPayloadBatch(batch: ByteArray): List<NativeRetainedDisplayPayload> {
        var offset = 0

        fun fail(): Nothing = error("Native retained display payload batch is malformed")

        fun requireBytes(count: Int) {
            if (count < 0 || offset > batch.size - count) fail()
        }

        fun readU16(): Int {
            requireBytes(2)
            val value = (batch[offset].toInt() and 0xff) or ((batch[offset + 1].toInt() and 0xff) shl 8)
            offset += 2
            return value
        }

        fun readU32(): Long {
            requireBytes(4)
            var value = 0L
            repeat(4) { index -> value = value or ((batch[offset + index].toLong() and 0xff) shl (index * 8)) }
            offset += 4
            return value
        }

        fun readI64(): Long {
            requireBytes(8)
            var value = 0L
            repeat(8) { index -> value = value or ((batch[offset + index].toLong() and 0xff) shl (index * 8)) }
            offset += 8
            return value
        }

        if (batch.size < 16 || readU32() != 0x4e52_444bL || readU16() != 1 || readU16() != 0) fail()
        if (readU32() != batch.size.toLong()) fail()
        val count = readU32().takeIf { it in 1..64 }?.toInt() ?: fail()
        val payloads = ArrayList<NativeRetainedDisplayPayload>(count)
        repeat(count) {
            val viewerToken = readI64().takeIf { it > 0 } ?: fail()
            val payloadLength = readU32().takeIf { it in 24..(512L * 1024L) }?.toInt() ?: fail()
            if (readU32() != 0L) fail()
            requireBytes(payloadLength)
            val payload = batch.copyOfRange(offset, offset + payloadLength)
            offset += payloadLength
            payloads += NativeRetainedDisplayPayload(viewerToken, payload)
        }
        if (offset != batch.size) fail()
        return payloads
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
    private external fun attachK16ComputerRetainedDisplayViewerNative(
        handle: Long,
        viewerToken: Long,
        computerId: Int,
    ): Long

    @JvmStatic
    private external fun detachK16ComputerRetainedDisplayViewerNative(
        handle: Long,
        viewerToken: Long,
    ): Boolean

    @JvmStatic
    private external fun acceptK16ComputerRetainedDisplayServerboundNative(
        handle: Long,
        viewerToken: Long,
        payload: ByteArray,
    ): Int

    @JvmStatic
    private external fun drainK16ComputerRetainedDisplayPayloadNative(
        handle: Long,
        viewerToken: Long,
    ): ByteArray

    @JvmStatic
    private external fun drainK16ComputerRetainedDisplayPayloadsNative(handle: Long): ByteArray?

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
