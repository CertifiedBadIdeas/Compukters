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

package ru.lazyhat.compukters.core.device.runtime.actor

import kotlin.test.Test
import kotlin.test.assertEquals

class VmCapacityGovernorTest {
    @Test
    fun `calibration uses measured concurrent work once and applies host share`() {
        val governor = VmCapacityGovernor(VmCapacityGovernorConfig(hostSharePercent = 10, safetyPercent = 50))
        governor.calibrated(VmCapacityCalibration(1_000_000, 100_000_000, concurrentWorkers = 4))

        assertEquals(25_000, governor.currentCapacity)
        assertEquals(4, governor.measuredWorkers)
        assertEquals(null, governor.fallbackReason)
    }

    @Test
    fun `failure retains bounded fallback and counters saturate explicitly`() {
        val governor = VmCapacityGovernor()
        governor.calibrationFailed("native sample unavailable")
        assertEquals(8_192, governor.currentCapacity)
        assertEquals("native sample unavailable", governor.fallbackReason)

        governor.calibrated(VmCapacityCalibration(Long.MAX_VALUE, 1, 1))
        assertEquals(Long.MAX_VALUE, governor.currentCapacity)
    }

    @Test
    fun `governor needs sustained pressure and restores slowly`() {
        val governor = VmCapacityGovernor(VmCapacityGovernorConfig(hostSharePercent = 100, safetyPercent = 100, fallbackCapacity = 1))
        governor.calibrated(VmCapacityCalibration(10_000, 50_000_000, 1))
        repeat(3) { governor.observeTick(hadDemand = true, deadlineOverrun = true) }
        assertEquals(10_000, governor.currentCapacity)
        governor.observeTick(hadDemand = true, deadlineOverrun = true)
        assertEquals(8_000, governor.currentCapacity)
        repeat(99) { governor.observeTick(hadDemand = true, deadlineOverrun = false) }
        assertEquals(8_000, governor.currentCapacity)
        governor.observeTick(hadDemand = true, deadlineOverrun = false)
        assertEquals(8_400, governor.currentCapacity)
        repeat(100) { governor.observeTick(hadDemand = false, deadlineOverrun = false) }
        assertEquals(8_400, governor.currentCapacity)
    }

    @Test
    fun `governor keeps a conservative progress floor under persistent deadline misses`() {
        val governor = VmCapacityGovernor(VmCapacityGovernorConfig(hostSharePercent = 100, safetyPercent = 100))
        governor.calibrated(VmCapacityCalibration(100_000, 50_000_000, 8))
        repeat(200) { governor.observeTick(hadDemand = true, deadlineOverrun = true) }
        assertEquals(8_192, governor.currentCapacity)

        governor.calibrated(VmCapacityCalibration(100, 50_000_000, 8))
        repeat(200) { governor.observeTick(hadDemand = true, deadlineOverrun = true) }
        assertEquals(100, governor.currentCapacity)
    }
}
