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

package ru.lazyhat.compukters.ide.analysis.k2.query

import java.util.PriorityQueue

internal data class CompletionRank(
    val applicability: Int,
    val prefixQuality: Int,
    val locality: Int,
    val nameUtf8: ByteArray,
    val signatureUtf8: ByteArray,
)

internal val completionRankComparator: Comparator<CompletionRank> =
    Comparator { left, right ->
        compareValuesBy(left, right, { -it.applicability }, { -it.prefixQuality }, { -it.locality })
            .takeIf { it != 0 }
            ?: compareUnsigned(left.nameUtf8, right.nameUtf8).takeIf { it != 0 }
            ?: compareUnsigned(left.signatureUtf8, right.signatureUtf8)
    }

internal class BoundedBest<T>(
    private val capacity: Int,
    private val comparator: Comparator<T>,
) {
    private val worstFirst = PriorityQueue(comparator.reversed())

    fun offer(value: T) {
        if (capacity == 0) return
        if (worstFirst.size < capacity) {
            worstFirst += value
        } else if (comparator.compare(value, worstFirst.element()) < 0) {
            worstFirst.remove()
            worstFirst += value
        }
    }

    fun sorted(): List<T> = worstFirst.sortedWith(comparator)
}

private fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    val common = minOf(left.size, right.size)
    for (index in 0 until common) {
        val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return left.size.compareTo(right.size)
}
