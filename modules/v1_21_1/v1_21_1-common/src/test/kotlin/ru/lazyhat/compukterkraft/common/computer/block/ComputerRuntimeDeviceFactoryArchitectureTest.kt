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
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerRuntimeDeviceFactoryArchitectureTest {
    @Test
    fun inGameRuxComputerStartsFromPreparedBiosFlashFile() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertTrue(source.contains("RuxBiosFlashWorkspace.prepareBiosFlash"))
        assertTrue(source.contains("RuxComputerRuntimeFactory.createFromBiosFlash"))
        assertFalse(source.contains("RuxComputerRuntimeFactory.createFromResource(storage0Path"))
    }

    @Test
    fun inGameRuxComputerSeedsStorage0BeforeOpeningVolume() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        val seedIndex = source.indexOf("RuxSystemVolumeWorkspace.prepareStorage0Volume(workspace)")
        val openIndex = source.indexOf("volumeStore.openOrCreateComputerVolume(deviceId, \"storage0\")")

        assertTrue(seedIndex >= 0, "storage0 should be seeded from the bundled system volume resource")
        assertTrue(openIndex >= 0, "storage0 should still be opened through FileRuxVolumeStore")
        assertTrue(seedIndex < openIndex, "storage0 must be seeded before FileRuxVolumeStore can create an empty volume")
    }
}
