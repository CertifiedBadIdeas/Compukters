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
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.fail

class RomScriptCompileTest {
    @Test
    fun bundledFirmwareScriptCompilesCleanly() {
        val source =
            RomScriptCompileTest::class.java.classLoader
                .getResourceAsStream("firmware/bios.ck")
                ?.bufferedReader()
                ?.readText()
                ?: fail("firmware/bios.ck missing from classpath")

        val compiled = ComputerProgramCompiler.compile("bios.ck", source)

        if (compiled.program == null) {
            fail("Firmware script bios.ck failed to compile: ${compiled.errorMessage}")
        }
    }

    @Test
    fun everyRomScriptCompilesCleanly() {
        val cl = RomScriptCompileTest::class.java.classLoader
        val index =
            cl
                .getResourceAsStream("rom/rom.index")
                ?.bufferedReader()
                ?.readText()
                ?: fail("rom/rom.index missing from classpath")

        val files =
            index
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        assertNotNull(files.firstOrNull(), "rom.index is empty")

        val sources =
            files.associateWith { path ->
                cl
                    .getResourceAsStream("rom/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: fail("rom/$path missing from classpath")
            }
        val sourceLoader = MapSourceLoader(sources)

        for (path in files) {
            val source = sources[path] ?: fail("rom/$path missing from source map")
            val compiled = ComputerProgramCompiler.compile(path, source, sourceLoader = sourceLoader)
            if (compiled.program == null) {
                fail("ROM script $path failed to compile: ${compiled.errorMessage}")
            }
        }
    }

    @Test
    fun romScriptsDoNotUseLegacyTerminalBuiltins() {
        val cl = RomScriptCompileTest::class.java.classLoader
        val index =
            cl
                .getResourceAsStream("rom/rom.index")
                ?.bufferedReader()
                ?.readText()
                ?: fail("rom/rom.index missing from classpath")

        val files =
            index
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        assertNotNull(files.firstOrNull(), "rom.index is empty")

        for (path in files) {
            val source =
                cl
                    .getResourceAsStream("rom/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: fail("rom/$path missing from classpath")
            assertFalse(source.contains("terminal::"), "rom/$path still uses legacy terminal builtins")
        }
    }
}
