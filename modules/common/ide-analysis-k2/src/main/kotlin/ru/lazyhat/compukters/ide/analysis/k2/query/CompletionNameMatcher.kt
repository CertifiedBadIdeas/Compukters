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

import ru.lazyhat.compukters.ide.editor.EditorRange

/** Match ordered contiguous fragments of name words, never skipping letters within a matched fragment. */
internal class CompletionNameMatcher(
    private val pattern: String,
) {
    private val folded = pattern.codePoints().map(Character::toLowerCase).toArray()
    val initial: Int? = folded.firstOrNull()

    fun quality(name: String): Int = match(name, false)?.quality ?: 0

    fun ranges(name: String): List<EditorRange> = match(name, true)?.ranges.orEmpty()

    private fun match(
        name: String,
        capture: Boolean,
    ): Match? {
        fun prefix(quality: Int) =
            Match(
                quality,
                if (capture &&
                    pattern.isNotEmpty()
                ) {
                    listOf(EditorRange(0, pattern.length))
                } else {
                    emptyList()
                },
            )
        if (name == pattern) return prefix(4)
        if (name.startsWith(pattern)) return prefix(3)
        val points = name.codePoints().toArray()
        if (folded.isEmpty() || folded.size > points.size) return null
        if (folded.indices.all { folded[it] == Character.toLowerCase(points[it]) }) return prefix(2)

        var reachable = BooleanArray(folded.size + 1)
        var next = BooleanArray(folded.size + 1)
        val run = BooleanArray(folded.size + 1)
        var paths = if (capture) arrayOfNulls<Node>(folded.size + 1) else null
        var nextPaths = if (capture) arrayOfNulls<Node>(folded.size + 1) else null
        val runParents = if (capture) arrayOfNulls<Node>(folded.size + 1) else null
        val runStarts = if (capture) IntArray(folded.size + 1) else null
        val offsets =
            if (capture) {
                IntArray(points.size + 1).also { values ->
                    for (i in points.indices) values[i + 1] = values[i] + Character.charCount(points[i])
                }
            } else {
                null
            }
        reachable[0] = true
        var start = 0
        while (start < points.size) {
            var end = start + 1
            while (end < points.size && !wordStart(points, end)) end++
            reachable.copyInto(next)
            paths?.copyInto(requireNotNull(nextPaths))
            run.fill(false)
            for (cursor in start until end) {
                val current = Character.toLowerCase(points[cursor])
                // Only earlier words may start a new fragment; this word's matches must stay consecutive.
                for (offset in folded.lastIndex downTo 0) {
                    val continuing = run[offset]
                    run[offset + 1] = (reachable[offset] || run[offset]) && folded[offset] == current
                    if (run[offset + 1]) {
                        if (capture) {
                            requireNotNull(runStarts)[offset + 1] = if (continuing) runStarts[offset] else cursor
                            requireNotNull(runParents)[offset + 1] = if (continuing) runParents[offset] else requireNotNull(paths)[offset]
                            if (!next[offset + 1]) {
                                requireNotNull(nextPaths)[offset + 1] =
                                    Node(
                                        EditorRange(requireNotNull(offsets)[runStarts[offset + 1]], offsets[cursor + 1]),
                                        runParents[offset + 1],
                                    )
                            }
                        }
                        next[offset + 1] = true
                    }
                }
                if (run[folded.size]) {
                    val ranges = generateSequence(nextPaths?.get(folded.size)) { it.previous }.map { it.range }.toList().asReversed()
                    // Adjacent fragments from neighboring words are one visual run.
                    val merged = mutableListOf<EditorRange>()
                    for (range in ranges) {
                        val last = merged.lastOrNull()
                        if (last?.endUtf16 == range.startUtf16) {
                            merged[merged.lastIndex] = EditorRange(last.startUtf16, range.endUtf16)
                        } else {
                            merged += range
                        }
                    }
                    return Match(1, merged)
                }
            }
            val previous = reachable
            reachable = next
            next = previous
            val previousPaths = paths
            paths = nextPaths
            nextPaths = previousPaths
            start = end
        }
        return null
    }

    private data class Match(
        val quality: Int,
        val ranges: List<EditorRange>,
    )

    private data class Node(
        val range: EditorRange,
        val previous: Node?,
    )

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
