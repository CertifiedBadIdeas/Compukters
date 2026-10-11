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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.editor.EditorRange

internal object IdePopupText {
    fun wrap(
        value: String,
        columns: Int,
        maximumRows: Int,
    ): List<EditorRange> {
        require(columns > 0 && maximumRows > 0)
        val result = mutableListOf<EditorRange>()
        var start = 0
        while (start < value.length && result.size < maximumRows) {
            val newline = value.indexOf('\n', start).takeIf { it >= 0 } ?: value.length
            var end = start
            var count = 0
            while (end < newline && count < columns) {
                end += Character.charCount(value.codePointAt(end))
                count++
            }
            if (end < newline) {
                val space = value.lastIndexOf(' ', end - 1)
                if (space > start) end = space + 1
            }
            result += EditorRange(start, end)
            start = if (end == newline && newline < value.length) end + 1 else end
        }
        if (value.isEmpty()) result += EditorRange(0, 0)
        return result
    }
}
