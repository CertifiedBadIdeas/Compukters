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

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Converts the pinned Cozette BDF into the fixed terminal-cell representation used at runtime. */
object CozetteFontAtlas {
    private const val CELL_WIDTH = 6
    private const val CELL_HEIGHT = 13
    private const val ASCENT = 10
    private const val DESCENT = 3
    private const val COLUMNS = 16
    private const val REPLACEMENT_CODE_POINT = 0xFFFD

    fun generate(source: InputStream): GeneratedCozetteFont {
        val parsed = parse(source)
        require(parsed.ascent == ASCENT) {
            "Expected FONT_ASCENT $ASCENT, got ${parsed.ascent}"
        }
        require(parsed.descent == DESCENT) {
            "Expected FONT_DESCENT $DESCENT, got ${parsed.descent}"
        }

        val selected =
            parsed.glyphs
                .filter { isSelected(it.encoding) }
                .sortedBy(BdfGlyph::encoding)
        require(selected.any { it.encoding == REPLACEMENT_CODE_POINT }) {
            "Cozette must provide replacement glyph U+FFFD"
        }
        selected.forEach { glyph ->
            require(glyph.advanceX == CELL_WIDTH && glyph.advanceY == 0) {
                "U+${glyph.encoding.toHex(4)} (${glyph.name}) has DWIDTH " +
                    "${glyph.advanceX} ${glyph.advanceY}; expected $CELL_WIDTH 0"
            }
        }

        val rows = (selected.size + COLUMNS - 1) / COLUMNS
        val atlas = BufferedImage(COLUMNS * CELL_WIDTH, rows * CELL_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        selected.forEachIndexed { index, glyph -> project(glyph, index, atlas) }
        val codePoints = selected.map(BdfGlyph::encoding).toIntArray()

        require(glyphHasVisiblePixel(REPLACEMENT_CODE_POINT, codePoints, atlas)) {
            "Replacement glyph U+FFFD must contain at least one visible pixel"
        }

        return GeneratedCozetteFont(
            cellWidth = CELL_WIDTH,
            cellHeight = CELL_HEIGHT,
            ascent = ASCENT,
            codePoints = codePoints,
            png = atlas.toPng(),
            fontJson = fontJson(codePoints),
            manifest = codePoints.joinToString(separator = "\n", postfix = "\n") { "U+${it.toHex(4)}" },
            coverageKotlin = coverageKotlin(codePoints),
            atlas = atlas,
        )
    }

    private fun parse(source: InputStream): ParsedBdf {
        var ascent: Int? = null
        var descent: Int? = null
        var current: BdfGlyphBuilder? = null
        var readingBitmap = false
        val glyphs = mutableListOf<BdfGlyph>()
        val encodings = mutableSetOf<Int>()

        source.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEach { untrimmed ->
                val line = untrimmed.trim()
                when {
                    line.startsWith("FONT_ASCENT ") -> ascent = line.valueAfterKeyword().toInt()
                    line.startsWith("FONT_DESCENT ") -> descent = line.valueAfterKeyword().toInt()
                    line.startsWith("STARTCHAR ") -> {
                        require(current == null) { "Nested STARTCHAR is not valid BDF" }
                        current = BdfGlyphBuilder(name = line.valueAfterKeyword())
                        readingBitmap = false
                    }
                    line == "ENDCHAR" -> {
                        val glyph = requireNotNull(current) { "ENDCHAR without STARTCHAR" }.build()
                        if (glyph.encoding >= 0) {
                            require(encodings.add(glyph.encoding)) {
                                "Duplicate BDF encoding U+${glyph.encoding.toHex(4)}"
                            }
                            glyphs += glyph
                        }
                        current = null
                        readingBitmap = false
                    }
                    current != null && line.startsWith("ENCODING ") ->
                        current!!.encoding = line.valueAfterKeyword().substringBefore(' ').toInt()
                    current != null && line.startsWith("DWIDTH ") -> {
                        val values = line.valueAfterKeyword().splitWhitespace()
                        require(values.size == 2) { "Malformed DWIDTH: $line" }
                        current!!.advanceX = values[0].toInt()
                        current!!.advanceY = values[1].toInt()
                    }
                    current != null && line.startsWith("BBX ") -> {
                        val values = line.valueAfterKeyword().splitWhitespace()
                        require(values.size == 4) { "Malformed BBX: $line" }
                        current!!.width = values[0].toInt()
                        current!!.height = values[1].toInt()
                        current!!.xOffset = values[2].toInt()
                        current!!.yOffset = values[3].toInt()
                    }
                    current != null && line == "BITMAP" -> readingBitmap = true
                    current != null && readingBitmap && line.isNotEmpty() -> current!!.bitmapRows += line
                }
            }
        }
        require(current == null) { "Unterminated BDF glyph ${current?.name}" }
        return ParsedBdf(
            ascent = requireNotNull(ascent) { "BDF is missing FONT_ASCENT" },
            descent = requireNotNull(descent) { "BDF is missing FONT_DESCENT" },
            glyphs = glyphs,
        )
    }

