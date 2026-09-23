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

import java.util.concurrent.atomic.AtomicLongArray

/** Fixed-size lifetime histogram; percentiles are rounded up to the end of their power-of-two nanosecond bucket. */
internal class VmActorLatencyHistogram {
    private val buckets = AtomicLongArray(BUCKETS)

    fun record(nanos: Long) {
        require(nanos >= 0) { "actor latency must not be negative" }
        val bucket = if (nanos == 0L) 0 else Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(nanos)
        buckets.getAndUpdate(bucket) { previous -> if (previous == Long.MAX_VALUE) previous else previous + 1 }
    }

    fun snapshot(): VmActorLatencyPercentiles {
        val counts = LongArray(BUCKETS) { buckets.get(it) }
        val total = counts.fold(0L, ::addSaturating)
        if (total == 0L) return VmActorLatencyPercentiles(0, 0)
        return VmActorLatencyPercentiles(
            medianNanos = percentile(counts, rank(total, 50)),
            p95Nanos = percentile(counts, rank(total, 95)),
        )
    }

    private fun percentile(
        counts: LongArray,
        rank: Long,
    ): Long {
        var seen = 0L
        counts.forEachIndexed { bucket, count ->
            seen = addSaturating(seen, count)
            if (seen >= rank) {
                return when (bucket) {
                    0 -> 0
                    BUCKETS - 1 -> Long.MAX_VALUE
                    else -> (1L shl bucket) - 1
                }
            }
        }
        return Long.MAX_VALUE
    }

    private companion object {
        const val BUCKETS = Long.SIZE_BITS

        fun rank(
            total: Long,
            percent: Int,
        ): Long = (total / 100) * percent + ((total % 100) * percent + 99) / 100

        fun addSaturating(
            first: Long,
            second: Long,
        ): Long = if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
    }
}

internal data class VmActorLatencyPercentiles(
    val medianNanos: Long,
    val p95Nanos: Long,
)
