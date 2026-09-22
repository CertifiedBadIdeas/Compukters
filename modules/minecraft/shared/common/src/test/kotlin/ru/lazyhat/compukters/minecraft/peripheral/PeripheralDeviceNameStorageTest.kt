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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeripheralDeviceNameStorageTest {
    @Test
    fun `device names survive the version-specific saved data round trip`() {
        val identity = PeripheralDeviceIdentity("create", "minecraft:overworld", BlockPos(4, 70, -2), "controller")
        val storage = PeripheralDeviceNameStorage()
        assertFalse(storage.isDirty)
        assertEquals(PeripheralCandidateStatus.TARGET, storage.assignName(setOf(identity), identity, "main_motor"))
        assertTrue(storage.isDirty)

        val restored = PeripheralDeviceNameStorageTestPersistence.roundTrip(storage)

        assertEquals("main_motor", restored.directory.nameOf(identity))
    }

    @Test
    fun `conflicting assignment does not dirty saved data`() {
        val target = PeripheralDeviceIdentity("create", "minecraft:overworld", BlockPos(4, 70, -2), "controller")
        val occupied = PeripheralDeviceIdentity("create", "minecraft:overworld", BlockPos(5, 70, -2), "speedometer")
        val storage = PeripheralDeviceNameStorage(PeripheralDeviceDirectory(listOf(PeripheralDeviceName(occupied, "main_motor"))))

        assertEquals(PeripheralCandidateStatus.CONFLICT, storage.assignName(setOf(target, occupied), target, "main_motor"))
        assertFalse(storage.isDirty)
        assertEquals(null, storage.directory.nameOf(target))
    }
}
