package ru.lazyhat.kraftui.editor

/**
 * View-model contract used by [ru.lazyhat.kraftui.foundation.UiElement.CodeEditor].
 *
 * The CodeEditor is model-driven: it never owns text, cursor or scroll state.
 * Hosts wire concrete state through an adapter and delegate input events to
 * the methods below.
 */
interface EditorViewModel {
    val text: String
    val cursorLine: Int
    val cursorColumn: Int
    val scrollLine: Int
    val highlights: List<HighlightToken>
    val diagnostics: List<Diagnostic>
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

    fun onScroll(deltaLines: Int)
}

data class SourcePosition(
    val line: Int,
    val column: Int,
)

data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
)

enum class HighlightTokenKind {
    KEYWORD,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    IDENTIFIER,
    FUNCTION,
    TYPE,
    MODULE,
    FIELD,
    OPERATOR,
    PUNCTUATION,
}

data class HighlightToken(
    val range: SourceRange,
    val kind: HighlightTokenKind,
)

data class Diagnostic(
    val message: String,
    val severity: DiagnosticSeverity,
    val range: SourceRange? = null,
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

data class SelectionRange(
    val start: Int,
    val endExclusive: Int,
)
