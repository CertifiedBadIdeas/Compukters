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

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScriptIdeServiceImplTest {
    private val environment =
        ScriptingEnvironmentInitializerImpl().initialize(
            ScriptingEnvironmentConfig(
                modId = "compukterkraft",
                bundledScriptsRoot = "rom",
                definitions = listOf(ScriptDefinitionPresets.computerKts("compukterkraft")),
            ),
        )

    private val code =
        """
        import terminal;

        fun helper() {
            let message: String = "hi";
            terminal.printLine(message);
        }

        fun main() {
            helper();
        }
        """.trimIndent()

    @Test
    fun providesCompletionAndHoverForBuiltinModules() {
        val completion = environment.ide.complete("test.ck", code, 4, 16)
        assertTrue(completion.any { it.label == "printLine" })

        val hover = environment.ide.hover("test.ck", code, 4, 18)
        assertNotNull(hover)
        assertTrue(hover.contents.contains("terminal.printLine"))
    }

    @Test
    fun resolvesDefinitionForUserFunction() {
        val definition = environment.ide.definition("test.ck", code, 8, 6)
        assertNotNull(definition)
        assertEquals("test.ck", definition.path)
    }
}