    private fun project(glyph: BdfGlyph, index: Int, atlas: BufferedImage) {
        val cellLeft = (index % COLUMNS) * CELL_WIDTH
        val cellTop = (index / COLUMNS) * CELL_HEIGHT
        glyph.bitmapRows.forEachIndexed { bitmapRow, hex ->
            val bytes = decodeHexRow(hex, glyph.width)
            repeat(glyph.width) { sourceX ->
                val pixelSet = bytes[sourceX / 8].toInt() and (0x80 ushr (sourceX % 8)) != 0
                if (!pixelSet) return@repeat

                val sourceY = glyph.yOffset + glyph.height - 1 - bitmapRow
                val targetX = glyph.xOffset + sourceX
                val targetY = ASCENT - 1 - sourceY
                if (targetX in 0 until CELL_WIDTH && targetY in 0 until CELL_HEIGHT) {
                    atlas.setRGB(cellLeft + targetX, cellTop + targetY, 0xFFFFFFFF.toInt())
                }
            }
        }
    }

    private fun decodeHexRow(value: String, glyphWidth: Int): ByteArray {
        val byteCount = (glyphWidth + 7) / 8
        require(value.length <= byteCount * 2) { "Bitmap row '$value' is wider than BBX width $glyphWidth" }
        val normalized = value.padStart(byteCount * 2, '0')
        return ByteArray(byteCount) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun isSelected(codePoint: Int): Boolean =
        codePoint in 0x20..0x7E ||
            codePoint in 0xA0..0xFF ||
            codePoint in 0x0400..0x04FF ||
            codePoint in 0x2190..0x21FF ||
            codePoint in 0x2500..0x257F ||
            codePoint in 0x2580..0x259F ||
            codePoint == REPLACEMENT_CODE_POINT

    private fun fontJson(codePoints: IntArray): String {
        val characterRows =
            codePoints
                .toList()
                .chunked(COLUMNS)
                .map { row -> row + List(COLUMNS - row.size) { 0 } }
        return buildString {
            appendLine("{")
            appendLine("  \"providers\": [")
            appendLine("    {")
            appendLine("      \"type\": \"bitmap\",")
            appendLine("      \"file\": \"compukters:font/terminal/cozette.png\",")
            appendLine("      \"height\": 13,")
            appendLine("      \"ascent\": 10,")
            appendLine("      \"chars\": [")
            characterRows.forEachIndexed { index, row ->
                val characters = row.joinToString(separator = "") { "\\u${it.toHex(4)}" }
                append("        \"").append(characters).append('"')
                if (index != characterRows.lastIndex) append(',')
                appendLine()
            }
            appendLine("      ]")
            appendLine("    }")
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun coverageKotlin(codePoints: IntArray): String {
        return buildString {
            appendLine("/*")
            appendLine(" * Generated from pinned Cozette v.1.30.0. Do not edit manually.")
            appendLine(" */")
            appendLine()
            appendLine("package ru.lazyhat.compukters.impl.terminal")
            appendLine()
            appendLine("internal val COZETTE_SUPPORTED_CODE_POINTS =")
            appendLine("    intArrayOf(")
            codePoints.forEach { appendLine("        0x${it.toHex(4)},") }
            appendLine("    )")
        }
    }

    private fun glyphHasVisiblePixel(codePoint: Int, codePoints: IntArray, atlas: BufferedImage): Boolean {
        val index = codePoints.binarySearch(codePoint)
        if (index < 0) return false
        val left = index % COLUMNS * CELL_WIDTH
        val top = index / COLUMNS * CELL_HEIGHT
        return (0 until CELL_HEIGHT).any { y ->
            (0 until CELL_WIDTH).any { x -> atlas.getRGB(left + x, top + y).ushr(24) != 0 }
        }
    }

    private fun BufferedImage.toPng(): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(this, "png", output)) { "No PNG writer is available" }
            output.toByteArray()
        }

    private fun String.valueAfterKeyword(): String = substringAfter(' ').trim()

    private fun String.splitWhitespace(): List<String> = split(Regex("\\s+"))

    private fun Int.toHex(minimumDigits: Int): String = toString(16).uppercase().padStart(minimumDigits, '0')

    private data class ParsedBdf(
        val ascent: Int,
        val descent: Int,
        val glyphs: List<BdfGlyph>,
    )

    private data class BdfGlyph(
        val name: String,
        val encoding: Int,
        val advanceX: Int,
        val advanceY: Int,
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val bitmapRows: List<String>,
    )

    private class BdfGlyphBuilder(val name: String) {
        var encoding: Int? = null
        var advanceX: Int? = null
        var advanceY: Int? = null
        var width: Int? = null
        var height: Int? = null
        var xOffset: Int? = null
        var yOffset: Int? = null
        val bitmapRows = mutableListOf<String>()

        fun build(): BdfGlyph {
            val resolvedHeight = requireNotNull(height) { "$name is missing BBX" }
            require(bitmapRows.size == resolvedHeight) {
                "$name has ${bitmapRows.size} bitmap rows, expected $resolvedHeight"
            }
            return BdfGlyph(
                name = name,
                encoding = requireNotNull(encoding) { "$name is missing ENCODING" },
                advanceX = requireNotNull(advanceX) { "$name is missing DWIDTH" },
                advanceY = requireNotNull(advanceY) { "$name is missing DWIDTH" },
                width = requireNotNull(width) { "$name is missing BBX" },
                height = resolvedHeight,
                xOffset = requireNotNull(xOffset) { "$name is missing BBX" },
                yOffset = requireNotNull(yOffset) { "$name is missing BBX" },
                bitmapRows = bitmapRows.toList(),
            )
        }
    }
}

class GeneratedCozetteFont internal constructor(
    val cellWidth: Int,
    val cellHeight: Int,
    val ascent: Int,
    val codePoints: IntArray,
    val png: ByteArray,
    val fontJson: String,
    val manifest: String,
    val coverageKotlin: String,
    private val atlas: BufferedImage,
) {
    fun pixel(codePoint: Int, x: Int, y: Int): Boolean {
        require(x in 0 until cellWidth) { "x is outside the glyph cell: $x" }
        require(y in 0 until cellHeight) { "y is outside the glyph cell: $y" }
        val index = codePoints.binarySearch(codePoint)
        require(index >= 0) { "Code point U+${codePoint.toString(16).uppercase()} is not in the atlas" }
        val left = index % 16 * cellWidth
        val top = index / 16 * cellHeight
        return atlas.getRGB(left + x, top + y).ushr(24) != 0
    }
}

abstract class GenerateCozetteTerminalFont : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bdfFile: RegularFileProperty

