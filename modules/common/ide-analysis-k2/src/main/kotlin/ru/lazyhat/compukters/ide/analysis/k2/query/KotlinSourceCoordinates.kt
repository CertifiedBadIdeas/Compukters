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

/** IntelliJ PSI normalizes CRLF. Edits and cursor positions must use admitted source coordinates. */
internal class KotlinSourceCoordinates(
    source: String,
) {
    private val rawOffsets =
        buildList {
            add(0)
            var index = 0
            while (index < source.length) {
                if (source[index] == '\r' && source.getOrNull(index + 1) == '\n') index++
                index++
                add(index)
            }
        }.toIntArray()

    fun toPsi(offset: Int): Int {
        val index = rawOffsets.binarySearch(offset)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }

    fun toSource(range: EditorRange): EditorRange = EditorRange(rawOffsets[range.startUtf16], rawOffsets[range.endUtf16])
}
