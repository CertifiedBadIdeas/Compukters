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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalBitmapFontAtlasTest {
    @Test
    fun projectsSelectedGlyphsIntoFixedSixByThirteenCells() {
        val generated = TerminalBitmapFontAtlas.generate(SPEC, FIXTURE.byteInputStream())

        assertEquals(6, generated.cellWidth)
        assertEquals(13, generated.cellHeight)
        assertEquals(10, generated.ascent)
        assertArrayEquals(
            intArrayOf(0x20, 0x41, 0x0416, 0x2500, 0x2588, 0xFFFD),
            generated.codePoints,
        )
        assertTrue(generated.pixel(0x41, x = 0, y = 0))
        assertTrue(generated.pixel(0x0416, x = 0, y = 0))
        assertTrue(generated.pixel(0x0416, x = 5, y = 0))
        assertTrue(generated.pixel(0x2500, x = 5, y = 6))
        assertTrue((0 until 13).all { y -> generated.pixel(0x2588, x = 5, y = y) })
        assertFalse(generated.codePoints.contains(0x1F680))
    }

    @Test
    fun emitsByteStableRuntimeAndCoverageOutputs() {
        val first = TerminalBitmapFontAtlas.generate(SPEC, FIXTURE.byteInputStream())
        val second = TerminalBitmapFontAtlas.generate(SPEC, FIXTURE.byteInputStream())

        assertArrayEquals(first.png, second.png)
        assertEquals(first.fontJson, second.fontJson)
        assertEquals(first.manifest, second.manifest)
        assertEquals(first.coverageKotlin, second.coverageKotlin)
        assertFalse(first.fontJson.contains("minecraft:"))
        assertTrue(first.fontJson.contains("compukters:font/terminal/fixture.png"))
        assertTrue(first.coverageKotlin.contains("FIXTURE_SUPPORTED_CODE_POINTS"))
    }

    @Test
    fun rejectsSelectedGlyphWithNonMonospaceAdvance() {
        val malformed = FIXTURE.replace("DWIDTH 6 0\nBBX 6 2 0 8", "DWIDTH 5 0\nBBX 6 2 0 8")

        val error = assertThrows(IllegalArgumentException::class.java) {
            TerminalBitmapFontAtlas.generate(SPEC, malformed.byteInputStream())
        }

        assertTrue(error.message.orEmpty().contains("U+0041"))
        assertTrue(error.message.orEmpty().contains("DWIDTH"))
    }

    private companion object {
        val SPEC =
            TerminalBitmapFontSpec(
                displayName = "Fixture",
                resourceName = "fixture",
                coveragePropertyName = "FIXTURE_SUPPORTED_CODE_POINTS",
                sourceDescription = "fixture BDF",
                cellWidth = 6,
                cellHeight = 13,
                ascent = 10,
                descent = 3,
                replacementCodePoint = 0xFFFD,
                selectedCodePoints =
                    listOf(
                        0x20..0x7E,
                        0x0400..0x04FF,
                        0x2500..0x25FF,
                        0xFFFD..0xFFFD,
                    ),
            )

        val FIXTURE =
            """
            STARTFONT 2.1
            FONT -test-Cozette-Medium-R-Normal--13-120-75-75-M-60-ISO10646-1
            FONTBOUNDINGBOX 13 15 -1 -3
            STARTPROPERTIES 3
            FONT_ASCENT 10
            FONT_DESCENT 3
            DEFAULT_CHAR 0
            ENDPROPERTIES
            CHARS 7
            STARTCHAR space
            ENCODING 32
            DWIDTH 6 0
            BBX 1 1 6 -1
            BITMAP
            00
            ENDCHAR
            STARTCHAR A
            ENCODING 65
            DWIDTH 6 0
            BBX 6 2 0 8
            BITMAP
            FC
            84
            ENDCHAR
            STARTCHAR afii10024
            ENCODING 1046
            DWIDTH 6 0
            BBX 7 1 -1 9
            BITMAP
            FE
            ENDCHAR
            STARTCHAR SF100000
            ENCODING 9472
            DWIDTH 6 0
            BBX 7 1 0 3
            BITMAP
            FE
            ENDCHAR
            STARTCHAR block
            ENCODING 9608
            DWIDTH 6 0
            BBX 7 13 0 -3
            BITMAP
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            FE
            ENDCHAR
            STARTCHAR uniFFFD
            ENCODING 65533
            DWIDTH 6 0
            BBX 6 3 0 3
            BITMAP
            78
            48
            78
            ENDCHAR
            STARTCHAR rocket
            ENCODING 128640
            DWIDTH 6 0
            BBX 6 1 0 3
            BITMAP
            FC
            ENDCHAR
            ENDFONT
            """.trimIndent() + "\n"
    }
}
