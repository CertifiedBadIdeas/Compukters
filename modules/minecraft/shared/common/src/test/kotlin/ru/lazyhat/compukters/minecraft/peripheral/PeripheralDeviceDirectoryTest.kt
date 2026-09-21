/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeripheralDeviceDirectoryTest {
    @Test
    fun `normalizes names and supports rename and clear`() {
        val directory = PeripheralDeviceDirectory()
        val device = device("create", 1)

        assertEquals("main_motor", directory.setName(device, "  Main_Motor  "))
        assertEquals("main_motor", directory.nameOf(device))
        assertEquals("backup-1", directory.setName(device, "backup-1"))
        assertEquals("backup-1", directory.clearName(device))
        assertEquals(null, directory.nameOf(device))
    }

    @Test
    fun `rejects empty malformed and oversized names`() {
        val directory = PeripheralDeviceDirectory()
        val device = device("create", 1)

        listOf("", "123motor", "two words", "motor!", "m".repeat(33)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { directory.setName(device, invalid) }
        }
    }

    @Test
    fun `lookup is scoped to reachable identities`() {
        val directory = PeripheralDeviceDirectory()
        val local = device("create", 1)
        val remote = device("create", 2)
        directory.setName(local, "motor")
        directory.setName(remote, "motor")

        assertEquals(PeripheralNameLookup.Found(local), directory.lookup("MOTOR", listOf(local)))
        assertEquals(PeripheralNameLookup.Missing, directory.lookup("motor", emptyList()))
    }

    @Test
    fun `duplicate reachable names are explicitly ambiguous`() {
        val directory = PeripheralDeviceDirectory()
        val first = device("create", 1)
        val second = device("create", 2)
        directory.setName(first, "motor")
        directory.setName(second, "motor")

        assertEquals(
            PeripheralNameLookup.Ambiguous(listOf(first, second)),
            directory.lookup("motor", listOf(first, second)),
        )
    }

    @Test
    fun `snapshot restores exact identities and names`() {
        val first = device("create", 1, "main")
        val second = device("example", 2)
        val restored =
            PeripheralDeviceDirectory(
                listOf(
                    PeripheralDeviceName(first, "motor"),
                    PeripheralDeviceName(second, "gauge"),
                ),
            )

        assertEquals(
            listOf(PeripheralDeviceName(first, "motor"), PeripheralDeviceName(second, "gauge")),
            restored.snapshot(),
        )
    }

    private fun device(
        provider: String,
        x: Int,
        key: String = "",
    ) = PeripheralDeviceIdentity(provider, "minecraft:overworld", BlockPos(x, 64, 0), key)
}
