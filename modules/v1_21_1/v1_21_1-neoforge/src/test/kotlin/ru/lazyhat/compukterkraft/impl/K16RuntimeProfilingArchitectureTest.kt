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

package ru.lazyhat.compukterkraft.impl

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16RuntimeProfilingArchitectureTest {
    @Test
    fun gradleExposesDedicatedK16WaitProfilingTask() {
        val rootBuildScript = Path.of("../../../build.gradle.kts").readText()
        val neoforgeBuildScript = Path.of("build.gradle.kts").readText()
        val k16FirmwareConvention = Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()
        val docs = Path.of("../../../docs/PROFILING.md").readText()
        val textIoProfilingSource = Path.of("src/test/kotlin/ru/lazyhat/compukterkraft/impl/K16RuntimeTextIoProfilingTest.kt").readText()
        val manyVmProfilingSource = Path.of("src/test/kotlin/ru/lazyhat/compukterkraft/impl/K16ManyVmServerBudgetProfilingTest.kt").readText()

        assertTrue(neoforgeBuildScript.contains("alias(libs.plugins.k16FirmwareConvention)"))
        assertTrue(k16FirmwareConvention.contains("profileK16RuntimeWait"))
        assertTrue(k16FirmwareConvention.contains("K16RuntimeWaitProfilingTest"))
        assertTrue(k16FirmwareConvention.contains("profileK16RuntimeTextIo"))
        assertTrue(k16FirmwareConvention.contains("K16RuntimeTextIoProfilingTest"))
        assertTrue(k16FirmwareConvention.contains("profileK16ManyVmServerBudget"))
        assertTrue(k16FirmwareConvention.contains("K16ManyVmServerBudgetProfilingTest"))
        assertTrue(k16FirmwareConvention.contains("showStandardStreams = true"))
        assertTrue(rootBuildScript.contains("profileK16RuntimeWait"))
        assertTrue(rootBuildScript.contains("profileK16RuntimeTextIo"))
        assertTrue(rootBuildScript.contains("profileK16ManyVmServerBudget"))
        assertTrue(docs.contains("./gradlew-sandbox-dev-parallel profileK16RuntimeWait"))
        assertTrue(docs.contains("./gradlew-sandbox-dev-parallel profileK16RuntimeTextIo"))
        assertTrue(docs.contains("./gradlew-sandbox-dev-parallel profileK16ManyVmServerBudget"))
        assertTrue(docs.contains("k16TextInput: events="))
        assertTrue(docs.contains("k16Bus: ramLoads="))
        assertTrue(docs.contains("k16Devices: mapped="))
        assertTrue(docs.contains("k16Storage0: readCommands="))
        assertTrue(docs.contains("requestedReadBlocks="))
        assertTrue(docs.contains("requestedReadBytes="))
        assertTrue(docs.contains("k16FsHotspots: metadataOps="))
        assertTrue(docs.contains("pathLookups=["))
        assertTrue(docs.contains("readDirDataReadBlocks=["))
        assertTrue(docs.contains("storageWriteCommands=["))
        assertTrue(docs.contains("storageMediaReadBlocks="))
        assertTrue(docs.contains("mediaReadBlocks="))
        assertTrue(docs.contains("uniqueReadBlocks="))
        assertTrue(docs.contains("storageRepeatedReadBlocks="))
        assertTrue(docs.contains("storageRootDataReadBlocks="))
        assertTrue(docs.contains("blockCacheHits="))
        assertTrue(docs.contains("blockCacheMisses="))
        assertTrue(docs.contains("blockCacheBatchReads="))
        assertTrue(docs.contains("partitionTableReadBlocks="))
        assertTrue(docs.contains("programLoadBytes="))
        assertTrue(docs.contains("programDataReadBlocks="))
        assertTrue(docs.contains("dynamicImportDataReadBlocks="))
        assertTrue(docs.contains("libraryDataReadBlocks="))
        assertTrue(docs.contains("initProgramFileDataReadBlocks="))
        assertTrue(docs.contains("shellProgramFileDataReadBlocks="))
        assertTrue(docs.contains("libkraftLibraryFileDataReadBlocks="))
        assertTrue(textIoProfilingSource.contains("programLoadBytes="))
        assertTrue(textIoProfilingSource.contains("programDataReadBlocks="))
        assertTrue(textIoProfilingSource.contains("initProgramFileDataReadBlocks="))
        assertTrue(textIoProfilingSource.contains("blockCacheHits="))
        assertTrue(textIoProfilingSource.contains("formatKfsHotspotSummary"))
        assertTrue(docs.contains("k16Wait: entries="))
        assertTrue(textIoProfilingSource.contains("bios.splash.visible"))
        assertTrue(textIoProfilingSource.contains("bios.splash.wait"))
        assertTrue(textIoProfilingSource.contains("shell.prompt.after_splash"))
        assertTrue(textIoProfilingSource.contains("shell.prompt.after_splash_to_prompt_visible"))
        assertTrue(textIoProfilingSource.contains("shell.prompt.prompt_visible_to_input_ready"))
        assertTrue(manyVmProfilingSource.contains("k16ManyVmSplash"))
        assertTrue(manyVmProfilingSource.contains("k16ManyVmBootAfterSplash"))
        assertTrue(manyVmProfilingSource.contains("k16ManyVmCpuHighload"))
        assertTrue(manyVmProfilingSource.contains("k16ManyVmTextDisplayHighload"))
        assertTrue(manyVmProfilingSource.contains("k16ManyVmStorageHighload"))
        assertTrue(docs.contains("bios.splash.wait"))
        assertTrue(docs.contains("k16ManyVmBootAfterSplash"))
        assertTrue(docs.contains("k16ManyVmCpuHighload"))
        assertTrue(docs.contains("k16ManyVmTextDisplayHighload"))
        assertTrue(docs.contains("k16ManyVmStorageHighload"))
        assertFalse(docs.contains("profileRuntimeVmImage"))
    }

    @Test
    fun inGameFactoryKeepsRuntimeProfilingDisabledByDefault() {
        val source =
            Path
                .of("../v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertFalse(source.contains("RecordingRuntimeMetricsCollector"))
    }

    @Test
    fun k16KernelBatchesContiguousFullBlockStorageReads() {
        val storageSource = Path.of("../../../guest/kraftos/kernel/src/kfs/storage.rs").readText()
        val deviceSource = Path.of("../../../guest/kraftos/kernel/src/kfs/device.rs").readText()

        assertTrue(storageSource.contains("unsafe fn read_fs_blocks_to_ram("))
        assertFalse(storageSource.contains("use k16_abi::computer::storage0"))
        assertFalse(storageSource.contains("write_i32(storage0::COMMAND"))
        assertTrue(deviceSource.contains("pub unsafe fn read_storage_blocks_to_ram("))
        assertTrue(deviceSource.contains("write_u32(storage0::BLOCK_COUNT, block_count)"))
        assertTrue(storageSource.contains("full_block_count"))
        assertTrue(storageSource.contains("read_fs_blocks_to_ram(block, full_block_count, dst)"))
    }

    @Test
    fun k16RootFsReusesMountedRootPartitionForCachedReadPaths() {
        val rootSource = Path.of("../../../guest/kraftos/kernel/src/kfs/root.rs").readText()
        val storageSource = Path.of("../../../guest/kraftos/kernel/src/kfs/storage.rs").readText()
        val fsSource = Path.of("../../../guest/kraftos/kernel/src/fs.rs").readText()

        assertTrue(storageSource.contains("pub unsafe fn mount_root_partition_superblock("))
        assertTrue(rootSource.contains("mounted: Option<crate::kfs::mount::MountedKfs>"))
        assertTrue(rootSource.contains("unsafe fn ensure_mounted("))
        assertTrue(rootSource.contains("crate::kfs::storage::mount_root_partition_superblock(partition_type)"))
        assertEquals(3, rootSource.split("self.ensure_mounted(partition_type)?").size - 1)
        assertFalse(rootSource.contains("read_root_partition_superblock"))

        val openRootFileSource =
            fsSource
                .substringAfter("pub unsafe fn open_root_file_for_process(")
                .substringBefore("pub unsafe fn seek_file_fd_for_process(")
        assertFalse(openRootFileSource.contains("crate::kfs::storage::open_file_from_storage0(ROOT_PARTITION, components.as_slice())"))
        assertTrue(openRootFileSource.contains(".open_file(ROOT_PARTITION, components.as_slice())"))
    }

    @Test
    fun k16StorageUsesBlockCacheForSingleBlockReads() {
        val storageSource = Path.of("../../../guest/kraftos/kernel/src/kfs/storage.rs").readText()

        assertTrue(storageSource.contains("KFS_BLOCK_CACHE_SLOTS"))
        assertTrue(storageSource.contains("static KFS_BLOCK_CACHE: KernelKfsBlockCache"))
        assertTrue(storageSource.contains("read_fs_block_from_cache(block)"))
        assertTrue(storageSource.contains("store_scratch_block_in_cache(block)"))
        assertTrue(storageSource.contains("write_cached_block_to_scratch(bytes)"))
        assertTrue(storageSource.contains("crate::kfs::block_cache::KfsBlockCache"))
    }

    @Test
    fun k16StorageProfilesBlockCacheAndUsesItForBulkReads() {
        val storageSource = Path.of("../../../guest/kraftos/kernel/src/kfs/storage.rs").readText()
        val osStatsSource = Path.of("../../../guest/kraftos/kernel/src/os_stats.rs").readText()
        val runtimeProfilingSource =
            Path
                .of("../../../modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt")
                .readText()

        assertTrue(osStatsSource.contains("block_cache_hits: u64"))
        assertTrue(osStatsSource.contains("block_cache_misses: u64"))
        assertTrue(osStatsSource.contains("block_cache_batch_reads: u64"))
        assertTrue(runtimeProfilingSource.contains("blockCacheHits="))
        assertTrue(runtimeProfilingSource.contains("blockCacheMisses="))
        assertTrue(runtimeProfilingSource.contains("blockCacheBatchReads="))
        assertTrue(storageSource.contains("read_fs_blocks_to_ram_cached"))
        assertTrue(storageSource.contains("copy_cached_block_to_ram(block, dst_cursor)"))
        assertTrue(storageSource.contains("store_ram_blocks_in_cache(block, miss_count, dst_cursor)"))
        assertTrue(storageSource.contains("record_block_cache_hit()"))
        assertTrue(storageSource.contains("record_block_cache_miss()"))
        assertTrue(storageSource.contains("record_block_cache_batch_read()"))
    }

    @Test
    fun k16StorageProfilesRequestedReadBlocksSeparatelyFromMediaReads() {
        val hostStatsSource = Path.of("../../../host/k16-vm/src/computer/stats.rs").readText()
        val hostStorageSource = Path.of("../../../host/k16-vm/src/computer/devices/storage.rs").readText()
        val jniSource = Path.of("../../../host/k16-vm/src/jni.rs").readText()
        val nativeBindingsSource =
            Path
                .of("../../../modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt")
                .readText()
        val runtimeProfilingSource =
            Path
                .of("../../../modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt")
                .readText()

        assertTrue(hostStatsSource.contains("requested_read_blocks: u64"))
        assertTrue(hostStatsSource.contains("requested_read_bytes: u64"))
        assertTrue(hostStorageSource.contains("requested_read_blocks"))
        assertTrue(hostStorageSource.contains("u64::from(self.block_count)"))
        assertTrue(hostStorageSource.contains("u64::from(byte_count)"))
        assertTrue(jniSource.contains("values.push(13)"))
        assertTrue(jniSource.contains("storage.requested_read_blocks"))
        assertTrue(jniSource.contains("storage.requested_read_bytes"))
        assertTrue(nativeBindingsSource.contains("VERSION_V13"))
        assertTrue(nativeBindingsSource.contains("requestedReadBlocks"))
        assertTrue(nativeBindingsSource.contains("requestedReadBytes"))
        assertTrue(runtimeProfilingSource.contains("requestedReadBlocks="))
        assertTrue(runtimeProfilingSource.contains("requestedReadBytes="))
    }
}
