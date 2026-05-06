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
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RomScriptCompileTest {
    private fun resourceText(path: String): String =
        RomScriptCompileTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: fail("$path missing from classpath")

    @Test
    fun bundledFirmwareScriptCompilesCleanly() {
        val source = resourceText("firmware/bios.ck")

        val compiled = ComputerProgramCompiler.compile("bios.ck", source)

        if (compiled.program == null) {
            fail("Firmware script bios.ck failed to compile: ${compiled.errorMessage}")
        }
    }

    @Test
    fun bundledRomTerminalDoesNotUseStdoutForVisibleUi() {
        val source = resourceText("rom/terminal.ck")
        assertFalse(source.contains("stdout::write"), "rom/terminal.ck must render via display, not stdout")
    }

    @Test
    fun bundledRomTerminalCommitsInputByRowsNotByFullBufferCellRewrite() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("replaceRange"), "terminal.ck should batch committed text into row-sized cell updates")
        assertTrue(source.contains("commitDirtySegment"), "terminal.ck should commit one dirty row segment at a time")
        assertFalse(source.contains("fun setCell"), "terminal.ck must not rebuild the full cell buffer for every committed character")
        assertFalse(source.contains("cells = setCell"), "terminal.ck must not use per-character full-buffer writes in appendText")
    }

    @Test
    fun bundledRomShellChecksExternalCommandBeforeRun() {
        val source = resourceText("rom/shell.ck")

        assertTrue(source.contains("import filesystem { exists }"), "shell.ck should query filesystem before external command launch")
        assertTrue(source.contains("exists(command + \".ck\")"), "shell.ck should reject missing commands before process::run")
        assertFalse(source.contains("if (process::run(command + \".ck\""), "shell.ck must not call process::run directly for unknown commands")
    }

    @Test
    fun bundledRomStdioUsesTaggedDescriptorOnly() {
        val source = resourceText("rom/stdio.ck")

        assertTrue(source.contains("stdio-v1"), "rom/stdio.ck must emit tagged stdio-v1 descriptors")
        assertFalse(source.contains("return ctx.input +"), "rom/stdio.ck must not emit untagged descriptors")
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
                val artifact = LanguageFrontend(LanguageBuiltins.defaultRuntimeRegistry).compile(path, source, sourceLoader)
                val details = artifact.analysis.diagnostics.joinToString { diagnostic ->
                    val range = diagnostic.range
                    if (range == null) {
                        diagnostic.message
                    } else {
                        "${diagnostic.message} @ ${range.start.line}:${range.start.column}-${range.end.line}:${range.end.column}"
                    }
                }
                fail("ROM script $path failed to compile: ${compiled.errorMessage}; diagnostics: $details")
            }
        }
    }

    @Test
    fun bundledFirmwareAndRomDoNotUseRemovedTerminalStdoutBuiltins() {
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

        val paths = listOf("firmware/bios.ck") + files.map { "rom/$it" }
        for (path in paths) {
            val source =
                cl
                    .getResourceAsStream(path)
                    ?.bufferedReader()
                    ?.readText()
                    ?: fail("$path missing from classpath")
            assertFalse(source.contains("terminal::"), "$path still uses removed terminal builtins")
            assertFalse(source.contains("stdout::"), "$path still uses removed stdout builtins")
        }
    }

    @Test
    fun bootStartsRomTerminalProgram() {
        val cl = RomScriptCompileTest::class.java.classLoader
        val index =
            cl
                .getResourceAsStream("rom/rom.index")
                ?.bufferedReader()
                ?.readText()
                ?: fail("rom/rom.index missing from classpath")
        assertContains(index.lineSequence().map { it.trim() }.toList(), "terminal.ck")

        val boot =
            cl
                .getResourceAsStream("rom/boot.ck")
                ?.bufferedReader()
                ?.readText()
                ?: fail("rom/boot.ck missing from classpath")
        assertContains(boot, "terminal.ck")
    }
}
