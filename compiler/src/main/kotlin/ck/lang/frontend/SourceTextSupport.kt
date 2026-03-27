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
package ck.lang.frontend

internal object SourceTextSupport {
    private val identifierPrefixRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*$""")
    private val moduleMemberRegex = Regex("""([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z0-9_]*)$""")

    fun moduleMemberPrefix(
        source: String,
        offset: Int,
    ): Pair<String, String>? {
        val prefix = source.take(offset)
        val match = moduleMemberRegex.find(prefix) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    fun identifierPrefix(
        source: String,
        offset: Int,
    ): String = identifierPrefixRegex.find(source.take(offset))?.value.orEmpty()

    fun offsetAt(
        source: String,
        line: Int,
        column: Int,
    ): Int {
        var index = 0
        var currentLine = 0
        var currentColumn = 0
        while (index < source.length) {
            if (currentLine == line && currentColumn == column) {
                return index
            }
            val ch = source[index]
            index += 1
            if (ch == '\n') {
                currentLine += 1
                currentColumn = 0
            } else {
                currentColumn += 1
            }
        }
        return source.length
    }
}
