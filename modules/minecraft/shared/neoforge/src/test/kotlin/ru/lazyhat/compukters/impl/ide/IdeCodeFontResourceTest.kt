/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import com.google.gson.JsonParser
import java.awt.Font
import java.awt.font.FontRenderContext
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeCodeFontResourceTest {
    @Test
    fun `packaged TTF provider matches editor metrics without bitmap fallback`() {
        val profile = IdeCodeFontProfile.DEFAULT
        val json = resource("/assets/compukters/font/ide/${profile.id}.json").reader().use { it.readText() }
        val provider =
            JsonParser
                .parseString(json)
                .asJsonObject
                .getAsJsonArray("providers")
                .single()
                .asJsonObject

        assertEquals("ttf", provider.get("type").asString)
        assertEquals("compukters:ide/jetbrains_mono_regular.ttf", provider.get("file").asString)
        assertEquals(profile.size, provider.get("size").asFloat)
        assertEquals(profile.oversample, provider.get("oversample").asFloat)
        assertEquals(listOf(0f, 0f), provider.getAsJsonArray("shift").map { it.asFloat })
        assertTrue(resource("/META-INF/licenses/JetBrains-Mono-OFL-1.1.txt").use { it.readAllBytes().isNotEmpty() })
        assertTrue(resource("/META-INF/licenses/JetBrains-Mono-PROVENANCE.txt").use { it.readAllBytes().isNotEmpty() })
    }

    @Test
    fun `bundled font is the pinned upstream NL regular face`() {
        val bytes = resource("/assets/compukters/font/ide/jetbrains_mono_regular.ttf").use { it.readAllBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("fb3b2575d7b0657359707993288f12a7360344d39387bb26050e276d61f6bd2a", digest)
        val face = Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())
        assertEquals("JetBrains Mono NL", face.family)
        assertEquals(Font.PLAIN, face.style)
    }

    @Test
    fun `font advances and ink fit editor cells for code and Cyrillic`() {
        val profile = IdeCodeFontProfile.DEFAULT
        val face =
            resource("/assets/compukters/font/ide/jetbrains_mono_regular.ttf")
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }
                .deriveFont(profile.size)
        val context = FontRenderContext(null, true, true)
        val sample = "Wi012!=->{}[]_ЖяЁёйgj"
        assertEquals(-1, face.canDisplayUpTo(sample))
        val glyphs = face.createGlyphVector(context, sample)
        for (index in 0 until glyphs.numGlyphs) {
            assertEquals(profile.cellWidth.toFloat(), glyphs.getGlyphMetrics(index).advanceX, 0.001f)
            val ink = glyphs.getGlyphMetrics(index).bounds2D
            assertTrue(ink.minY + profile.baseline >= 0, "glyph above line: ${sample[index]}")
            assertTrue(ink.maxY + profile.baseline <= profile.cellHeight, "glyph below line: ${sample[index]}")
        }
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "Missing runtime resource $path" }
}
