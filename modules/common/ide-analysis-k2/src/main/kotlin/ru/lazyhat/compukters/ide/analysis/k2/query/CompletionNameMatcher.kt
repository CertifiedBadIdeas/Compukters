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

/** Match ordered contiguous fragments of name words, never skipping letters within a matched fragment. */
internal class CompletionNameMatcher(
    private val pattern: String,
) {
    private val folded = pattern.codePoints().map(Character::toLowerCase).toArray()
    val initial: Int? = folded.firstOrNull()

    fun quality(name: String): Int {
        if (name == pattern) return 4
        if (name.startsWith(pattern)) return 3
        val points = name.codePoints().toArray()
        if (folded.isEmpty() || folded.size > points.size) return 0
        if (folded.indices.all { folded[it] == Character.toLowerCase(points[it]) }) return 2

        var reachable = BooleanArray(folded.size + 1)
        var next = BooleanArray(folded.size + 1)
        val run = BooleanArray(folded.size + 1)
        reachable[0] = true
        var start = 0
        while (start < points.size) {
            var end = start + 1
            while (end < points.size && !wordStart(points, end)) end++
            reachable.copyInto(next)
            run.fill(false)
            for (cursor in start until end) {
                val current = Character.toLowerCase(points[cursor])
                // Only earlier words may start a new fragment; this word's matches must stay consecutive.
                for (offset in folded.lastIndex downTo 0) {
                    run[offset + 1] = (reachable[offset] || run[offset]) && folded[offset] == current
                    if (run[offset + 1]) next[offset + 1] = true
                }
                if (run[folded.size]) return 1
            }
            val previous = reachable
            reachable = next
            next = previous
            start = end
        }
        return 0
    }

    private fun wordStart(
        points: IntArray,
        index: Int,
    ): Boolean {
        val previous = points[index - 1]
        val current = points[index]
        return !Character.isLetterOrDigit(previous) ||
            (
                Character.isUpperCase(current) &&
                    (
                        Character.isLowerCase(previous) ||
                            (Character.isUpperCase(previous) && points.getOrNull(index + 1)?.let(Character::isLowerCase) == true)
                    )
            ) ||
            (Character.isDigit(current) && !Character.isDigit(previous)) ||
            (Character.isDigit(previous) && Character.isLetter(current))
    }
}
