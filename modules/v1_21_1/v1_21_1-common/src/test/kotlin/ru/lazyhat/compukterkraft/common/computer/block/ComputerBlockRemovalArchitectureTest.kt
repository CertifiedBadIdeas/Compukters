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
import kotlin.test.assertTrue

class ComputerBlockRemovalArchitectureTest {
    @Test
    fun closingRuntimeCannotRecreateBlockDuringRemoval() {
        val abstractBlockEntitySource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()
        val blockEntitySource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt")
                .readText()

        assertTrue(
            abstractBlockEntitySource.contains("runtimeBlockStateUpdatesEnabled = false"),
            "Runtime close callbacks must be blocked before the device is closed during block removal.",
        )
        assertTrue(
            blockEntitySource.contains("canApplyRuntimeBlockStateUpdate(currentState)"),
            "Runtime state updates must check the live world block before calling level.setBlock.",
        )
    }
}