    @get:OutputFile
    abstract val fontJsonFile: RegularFileProperty

    @get:OutputFile
    abstract val atlasPngFile: RegularFileProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val coverageKotlinFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val generated = bdfFile.get().asFile.inputStream().use(CozetteFontAtlas::generate)
        write(fontJsonFile, generated.fontJson.toByteArray(Charsets.UTF_8))
        write(atlasPngFile, generated.png)
        write(manifestFile, generated.manifest.toByteArray(Charsets.UTF_8))
        write(coverageKotlinFile, generated.coverageKotlin.toByteArray(Charsets.UTF_8))
    }

    private fun write(
        destination: RegularFileProperty,
        bytes: ByteArray,
    ) {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

abstract class VerifyCozetteTerminalFont : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bdfFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fontJsonFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atlasPngFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coverageKotlinFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val generated = bdfFile.get().asFile.inputStream().use(CozetteFontAtlas::generate)
        val stale =
            listOf(
                fontJsonFile to generated.fontJson.toByteArray(Charsets.UTF_8),
                atlasPngFile to generated.png,
                manifestFile to generated.manifest.toByteArray(Charsets.UTF_8),
                coverageKotlinFile to generated.coverageKotlin.toByteArray(Charsets.UTF_8),
            ).mapNotNull { (property, expected) ->
                property.get().asFile.takeUnless { file -> file.readBytes().contentEquals(expected) }
            }
        check(stale.isEmpty()) {
            "Generated Cozette resources are stale: ${stale.joinToString { it.relativeTo(project.rootDir).path }}. " +
                "Run :v26_1-neoforge:generateCozetteTerminalFont."
        }
    }
}
