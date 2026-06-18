package ru.lazyhat.kraftui.editor

/**
 * Pixel-level helpers for the [UiElement.CodeEditor][ru.lazyhat.kraftui.foundation.UiElement.CodeEditor].
 *
 * The compiler and every render backend have to agree on a few derived
 * quantities (gutter width, glyph offsets, etc.). They live here so backends
 * cannot drift from the compiler's hit-test logic.
 */
object CodeEditorMetrics {
    /** Right-padding (in characters) between gutter digits and the text body. */
    const val GUTTER_PADDING_CHARS: Int = 1

    /** Returns the pixel width of the gutter for a buffer with [lineCount] lines. */
    fun gutterPixelWidth(
        lineCount: Int,
        fontWidth: Int,
    ): Int {
        val digits = digitsForLineCount(lineCount)
        return (digits + GUTTER_PADDING_CHARS) * fontWidth
    }

    /** How many decimal digits are needed to display [lineCount] line numbers. */
    fun digitsForLineCount(lineCount: Int): Int {
        if (lineCount <= 0) return 1
        var n = lineCount
        var digits = 0
        while (n > 0) {
            digits++
            n /= 10
        }
        return digits
    }

    /** Counts logical lines in [text] (lines are separated by `\n`). */
    fun lineCount(text: String): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
}
