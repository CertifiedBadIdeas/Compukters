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
package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserFileImportsRuntimeTest {
    private val frontend = LanguageFrontend()

    @Test
    fun executesAcrossFiles() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to "pub fun add(x: Int, y: Int): Int { return x + y; }",
                    "main.ck" to
                        """
                        import "math.ck" as m;
                        pub fun main() { terminal::println("sum=" + m::add(2, 3)); }
                        """.trimIndent(),
                ),
            )
        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
        assertNotNull(artifact.module)

        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(artifact.module!!).run(runtime) }
        assertEquals(listOf("sum=5"), runtime.lines)
    }

    @Test
    fun selectiveImportCallsAcrossFiles() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "io.ck" to "pub fun greet(): Unit { terminal::println(\"hi\"); }",
                    "main.ck" to
                        """
                        import "io.ck" { greet };
                        pub fun main() { greet(); }
                        """.trimIndent(),
                ),
            )
        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
        assertNotNull(artifact.module)

        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(artifact.module!!).run(runtime) }
        assertEquals(listOf("hi"), runtime.lines)
    }
}
