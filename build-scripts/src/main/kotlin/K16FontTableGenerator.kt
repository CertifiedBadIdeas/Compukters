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

private val terminalRequiredCodepoints =
    (0x20..0x7E).toSet() +
        setOf(0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x253C)

private data class K16FontMetadata(
    val name: String,
    val sourcePath: String,
    val version: Int,
    val glyphWidth: Int,
    val glyphHeight: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val glyphX: Int,
    val glyphY: Int,
    val baseline: Int,
    val fallbackCodepoint: Int,
)

private data class ParsedK16Font(
    val metadata: K16FontMetadata,
    val glyphs: Map<Int, List<String>>,
)

data class GeneratedK16FontTables(
    val guestRustSource: String,
    val markdownSpecimen: String,
)

class K16FontTableGenerator {
    fun generate(source: String): GeneratedK16FontTables {
        val font = parse(source)
        validate(font)
        return GeneratedK16FontTables(
            guestRustSource = renderGuestRust(font.metadata, font.glyphs),
            markdownSpecimen = renderMarkdownSpecimen(font.metadata, font.glyphs.toSortedMap()),
        )
    }

    private fun parse(source: String): ParsedK16Font {
        val header = linkedMapOf<String, String>()
        val glyphs = linkedMapOf<Int, List<String>>()
        val lines = source.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            index += 1
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.startsWith("glyph ")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "Expected `key value` or `glyph U+XXXX`, got `$line` at line $index" }
                require(parts[0] !in header) { "Duplicate font header `${parts[0]}` at line $index" }
                header[parts[0]] = parts[1]
                continue
            }
            val metadata = parseMetadata(header)
            val parts = line.split(Regex("\\s+"))
            require(parts.size >= 2) { "Glyph header must include a codepoint at line $index" }
            val codepoint = parseCodepoint(parts[1])
            val rows =
                (0 until metadata.glyphHeight).map { row ->
                    require(index < lines.size) { "Incomplete glyph U+${codepoint.hex()} at line $index" }
                    val glyphRow = lines[index].trim()
                    index += 1
                    require(glyphRow.length == metadata.glyphWidth && glyphRow.all { it == '.' || it == '#' }) {
                        "Glyph U+${codepoint.hex()} row ${row + 1} must be ${metadata.glyphWidth} cells of `.` or `#`"
                    }
                    glyphRow
                }
            glyphs[codepoint] = rows
        }
        return ParsedK16Font(parseMetadata(header), glyphs)
    }

    private fun parseMetadata(header: Map<String, String>): K16FontMetadata {
        val missing =
            listOf(
                "font",
                "source",
                "version",
                "glyph_width",
                "glyph_height",
                "cell_width",
                "cell_height",
                "glyph_x",
                "glyph_y",
                "baseline",
                "fallback",
            ).filterNot(header::containsKey)
        require(missing.isEmpty()) { "Missing font header fields: ${missing.joinToString()}" }
        val metadata =
            K16FontMetadata(
                name = header.getValue("font"),
                sourcePath = header.getValue("source"),
                version = header.getValue("version").toInt(),
                glyphWidth = header.getValue("glyph_width").toInt(),
                glyphHeight = header.getValue("glyph_height").toInt(),
                cellWidth = header.getValue("cell_width").toInt(),
                cellHeight = header.getValue("cell_height").toInt(),
                glyphX = header.getValue("glyph_x").toInt(),
                glyphY = header.getValue("glyph_y").toInt(),
                baseline = header.getValue("baseline").toInt(),
                fallbackCodepoint = parseCodepoint(header.getValue("fallback")),
            )
        require(metadata.version == 1) { "Unsupported font source version ${metadata.version}" }
        require(metadata.glyphWidth in 1..8) { "glyph_width must be between 1 and 8" }
        require(metadata.glyphHeight in 1..8) { "glyph_height must be between 1 and 8" }
        require(metadata.cellWidth >= metadata.glyphWidth) { "cell_width must be >= glyph_width" }
        require(metadata.cellHeight >= metadata.glyphHeight) { "cell_height must be >= glyph_height" }
        require(metadata.glyphX >= 0 && metadata.glyphY >= 0) { "glyph placement must be non-negative" }
        require(
            metadata.glyphX + metadata.glyphWidth <= metadata.cellWidth &&
                metadata.glyphY + metadata.glyphHeight <= metadata.cellHeight,
        ) {
            "glyph placement must fit inside cell"
        }
        require(metadata.baseline in 0..metadata.cellHeight) { "baseline must fit inside cell_height" }
        return metadata
    }

    private fun validate(font: ParsedK16Font) {
        val requiredCodepoints = terminalRequiredCodepoints + font.metadata.fallbackCodepoint
        val missing = requiredCodepoints.filterNot(font.glyphs::containsKey)
        require(missing.isEmpty()) {
            "Missing required glyphs: ${missing.joinToString { "U+${it.hex()}" }}"
        }
    }

    private fun renderGuestRust(
        metadata: K16FontMetadata,
        glyphs: Map<Int, List<String>>,
    ): String {
        val rows =
            (0x00..0x7F).joinToString(separator = "\n") { codepoint ->
                "    ${glyphRows(glyphs[codepoint] ?: glyphs.getValue(metadata.fallbackCodepoint), metadata)},"
            }
        return """
            |// Generated by ./gradlew generateK16FontTables. Do not edit by hand.
            |
            |pub const GLYPH_WIDTH: usize = ${metadata.glyphWidth};
            |pub const GLYPH_HEIGHT: usize = ${metadata.glyphHeight};
            |pub const CELL_WIDTH: usize = ${metadata.cellWidth};
            |pub const CELL_HEIGHT: usize = ${metadata.cellHeight};
            |pub const GLYPH_X: usize = ${metadata.glyphX};
            |pub const GLYPH_Y: usize = ${metadata.glyphY};
            |pub const BASELINE: usize = ${metadata.baseline};
            |pub const TERMINAL_FONT_LAST: u8 = 0x7e;
            |#[rustfmt::skip]
            |pub const FALLBACK_ROWS: [u8; GLYPH_HEIGHT] = ${glyphRows(glyphs.getValue(metadata.fallbackCodepoint), metadata)};
            |
            |#[rustfmt::skip]
            |pub const TERMINAL_FONT_ROWS: [[u8; GLYPH_HEIGHT]; 128] = [
            |$rows
            |];
            |
        """.trimMargin()
    }

    private fun renderMarkdownSpecimen(
        metadata: K16FontMetadata,
        glyphs: Map<Int, List<String>>,
    ): String {
        val glyphSections =
            glyphs.entries.joinToString(separator = "\n\n") { (codepoint, rows) ->
                """
                    |## U+${codepoint.hex()} ${displayName(codepoint, metadata)}
                    |
                    |```text
                    |${rows.joinToString(separator = "\n")}
                    |```
                """.trimMargin()
            }
        return """
            |# ${metadata.name} Font Specimen
            |
            |- Glyph size: ${metadata.glyphWidth}x${metadata.glyphHeight}
            |- Cell size: ${metadata.cellWidth}x${metadata.cellHeight}
            |- Glyph origin: ${metadata.glyphX},${metadata.glyphY}
            |- Baseline: ${metadata.baseline}
            |- Glyph count: ${glyphs.size}
            |- Source: ${metadata.sourcePath}
            |
            |$glyphSections
            |
        """.trimMargin()
    }

    private fun parseCodepoint(value: String): Int = value.removePrefix("U+").toInt(radix = 16)

    private fun glyphRows(
        rows: List<String>,
        metadata: K16FontMetadata,
    ): String =
        rows.joinToString(prefix = "[", postfix = "]") { row ->
            "0b" + row.fold(0) { bits, ch -> (bits shl 1) or if (ch == '#') 1 else 0 }
                .toString(radix = 2)
                .padStart(metadata.glyphWidth, '0')
        }

    private fun Int.hex(): String = toString(radix = 16).uppercase().padStart(4, '0')

    private fun displayName(
        codepoint: Int,
        metadata: K16FontMetadata,
    ): String =
        when (codepoint) {
            0x20 -> "SPACE"
            metadata.fallbackCodepoint -> "FALLBACK"
            else -> String(Character.toChars(codepoint))
        }

}
