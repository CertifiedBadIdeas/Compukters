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
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

data class TerminalBitmapFontSpec(
    val displayName: String,
    val resourceName: String,
    val coveragePropertyName: String,
    val sourceDescription: String,
    val cellWidth: Int,
    val cellHeight: Int,
    val ascent: Int,
    val descent: Int,
    val replacementCodePoint: Int,
    val selectedCodePoints: List<IntRange>,
) {
    init {
        require(displayName.isNotBlank()) { "terminal font display name must not be blank" }
        require(resourceName.matches(Regex("[a-z0-9_-]+"))) { "invalid terminal font resource name: $resourceName" }
        require(coveragePropertyName.matches(Regex("[A-Z0-9_]+"))) {
            "invalid terminal font coverage property: $coveragePropertyName"
        }
        require(sourceDescription.isNotBlank()) { "terminal font source description must not be blank" }
        require(cellWidth > 0 && cellHeight > 0) { "terminal font cell must be positive" }
        require(ascent > 0 && descent >= 0 && cellHeight == ascent + descent) {
            "terminal font height must equal ascent plus descent"
        }
        require(selectedCodePoints.isNotEmpty()) { "terminal font selection must not be empty" }
    }

    fun selects(codePoint: Int): Boolean = selectedCodePoints.any { codePoint in it }
}

private val COZETTE_FONT_SPEC =
    TerminalBitmapFontSpec(
        displayName = "Cozette",
        resourceName = "cozette",
        coveragePropertyName = "COZETTE_SUPPORTED_CODE_POINTS",
        sourceDescription = "pinned Cozette v.1.30.0",
        cellWidth = 6,
        cellHeight = 13,
        ascent = 10,
        descent = 3,
        replacementCodePoint = 0xFFFD,
        selectedCodePoints =
            listOf(
                0x20..0x7E,
                0xA0..0xFF,
                0x0400..0x04FF,
                0x2190..0x21FF,
                0x2500..0x257F,
                0x2580..0x259F,
                0xFFFD..0xFFFD,
            ),
    )

/** Converts a pinned BDF into the fixed terminal-cell representation used at runtime. */
object TerminalBitmapFontAtlas {
    private const val COLUMNS = 16

