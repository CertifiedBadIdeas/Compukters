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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuxComputerRuntimeFactoryTest {
    @Test
    fun defaultFirmwareResourceTargetsBiosFirmware() {
        assertEquals("firmware/rux-bios.ruxi", RuxComputerRuntimeFactory.DEFAULT_FIRMWARE_RESOURCE)
    }

    @Test
    fun reportsMissingFirmwareResource() {
        assertFailsWith<IllegalStateException> {
            RuxComputerRuntimeFactory.loadFirmwareResource("firmware/missing-rux-image.ruxi")
        }
    }

    @Test
    fun createFactoryAcceptsStorage0PathParameter() {
        val createMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "create" }
        val hasStorage0Path =
            createMethods.any { method ->
                method.parameterTypes.any { parameterType -> parameterType == Path::class.java }
            }

        assertEquals(true, hasStorage0Path)
    }

    @Test
    fun createFromBiosFlashAcceptsOnlyPathInputsForFirmwareAndStorage() {
        val createMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "createFromBiosFlash" }
        val hasBiosFlashAndStorage0Paths =
            createMethods.any { method ->
                val pathParameterCount = method.parameterTypes.count { parameterType -> parameterType == Path::class.java }
                val byteArrayParameterCount = method.parameterTypes.count { parameterType -> parameterType == ByteArray::class.java }
                pathParameterCount >= 2 && byteArrayParameterCount == 0
            }

        assertEquals(true, hasBiosFlashAndStorage0Paths)
    }
}
