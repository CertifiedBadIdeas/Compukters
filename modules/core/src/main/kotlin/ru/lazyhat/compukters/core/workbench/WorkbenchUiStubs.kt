/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.core.workbench

import ru.lazyhat.compukters.lang.runtime.HighlightTokenKind

/**
 * UI mode toggled by the workbench screen between terminal interaction and
 * source editing. Kept after the CKL workbench removal because the
 * `WorkbenchTerminalInteractionPolicy` still uses it.
 */
enum class WorkbenchMode {
    TERMINAL,
    EDITOR,
}

/**
 * Stable color palette used by the editor to colorize highlight tokens.
 */
fun highlightColor(kind: HighlightTokenKind): Int =
    when (kind) {
        HighlightTokenKind.KEYWORD -> 0x8EC5FF
        HighlightTokenKind.STRING -> 0xD9C27C
        HighlightTokenKind.NUMBER -> 0xC6A0F6
        HighlightTokenKind.BOOLEAN -> 0xC6A0F6
        HighlightTokenKind.NULL -> 0xC6A0F6
        HighlightTokenKind.IDENTIFIER -> 0xE6ECF5
        HighlightTokenKind.FUNCTION -> 0x8BD5CA
        HighlightTokenKind.TYPE -> 0xF5B971
        HighlightTokenKind.MODULE -> 0x7FC1FF
        HighlightTokenKind.FIELD -> 0xA8D68F
        HighlightTokenKind.OPERATOR -> 0xE6ECF5
        HighlightTokenKind.PUNCTUATION -> 0xB0B8C5
    }