    fun generate(
        spec: TerminalBitmapFontSpec,
        source: InputStream,
    ): GeneratedTerminalBitmapFont {
        val parsed = parse(source)
        require(parsed.ascent == spec.ascent) {
            "Expected FONT_ASCENT ${spec.ascent}, got ${parsed.ascent}"
        }
        require(parsed.descent == spec.descent) {
            "Expected FONT_DESCENT ${spec.descent}, got ${parsed.descent}"
        }

        val selected =
            parsed.glyphs
                .filter { spec.selects(it.encoding) }
                .sortedBy(BdfGlyph::encoding)
        require(selected.any { it.encoding == spec.replacementCodePoint }) {
            "${spec.displayName} must provide replacement glyph U+${spec.replacementCodePoint.toHex(4)}"
        }
        selected.forEach { glyph ->
            require(glyph.advanceX == spec.cellWidth && glyph.advanceY == 0) {
                "U+${glyph.encoding.toHex(4)} (${glyph.name}) has DWIDTH " +
                    "${glyph.advanceX} ${glyph.advanceY}; expected ${spec.cellWidth} 0"
            }
        }

        val rows = (selected.size + COLUMNS - 1) / COLUMNS
        val atlas = BufferedImage(COLUMNS * spec.cellWidth, rows * spec.cellHeight, BufferedImage.TYPE_INT_ARGB)
        selected.forEachIndexed { index, glyph -> project(spec, glyph, index, atlas) }
        val codePoints = selected.map(BdfGlyph::encoding).toIntArray()

        require(glyphHasVisiblePixel(spec, spec.replacementCodePoint, codePoints, atlas)) {
            "Replacement glyph U+${spec.replacementCodePoint.toHex(4)} must contain at least one visible pixel"
        }

        return GeneratedTerminalBitmapFont(
            cellWidth = spec.cellWidth,
            cellHeight = spec.cellHeight,
            ascent = spec.ascent,
            codePoints = codePoints,
            png = atlas.toPng(),
            fontJson = fontJson(spec, codePoints),
            manifest = codePoints.joinToString(separator = "\n", postfix = "\n") { "U+${it.toHex(4)}" },
            coverageKotlin = coverageKotlin(spec, codePoints),
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

    private fun project(
        spec: TerminalBitmapFontSpec,
        glyph: BdfGlyph,
        index: Int,
        atlas: BufferedImage,
    ) {
        val cellLeft = (index % COLUMNS) * spec.cellWidth
        val cellTop = (index / COLUMNS) * spec.cellHeight
        glyph.bitmapRows.forEachIndexed { bitmapRow, hex ->
            val bytes = decodeHexRow(hex, glyph.width)
            repeat(glyph.width) { sourceX ->
                val pixelSet = bytes[sourceX / 8].toInt() and (0x80 ushr (sourceX % 8)) != 0
                if (!pixelSet) return@repeat

                val sourceY = glyph.yOffset + glyph.height - 1 - bitmapRow
                val targetX = glyph.xOffset + sourceX
                val targetY = spec.ascent - 1 - sourceY
                if (targetX in 0 until spec.cellWidth && targetY in 0 until spec.cellHeight) {
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

    private fun fontJson(
        spec: TerminalBitmapFontSpec,
        codePoints: IntArray,
    ): String {
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
            appendLine("      \"file\": \"compukters:font/terminal/${spec.resourceName}.png\",")
            appendLine("      \"height\": ${spec.cellHeight},")
            appendLine("      \"ascent\": ${spec.ascent},")
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

    private fun coverageKotlin(
        spec: TerminalBitmapFontSpec,
        codePoints: IntArray,
    ): String {
        return buildString {
            appendLine("/*")
            appendLine(" * Generated from ${spec.sourceDescription}. Do not edit manually.")
            appendLine(" */")
            appendLine()
            appendLine("package ru.lazyhat.compukters.impl.terminal")
            appendLine()
            appendLine("internal val ${spec.coveragePropertyName} =")
            appendLine("    intArrayOf(")
            codePoints.forEach { appendLine("        0x${it.toHex(4)},") }
            appendLine("    )")
        }
    }

    private fun glyphHasVisiblePixel(
        spec: TerminalBitmapFontSpec,
        codePoint: Int,
        codePoints: IntArray,
        atlas: BufferedImage,
    ): Boolean {
        val index = codePoints.binarySearch(codePoint)
        if (index < 0) return false
        val left = index % COLUMNS * spec.cellWidth
        val top = index / COLUMNS * spec.cellHeight
        return (0 until spec.cellHeight).any { y ->
            (0 until spec.cellWidth).any { x -> atlas.getRGB(left + x, top + y).ushr(24) != 0 }
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

class GeneratedTerminalBitmapFont internal constructor(
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

private fun taskSpec(
    displayName: Property<String>,
    resourceName: Property<String>,
    coveragePropertyName: Property<String>,
    sourceDescription: Property<String>,
    cellWidth: Property<Int>,
    cellHeight: Property<Int>,
    ascent: Property<Int>,
    descent: Property<Int>,
    replacementCodePoint: Property<Int>,
    selectedRanges: ListProperty<String>,
): TerminalBitmapFontSpec =
    TerminalBitmapFontSpec(
        displayName = displayName.get(),
        resourceName = resourceName.get(),
        coveragePropertyName = coveragePropertyName.get(),
        sourceDescription = sourceDescription.get(),
        cellWidth = cellWidth.get(),
        cellHeight = cellHeight.get(),
        ascent = ascent.get(),
        descent = descent.get(),
        replacementCodePoint = replacementCodePoint.get(),
        selectedCodePoints =
            selectedRanges.get().map { encoded ->
                val endpoints = encoded.split("..")
                require(endpoints.size == 2) { "invalid terminal font range: $encoded" }
                endpoints[0].toInt()..endpoints[1].toInt()
            },
    )

abstract class GenerateTerminalBitmapFont : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bdfFile: RegularFileProperty

    @get:Input
    abstract val displayName: Property<String>

    @get:Input
    abstract val resourceName: Property<String>

    @get:Input
    abstract val coveragePropertyName: Property<String>

    @get:Input
    abstract val sourceDescription: Property<String>

    @get:Input
    abstract val cellWidth: Property<Int>

    @get:Input
    abstract val cellHeight: Property<Int>

    @get:Input
    abstract val ascent: Property<Int>

    @get:Input
    abstract val descent: Property<Int>

    @get:Input
    abstract val replacementCodePoint: Property<Int>

    @get:Input
    abstract val selectedRanges: ListProperty<String>

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
        val spec =
            taskSpec(
                displayName,
                resourceName,
                coveragePropertyName,
                sourceDescription,
                cellWidth,
                cellHeight,
                ascent,
                descent,
                replacementCodePoint,
                selectedRanges,
            )
        val generated =
            bdfFile.get().asFile.inputStream().use { source ->
                TerminalBitmapFontAtlas.generate(spec, source)
            }
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

abstract class VerifyTerminalBitmapFont : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bdfFile: RegularFileProperty

    @get:Input
    abstract val displayName: Property<String>

    @get:Input
    abstract val resourceName: Property<String>

    @get:Input
    abstract val coveragePropertyName: Property<String>

    @get:Input
    abstract val sourceDescription: Property<String>

    @get:Input
    abstract val cellWidth: Property<Int>

    @get:Input
    abstract val cellHeight: Property<Int>

    @get:Input
    abstract val ascent: Property<Int>

    @get:Input
    abstract val descent: Property<Int>

    @get:Input
    abstract val replacementCodePoint: Property<Int>

    @get:Input
    abstract val selectedRanges: ListProperty<String>

    @get:Input
    abstract val regenerationTaskName: Property<String>

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
        val spec =
            taskSpec(
                displayName,
                resourceName,
                coveragePropertyName,
                sourceDescription,
                cellWidth,
                cellHeight,
                ascent,
                descent,
                replacementCodePoint,
                selectedRanges,
            )
        val generated =
            bdfFile.get().asFile.inputStream().use { source ->
                TerminalBitmapFontAtlas.generate(spec, source)
            }
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
            "Generated ${spec.displayName} resources are stale: " +
                "${stale.joinToString { it.relativeTo(project.rootDir).path }}. " +
                "Run ${regenerationTaskName.get()}."
        }
    }
}

abstract class GenerateCozetteTerminalFont : GenerateTerminalBitmapFont() {
    init {
        convention(COZETTE_FONT_SPEC)
    }

    private fun convention(spec: TerminalBitmapFontSpec) {
        displayName.convention(spec.displayName)
        resourceName.convention(spec.resourceName)
        coveragePropertyName.convention(spec.coveragePropertyName)
        sourceDescription.convention(spec.sourceDescription)
        cellWidth.convention(spec.cellWidth)
        cellHeight.convention(spec.cellHeight)
        ascent.convention(spec.ascent)
        descent.convention(spec.descent)
        replacementCodePoint.convention(spec.replacementCodePoint)
        selectedRanges.convention(spec.selectedCodePoints.map { "${it.first}..${it.last}" })
    }
}

abstract class VerifyCozetteTerminalFont : VerifyTerminalBitmapFont() {
    init {
        displayName.convention(COZETTE_FONT_SPEC.displayName)
        resourceName.convention(COZETTE_FONT_SPEC.resourceName)
        coveragePropertyName.convention(COZETTE_FONT_SPEC.coveragePropertyName)
        sourceDescription.convention(COZETTE_FONT_SPEC.sourceDescription)
        cellWidth.convention(COZETTE_FONT_SPEC.cellWidth)
        cellHeight.convention(COZETTE_FONT_SPEC.cellHeight)
        ascent.convention(COZETTE_FONT_SPEC.ascent)
        descent.convention(COZETTE_FONT_SPEC.descent)
        replacementCodePoint.convention(COZETTE_FONT_SPEC.replacementCodePoint)
        selectedRanges.convention(COZETTE_FONT_SPEC.selectedCodePoints.map { "${it.first}..${it.last}" })
        regenerationTaskName.convention(":v26_1-neoforge:generateCozetteTerminalFont")
    }
}
