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

package ru.lazyhat.compukterkraft.core.workbench.screen

import ru.lazyhat.compukterkraft.core.ui.editor.EditorViewModel
import ru.lazyhat.compukterkraft.core.ui.editor.SelectionRange
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.HighlightToken

/**
 * Thin adapter that exposes a [WorkbenchStore] as the
 * [EditorViewModel] consumed by `UiElement.CodeEditor`. The adapter holds
 * no state of its own — every read goes through `store.state`, every write
 * delegates back into the store.
 */
class WorkbenchEditorViewModel(
    private val store: WorkbenchStore,
) : EditorViewModel {
    override val text: String
        get() = store.state.editor.text

    override val cursorLine: Int
        get() = store.state.editor.cursorLine

    override val cursorColumn: Int
        get() = store.state.editor.cursorColumn

    override val scrollLine: Int
        get() = store.state.editor.scrollLine

    override val highlights: List<HighlightToken>
        get() =
            store.state.editor.ideSnapshot
                ?.highlights
                .orEmpty()

    override val diagnostics: List<Diagnostic>
        get() =
            store.state.editor.ideSnapshot
                ?.diagnostics
                .orEmpty()

    override val selection: SelectionRange? = null

    override fun onKeyPressed(
        key: Int,
        modifiers: Int,
        visibleLines: Int,
    ): Boolean = store.keyPressed(key, modifiers, visibleLines)

    override fun onCharTyped(
        ch: Char,
        visibleLines: Int,
    ): Boolean = store.charTyped(ch, visibleLines)

    override fun onMouseClickAt(
        line: Int,
        column: Int,
    ) {
        // Visible-lines argument controls scroll-bounds clamping inside the
        // store; we don't have a viewport here, so we pass a generous default.
        store.moveCursorTo(line, column, visibleEditorLines = DEFAULT_VISIBLE_LINES)
    }

    override fun onScroll(deltaLines: Int) {
        store.scrollEditor(deltaLines)
    }

    private companion object {
        const val DEFAULT_VISIBLE_LINES: Int = 64
    }
}
