/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.lang.runtime

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceVmModelsArchitectureTest {
    @Test
    fun cpuBudgetModelUsesStepBasedNames() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukters/lang/runtime/DeviceVmModels.kt")
                .readText()

        assertTrue(source.contains("val maxStepsPerSlice: Long"))
        assertTrue(source.contains("val maxTurnsPerTick: Int"))
        assertTrue(source.contains("DeviceCpuResources(maxStepsPerSlice = maxStepsPerSlice, maxTurnsPerTick = 8)"))
        assertFalse(source.contains("wallTimeGuardNanosPerSlice"))
        assertFalse(source.contains("cpuBudgetNanosPerSlice"))
    }
}
