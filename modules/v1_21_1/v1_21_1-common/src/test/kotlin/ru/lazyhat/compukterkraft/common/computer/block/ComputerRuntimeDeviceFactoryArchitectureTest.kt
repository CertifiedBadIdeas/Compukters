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
    fun inGameRuxComputerStartsFromPreparedBiosFlashFile() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertTrue(source.contains("K16BiosFlashWorkspace.prepareBiosFlash"))
        assertTrue(source.contains("K16ComputerRuntimeFactory.createFromBiosFlash"))
        assertFalse(source.contains("K16ComputerRuntimeFactory.createFromResource(storage0Path"))
    }

    @Test
    fun inGameRuxComputerSeedsStorage0BeforeOpeningVolume() {
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
    fun inGameRuxComputerRestoresPendingRuntimeSnapshotBeforeFreshBoot() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertTrue(source.contains("tile.consumePendingRuntimeSnapshot()"))
        assertTrue(source.contains("K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot"))
        assertTrue(source.contains("K16ComputerRuntimeFactory.createFromBiosFlash"))
        assertFalse(source.contains("var pendingRuntimeSnapshot"))
    }

    @Test
    fun computerBlockEntityPersistsRuntimeSnapshotBytes() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        assertTrue(source.contains("runtimeSnapshot"))
        assertTrue(source.contains("snapshotRuntimeState()"))
        assertTrue(source.contains("consumePendingRuntimeSnapshot"))
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
