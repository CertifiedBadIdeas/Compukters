package ru.lazyhat.compuktercraft.scripting.impl

import ru.lazyhat.compuktercraft.scripting.api.CompletionItemKind
import ru.lazyhat.compuktercraft.scripting.api.DefinitionTarget
import ru.lazyhat.compuktercraft.scripting.api.HighlightToken
import ru.lazyhat.compuktercraft.scripting.api.HighlightTokenType
import ru.lazyhat.compuktercraft.scripting.api.Position
import ru.lazyhat.compuktercraft.scripting.api.Range

internal data class WordSelection(
    val word: String,
    val range: Range,
)

internal data class DeclarationMatch(
    val name: String,
    val kind: CompletionItemKind,
    val range: Range,
)

internal class TextDocument(private val text: String) {
    private val lines = text.split('\n')

    fun wordAt(
        line: Int,
        column: Int,
    ): WordSelection? {
        val lineText = lines.getOrNull(line) ?: return null
        if (lineText.isEmpty()) return null

        val safeColumn = column.coerceIn(0, lineText.length)
        var start = safeColumn
        var end = safeColumn

        while (start > 0 && lineText[start - 1].isIdentifierPart()) start--
        while (end < lineText.length && lineText[end].isIdentifierPart()) end++

        if (start == end) return null

        return WordSelection(
            word = lineText.substring(start, end),
            range = Range(Position(line, start), Position(line, end)),
        )
    }

    fun prefixAt(
        line: Int,
        column: Int,
    ): String {
        val lineText = lines.getOrNull(line) ?: return ""
        val safeColumn = column.coerceIn(0, lineText.length)
        var start = safeColumn
        while (start > 0 && lineText[start - 1].isIdentifierPart()) start--
        return lineText.substring(start, safeColumn)
    }

    fun range(
        offset: Int,
        length: Int,
    ): Range {
        val safeOffset = offset.coerceIn(0, text.length)
        val safeEnd = (safeOffset + length).coerceIn(safeOffset, text.length)
        return Range(positionOf(safeOffset), positionOf(safeEnd))
    }

    fun declarations(): List<DeclarationMatch> {
        val regex = Regex("""\b(val|var|fun|class|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        return regex.findAll(text).map { match ->
            val kind = when (match.groupValues[1]) {
                "fun" -> CompletionItemKind.SYMBOL
                "class", "object" -> CompletionItemKind.SYMBOL
                else -> CompletionItemKind.SYMBOL
            }

            DeclarationMatch(
                name = match.groupValues[2],
                kind = kind,
                range = range(match.range.first + match.groupValues[1].length + 1, match.groupValues[2].length),
            )
        }.toList()
    }

    fun definition(
        path: String,
        symbol: String,
    ): DefinitionTarget? =
        declarations()
            .firstOrNull { it.name == symbol }
            ?.let { DefinitionTarget(path, it.range) }

    fun highlightMatches(
        regex: Regex,
        type: HighlightTokenType,
    ): List<HighlightToken> =
        regex.findAll(text).map { HighlightToken(range(it.range.first, it.value.length), type) }.toList()

    private fun positionOf(offset: Int): Position {
        var consumed = 0
        lines.forEachIndexed { index, line ->
            val lineLengthWithBreak = line.length + 1
            if (offset < consumed + lineLengthWithBreak) {
                return Position(index, (offset - consumed).coerceAtMost(line.length))
            }
            consumed += lineLengthWithBreak
        }
        val lastLine = lines.lastOrNull().orEmpty()
        return Position((lines.size - 1).coerceAtLeast(0), lastLine.length)
    }
}

private fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
