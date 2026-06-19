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

package ru.lazyhat.compukterkraft.common.computer.block

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerRuntimeDeviceFactoryArchitectureTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun inGameK16ComputerStartsFromPreparedBiosFlashFile() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertTrue(source.contains("K16BiosFlashWorkspace.prepareBiosFlash"))
        assertTrue(source.contains("K16ComputerRuntimeFactory.createFromBiosFlash"))
        assertFalse(source.contains("K16ComputerRuntimeFactory.createFromResource(storage0Path"))
    }

    @Test
    fun inGameK16ComputerSeedsStorage0BeforeOpeningVolume() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val seedIndex = source.indexOf("K16SystemVolumeWorkspace.prepareStorage0Volume(workspace)")
        val openIndex = source.indexOf("volumeStore.openOrCreateComputerVolume(deviceId, \"storage0\")")

        assertTrue(source.contains("FileK16VolumeStore(worldRoot)"))
        assertTrue(seedIndex >= 0, "storage0 should be seeded from the bundled system volume resource")
        assertTrue(openIndex >= 0, "storage0 should still be opened through FileK16VolumeStore")
        assertTrue(seedIndex < openIndex, "storage0 must be seeded before FileK16VolumeStore can create an empty volume")
    }

    @Test
    fun inGameK16ComputerRestoresPendingRuntimeSnapshotBeforeFreshBoot() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val consumeIndex = source.indexOf("tile.consumePendingRuntimeSnapshot()")
        val endpointIndex = source.indexOf("createK16ComputerEndpoint(biosFlashPath, storage0, snapshot, memorySize, maxSteps)")
        val restoreIndex = source.indexOf("K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot")
        val freshBootIndex = source.indexOf("K16ComputerRuntimeFactory.createFromBiosFlash")

        assertTrue(consumeIndex >= 0, "factory should consume pending runtime snapshot before endpoint creation")
        assertTrue(endpointIndex > consumeIndex, "factory should pass the consumed snapshot into endpoint creation")
        assertTrue(restoreIndex >= 0, "factory should expose a restore branch")
        assertTrue(freshBootIndex >= 0, "factory should keep a fresh boot branch")
        assertTrue(restoreIndex > freshBootIndex, "restore should remain the non-null snapshot branch after fresh boot")
        assertFalse(source.contains("var pendingRuntimeSnapshot"))
    }

    @Test
    fun inGameK16ComputerUsesDeviceProfileRamForFreshBootAndRestore() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val profileIndex = source.indexOf("DeviceProfileRegistry.forFamily(tile.family)")
        val ramIndex = source.indexOf("profile.resources.memory.vmRamBytes")
        val endpointIndex = source.indexOf("createK16ComputerEndpoint(biosFlashPath, storage0, snapshot, memorySize, maxSteps)")
        val freshBootMemoryIndex =
            source.indexOf("memorySize = memorySize", source.indexOf("K16ComputerRuntimeFactory.createFromBiosFlash"))
        val restoreMemoryIndex =
            source.indexOf("memorySize = memorySize", source.indexOf("K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot"))

        assertTrue(profileIndex >= 0, "factory should resolve the active device profile from the block family")
        assertTrue(ramIndex > profileIndex, "factory should derive K16 RAM from profile memory resources")
        assertTrue(endpointIndex > ramIndex, "factory should pass profile RAM into endpoint creation")
        assertTrue(freshBootMemoryIndex > endpointIndex, "fresh K16 boot should use profile RAM")
        assertTrue(restoreMemoryIndex > freshBootMemoryIndex, "K16 restore should use the same profile RAM")
    }

    @Test
    fun inGameK16ComputerUsesDeviceProfileCpuBudgetForFreshBoot() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val profileIndex = source.indexOf("DeviceProfileRegistry.forFamily(tile.family)")
        val budgetIndex = source.indexOf("profile.resources.cpu.wallTimeGuardNanosPerSlice")
        val endpointIndex = source.indexOf("createK16ComputerEndpoint(biosFlashPath, storage0, snapshot, memorySize, maxSteps)")
        val freshBootBudgetIndex =
            source.indexOf("maxSteps = maxSteps", source.indexOf("K16ComputerRuntimeFactory.createFromBiosFlash"))

        assertTrue(profileIndex >= 0, "factory should resolve the active device profile from the block family")
        assertTrue(budgetIndex > profileIndex, "factory should derive K16 CPU budget from profile CPU resources")
        assertTrue(endpointIndex > budgetIndex, "factory should pass profile CPU budget into endpoint creation")
        assertTrue(freshBootBudgetIndex > endpointIndex, "fresh K16 boot should use profile CPU budget")
    }

    @Test
    fun inGameK16ComputerFallsBackToDurableRuntimeSnapshotBeforeFreshBoot() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val storeIndex = source.indexOf("K16RuntimeSnapshotStore(worldRoot)")
        val consumeIndex = source.indexOf("tile.consumePendingRuntimeSnapshot()")
        val durableReadIndex = source.indexOf("snapshotStore.readComputerSnapshotOrNull(deviceId)")
        val endpointIndex = source.indexOf("createK16ComputerEndpoint(biosFlashPath, storage0, snapshot, memorySize, maxSteps)")

        assertTrue(storeIndex >= 0, "factory should create a runtime snapshot store from the world root")
        assertTrue(consumeIndex >= 0, "factory should still prefer pending NBT snapshots first")
        assertTrue(durableReadIndex > consumeIndex, "factory should read durable snapshot only after pending NBT is absent")
        assertTrue(endpointIndex > durableReadIndex, "factory should pass the resolved snapshot into endpoint creation")
    }

    @Test
    fun inGameFactoryExposesK16ComputerNameWithoutK16ComputerAlias() {
        val factorySource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()
        val notebookSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlockEntity.kt")
                .readText()

        assertTrue(factorySource.contains("fun createK16Computer("))
        assertTrue(notebookSource.contains("ComputerRuntimeDeviceFactory.createK16Computer("))
        assertFalse(factorySource.contains("createRuxComputer"))
        assertFalse(notebookSource.contains("createRuxComputer"))
    }

    @Test
    fun computerBlockEntityPersistsRuntimeSnapshotBytes() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        val saveIndex = source.indexOf("tag.runtimeSnapshot = runtimeSnapshotForSave()")
        val loadIndex = source.indexOf("pendingRuntimeSnapshot = tag.runtimeSnapshot?.copyOf()")
        val runtimeSnapshotIndex = source.indexOf("private fun runtimeSnapshotForSave(): ByteArray?")

        assertTrue(saveIndex >= 0, "saveAdditional should write runtime snapshot bytes into NBT")
        assertTrue(loadIndex >= 0, "loadAdditional should copy NBT snapshot bytes into pending state")
        assertTrue(runtimeSnapshotIndex >= 0, "block entity should centralize snapshot selection for save")
        assertTrue(source.contains("?.snapshotRuntimeState()"), "saving should prefer the live runtime snapshot when present")
        assertTrue(source.contains("?: pendingRuntimeSnapshot?.copyOf()"), "saving should preserve pending snapshot bytes when runtime is absent")
        assertTrue(source.contains("consumePendingRuntimeSnapshot"))
    }

    @Test
    fun computerBlockEntityPersistsRuntimeSnapshotBytesToDurableStore() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        val snapshotStoreIndex = source.indexOf("private fun runtimeSnapshotStore(): K16RuntimeSnapshotStore?")
        val writeMethodIndex = source.indexOf("private fun persistRuntimeSnapshot(snapshot: ByteArray)")
        val liveSaveIndex = source.indexOf("persistRuntimeSnapshot(snapshot)", source.indexOf("private fun runtimeSnapshotForSave()"))
        val releaseIndex = source.indexOf("persistRuntimeSnapshot(it)", source.indexOf("protected fun releaseRuntimeDevice()"))

        assertTrue(snapshotStoreIndex >= 0, "block entity should resolve a durable runtime snapshot store from the server level")
        assertTrue(writeMethodIndex >= 0, "block entity should centralize durable runtime snapshot writes")
        assertTrue(liveSaveIndex >= 0, "saving a live runtime snapshot should write the durable snapshot store")
        assertTrue(releaseIndex >= 0, "capturing an unload snapshot should write the durable snapshot store")
    }

    @Test
    fun computerBlockEntityConsumesPendingRuntimeSnapshotOnce() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        val methodIndex = source.indexOf("internal fun consumePendingRuntimeSnapshot(): ByteArray?")
        val copyIndex = source.indexOf("pendingRuntimeSnapshot?.copyOf()", methodIndex)
        val clearIndex = source.indexOf("pendingRuntimeSnapshot = null", methodIndex)

        assertTrue(methodIndex >= 0, "block entity should expose pending snapshot consumption to the factory")
        assertTrue(copyIndex > methodIndex, "consume should return a defensive copy")
        assertTrue(clearIndex > copyIndex, "consume should clear pending snapshot after copying it")
    }

    @Test
    fun runtimeSnapshotNbtKeyUsesK16Name() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt")
                .readText()

        assertTrue(source.contains("K16RuntimeSnapshot"))
        assertFalse(source.contains("RuxRuntimeSnapshot"))
    }

    @Test
    fun computerBlockEntityMarksChunkDirtyAfterCapturingUnloadSnapshot() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        val snapshotIndex = source.indexOf("pendingRuntimeSnapshot = it.copyOf()")
        val dirtyIndex = source.indexOf("setChanged()", snapshotIndex)

        assertTrue(snapshotIndex >= 0, "releaseRuntimeDevice should capture a pending runtime snapshot.")
        assertTrue(
            dirtyIndex > snapshotIndex,
            "releaseRuntimeDevice should mark the block entity dirty after capturing the unload snapshot.",
        )
    }

    @Test
    fun runtimeStartupFailureIsVisibleToComputerMenuAndNotebookScreen() {
        val runtimeSource =
            root
                .resolve("modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt")
                .readText()
        val menuSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt")
                .readText()
        val notebookScreenSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt")
                .readText()

        assertTrue(runtimeSource.contains("RuntimeDeviceFailureState"))
        assertTrue(menuSource.contains("hasComputerRuntimeFailure"))
        assertTrue(notebookScreenSource.contains("FAILED(\"ERROR\")"))
    }
}
