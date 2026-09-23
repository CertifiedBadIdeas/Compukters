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

import java.math.BigInteger

data class VmCapacityCalibration(
    val retiredInstructions: Long,
    val elapsedNanos: Long,
    val concurrentWorkers: Int,
) {
    init {
        require(retiredInstructions > 0) { "calibration must retire Guest instructions" }
        require(elapsedNanos > 0) { "calibration duration must be positive" }
        require(concurrentWorkers > 0) { "calibration worker count must be positive" }
    }
}

data class VmCapacityGovernorConfig(
    val hostSharePercent: Int = 5,
    val safetyPercent: Int = 50,
    val fallbackCapacity: Long = 8_192,
    val tickNanos: Long = 50_000_000,
) {
    init {
        require(hostSharePercent in 1..100) { "host share must be 1..100 percent" }
        require(safetyPercent in 1..100) { "safety factor must be 1..100 percent" }
        require(fallbackCapacity > 0) { "fallback capacity must be positive" }
        require(tickNanos > 0) { "tick duration must be positive" }
    }
}

/** Uses measured concurrent throughput directly; the worker count is never multiplied into it. */
class VmCapacityGovernor(
    private val config: VmCapacityGovernorConfig = VmCapacityGovernorConfig(),
) {
    var calibratedCapacity: Long = config.fallbackCapacity
        private set
    var currentCapacity: Long = config.fallbackCapacity
        private set
    var fallbackReason: String? = "calibration pending"
        private set
    var measuredWorkers: Int = 0
        private set
    val hostTimeBudgetNanos: Long =
        BigInteger
            .valueOf(config.tickNanos)
            .multiply(BigInteger.valueOf(config.hostSharePercent.toLong()))
            .divide(BigInteger.valueOf(100))
            .max(BigInteger.ONE)
            .toLong()
    private var overrunStreak = 0
    private var headroomStreak = 0

    fun calibrated(measurement: VmCapacityCalibration) {
        val throughput = BigInteger.valueOf(measurement.retiredInstructions).multiply(BigInteger.valueOf(config.tickNanos))
        val numerator =
            throughput
                .multiply(BigInteger.valueOf(config.hostSharePercent.toLong()))
                .multiply(BigInteger.valueOf(config.safetyPercent.toLong()))
        val denominator = BigInteger.valueOf(measurement.elapsedNanos).multiply(BigInteger.valueOf(10_000))
        val result = numerator.divide(denominator).max(BigInteger.ONE).min(BigInteger.valueOf(Long.MAX_VALUE))
        calibratedCapacity = result.toLong()
        currentCapacity = calibratedCapacity
        measuredWorkers = measurement.concurrentWorkers
        fallbackReason = null
        overrunStreak = 0
        headroomStreak = 0
    }

    fun calibrationFailed(reason: String) {
        require(reason.isNotBlank()) { "calibration failure reason must not be blank" }
        calibratedCapacity = config.fallbackCapacity
        currentCapacity = config.fallbackCapacity
        measuredWorkers = 0
        fallbackReason = reason
        overrunStreak = 0
        headroomStreak = 0
    }

    fun observeTick(
        hadDemand: Boolean,
        deadlineOverrun: Boolean,
    ) {
        if (!hadDemand) {
            overrunStreak = 0
            headroomStreak = 0
            return
        }
        if (deadlineOverrun) {
            headroomStreak = 0
            if (++overrunStreak >= OVERRUN_TICKS) {
                currentCapacity = (currentCapacity - maxOf(1, currentCapacity / 5)).coerceAtLeast(1)
                overrunStreak = 0
            }
        } else {
            overrunStreak = 0
            if (++headroomStreak >= RECOVERY_TICKS) {
                currentCapacity += minOf(maxOf(1, currentCapacity / 20), calibratedCapacity - currentCapacity)
                headroomStreak = 0
            }
        }
    }

    private companion object {
        const val OVERRUN_TICKS = 4
        const val RECOVERY_TICKS = 100
    }
}
