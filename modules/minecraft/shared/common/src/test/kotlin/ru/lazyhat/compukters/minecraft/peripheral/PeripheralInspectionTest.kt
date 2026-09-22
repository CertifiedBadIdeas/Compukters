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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeripheralInspectionTest {
    @Test
    fun `projects stable bounded entries and marks duplicate names`() {
        val first = device("create", "speedometer", 3)
        val second = device("create", "controller", 2)
        val third = device("other", "gauge", 1)
        val directory =
            PeripheralDeviceDirectory(
                listOf(
                    PeripheralDeviceName(first, "shared"),
                    PeripheralDeviceName(second, "motor"),
                    PeripheralDeviceName(third, "shared"),
                ),
            )

        val inspection =
            assertIs<PeripheralInspection.Complete>(
                inspectPeripheralComponent(complete(first, second, third), directory, second, "motor", maximumEntries = 2),
            )

        assertEquals(listOf("motor", "shared"), inspection.entries.map(PeripheralInspectionEntry::name))
        assertFalse(inspection.entries.first().duplicate)
        assertTrue(inspection.entries.last().duplicate)
        assertEquals(3, inspection.totalNamedDevices)
        assertTrue(inspection.truncated)
        assertEquals(mapOf("motor" to 1, "shared" to 2), inspection.nameCounts)
        assertEquals("motor", inspection.targetName)
        assertEquals(PeripheralCandidateStatus.TARGET, inspection.candidateStatus)
    }

    @Test
    fun `candidate and assignment distinguish invalid free owned and conflicting names`() {
        val target = device("create", "controller", 1)
        val occupied = device("create", "speedometer", 2)
        val directory = PeripheralDeviceDirectory(listOf(PeripheralDeviceName(target, "motor"), PeripheralDeviceName(occupied, "input")))
        val traversal = complete(target, occupied)

        assertEquals(PeripheralCandidateStatus.INVALID, complete(traversal, directory, target, "not a name").candidateStatus)
        assertEquals(PeripheralCandidateStatus.FREE, complete(traversal, directory, target, "output").candidateStatus)
        assertEquals(PeripheralCandidateStatus.TARGET, complete(traversal, directory, target, "MOTOR").candidateStatus)
        assertEquals(PeripheralCandidateStatus.CONFLICT, complete(traversal, directory, target, "input").candidateStatus)
        assertEquals(PeripheralCandidateStatus.CONFLICT, assignPeripheralName(directory, traversal.contacts, target, "input"))
        assertEquals("motor", directory.nameOf(target))
        assertEquals(PeripheralCandidateStatus.TARGET, assignPeripheralName(directory, traversal.contacts, target, "output"))
        assertEquals("output", directory.nameOf(target))
    }

    @Test
    fun `propagates topology limit without partial entries`() {
        assertEquals(
            PeripheralInspection.LimitExceeded(PeripheralCableLimit.CONTACTS, 12),
            inspectPeripheralComponent(
                PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CONTACTS, 12),
                PeripheralDeviceDirectory(),
                null,
                "motor",
            ),
        )
    }

    private fun complete(
        traversal: PeripheralCableTraversal.Complete<BlockPos, PeripheralDeviceIdentity>,
        directory: PeripheralDeviceDirectory,
        target: PeripheralDeviceIdentity?,
        candidate: String,
    ) = assertIs<PeripheralInspection.Complete>(inspectPeripheralComponent(traversal, directory, target, candidate))

    private fun complete(vararg devices: PeripheralDeviceIdentity) =
        PeripheralCableTraversal.Complete(emptySet<BlockPos>(), devices.toSet())

    private fun device(
        provider: String,
        key: String,
        x: Int,
    ) = PeripheralDeviceIdentity(provider, "minecraft:overworld", BlockPos(x, 64, 0), key)
}
