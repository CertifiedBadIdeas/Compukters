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
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus.AMBIGUOUS
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus.FOUND
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus.INVALID_NAME
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerPeripheralLookupTest {
    @Test
    fun `lookup filters other addon providers before resolving a name`() {
        val create = identity("create", 1)
        val other = identity("other", 2)
        val directory =
            PeripheralDeviceDirectory(
                listOf(PeripheralDeviceName(create, "motor"), PeripheralDeviceName(other, "motor")),
            )

        val result = lookupComputerPeripheral("create", "motor", complete(create, other), directory)

        assertEquals(FOUND, result.status)
        assertEquals(create.anchor, result.identity?.anchor)
        assertEquals(create.deviceKey, result.identity?.deviceKey)
    }

    @Test
    fun `lookup reports same-provider ambiguity and invalid names`() {
        val first = identity("create", 1)
        val second = identity("create", 2)
        val directory =
            PeripheralDeviceDirectory(
                listOf(PeripheralDeviceName(first, "motor"), PeripheralDeviceName(second, "motor")),
            )
        val traversal = complete(first, second)

        assertEquals(AMBIGUOUS, lookupComputerPeripheral("create", "motor", traversal, directory).status)
        assertEquals(INVALID_NAME, lookupComputerPeripheral("create", "not a name", traversal, directory).status)
    }

    @Test
    fun `lookup rejects an incomplete bounded traversal`() {
        val traversal = PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CABLES, 4096)

        assertEquals(
            TOPOLOGY_LIMIT_EXCEEDED,
            lookupComputerPeripheral("create", "motor", traversal, PeripheralDeviceDirectory()).status,
        )
    }

    @Test
    fun `reachability follows exact device identity independently of its name`() {
        val device = identity("create", 1)
        val traversal = complete(device)

        assertTrue(isPeripheralReachable(device, traversal))
        assertFalse(isPeripheralReachable(device.copy(deviceKey = "stressometer"), traversal))
        assertFalse(isPeripheralReachable(identity("create", 2), traversal))
        assertFalse(
            isPeripheralReachable(
                device,
                PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CABLES, 4096),
            ),
        )
    }

    private fun complete(vararg identities: PeripheralDeviceIdentity) =
        PeripheralCableTraversal.Complete(emptySet<BlockPos>(), identities.toSet())

    private fun identity(
        providerId: String,
        x: Int,
    ) = PeripheralDeviceIdentity(providerId, "minecraft:overworld", BlockPos(x, 64, 0), "speedometer")
}
