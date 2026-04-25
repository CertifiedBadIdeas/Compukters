package ru.lazyhat.compukterkraft.core.ui.editor

import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.HighlightToken

/**
 * View-model contract used by [ru.lazyhat.compukterkraft.core.ui.foundation.UiElement.CodeEditor].
 *
 * The CodeEditor is intentionally model-driven: it never owns text, cursor or
 * scroll state. Hosts wire concrete state (e.g. a `WorkbenchStore`) through
 * an adapter and delegate input events to the methods below.
 *
 * Event methods return `true` when they consumed the event so the host UI
 * can stop propagation.
 */
interface EditorViewModel {
    val text: String
    val cursorLine: Int
    val cursorColumn: Int

    /**
     * The first line of text currently visible in the editor's viewport.
     * The CodeEditor uses this to translate stored content into pixels.
     */
    val scrollLine: Int

    val highlights: List<HighlightToken>
    val diagnostics: List<Diagnostic>

    /**
     * Currently-selected range, or `null` when nothing is selected. Always
     * `null` until selection editing lands (see plan 2026-04-25, task 2.x).
     */
    val selection: SelectionRange?

    fun onKeyPressed(
        key: Int,
        modifiers: Int,
        visibleLines: Int,
    ): Boolean

    fun onCharTyped(
        ch: Char,
        visibleLines: Int,
    ): Boolean

    fun onMouseClickAt(
        line: Int,
        column: Int,
    )

    /**
     * Receives the raw mouse-wheel delta. Implementations decide how many
     * lines to advance and call back into themselves.
     */
    fun onScroll(deltaLines: Int)
}

/**
 * Inclusive-start, exclusive-end character offsets into [EditorViewModel.text].
 */
data class SelectionRange(
    val start: Int,
    val endExclusive: Int,
)
