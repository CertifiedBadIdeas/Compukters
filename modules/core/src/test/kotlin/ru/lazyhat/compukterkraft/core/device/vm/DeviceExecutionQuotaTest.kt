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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceExecutionQuotaTest {
    @Test
    fun refillCapsPendingQuotaAtSingleTickBudget() =
        runBlocking {
            val quota = DeviceExecutionQuota()

            assertTrue(quota.refill(selectedPid = 2))
            assertFalse(quota.refill(selectedPid = 2))

            quota.awaitPermit(processId = 2)

            assertTrue(quota.refill(selectedPid = 2))
        }

    @Test
    fun refillDoesNotAddQuotaWhenNoProcessIsSelected() {
        val quota = DeviceExecutionQuota()

        assertFalse(quota.refill(selectedPid = null))
    }

    @Test
    fun awaitPermitResumesWhenQuotaArrives() =
        runBlocking {
            val quota = DeviceExecutionQuota()
            val waiter = async { quota.awaitPermit(processId = 2) }

            assertFalse(waiter.isCompleted)
            assertTrue(quota.refill(selectedPid = 2))

            withTimeout(1_000) {
                waiter.await()
            }
        }

    @Test
    fun awaitPermitDoesNotResumeDifferentProcess() =
        runBlocking {
            val quota = DeviceExecutionQuota()
            val waiter = async { quota.awaitPermit(processId = 3) }

            assertFalse(waiter.isCompleted)
            assertTrue(quota.refill(selectedPid = 2))
            assertFalse(waiter.isCompleted)

            assertFalse(quota.refill(selectedPid = 3))
            quota.awaitPermit(processId = 2)
            assertTrue(quota.refill(selectedPid = 3))

            withTimeout(1_000) {
                waiter.await()
            }
        }
}
