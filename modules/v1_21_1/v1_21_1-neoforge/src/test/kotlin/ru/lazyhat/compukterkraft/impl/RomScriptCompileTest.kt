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
package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class RomScriptCompileTest {
    @Test
    fun everyRomScriptCompilesCleanly() {
        val cl = RomScriptCompileTest::class.java.classLoader
        val index =
            cl.getResourceAsStream("rom/rom.index")
                ?.bufferedReader()
                ?.readText()
                ?: fail("rom/rom.index missing from classpath")

        val files = index.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        assertNotNull(files.firstOrNull(), "rom.index is empty")

        for (path in files) {
            val source =
                cl.getResourceAsStream("rom/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: fail("rom/$path missing from classpath")
            val compiled = ComputerProgramCompiler.compile(path, source)
            if (compiled.program == null) {
                fail("ROM script $path failed to compile: ${compiled.errorMessage}")
            }
        }
    }
}
