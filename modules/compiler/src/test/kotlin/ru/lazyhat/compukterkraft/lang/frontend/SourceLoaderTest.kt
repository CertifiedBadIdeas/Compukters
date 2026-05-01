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
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapSourceLoaderTest {
    private val files =
        mapOf(
            "main.ck" to "fun main() {}",
            "lib/math.ck" to "fun add() {}",
            "lib/io/print.ck" to "fun p() {}",
        )
    private val loader = MapSourceLoader(files)

    @Test
    fun resolvesSiblingFile() {
        assertEquals("lib/math.ck", loader.resolve("main.ck", "lib/math.ck"))
    }

    @Test
    fun resolvesRelativeWithDotDot() {
        assertEquals("lib/math.ck", loader.resolve("lib/io/print.ck", "../math.ck"))
    }

    @Test
    fun resolvesCurrentDirectory() {
        assertEquals("lib/io/print.ck", loader.resolve("lib/io/print.ck", "./print.ck"))
    }

    @Test
    fun returnsNullForMissing() {
        assertNull(loader.resolve("main.ck", "nope.ck"))
    }

    @Test
    fun readsKnownFiles() {
        assertEquals("fun main() {}", loader.read("main.ck"))
        assertNull(loader.read("nope.ck"))
    }

    @Test
    fun mapSourceLoaderListsCkSources() {
        val loader = MapSourceLoader(mapOf("main.ck" to "", "lib/math.ck" to "", "notes.txt" to ""))

        assertEquals(listOf("lib/math.ck", "main.ck"), loader.listSources().sorted())
    }
}
