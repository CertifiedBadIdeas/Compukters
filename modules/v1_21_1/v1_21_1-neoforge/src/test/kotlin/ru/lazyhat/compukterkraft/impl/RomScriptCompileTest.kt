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
    fun bundledFirmwareShowsSplashBeforeBootLookup() {
        val source = resourceText("firmware/bios.ck")

        assertTrue(source.contains("fun draw_splash"), "bios.ck should have a dedicated splash renderer")
        assertTrue(source.contains("fun hold_splash"), "bios.ck should keep the splash visible before boot starts")
        assertTrue(source.contains("display::blitMono"), "bios.ck should render the splash through display primitives")
        assertTrue(source.contains("Compukter"), "bios.ck should include visible Compukter branding")
        assertTrue(source.contains("hold_splash(20)"), "bios.ck should hold the splash briefly before boot")
        assertTrue(
            source.indexOf("hold_splash(20)") < source.indexOf("filesystem::exists(\"boot.ck\")"),
            "bios.ck should show the splash before looking up boot.ck",
        )
        assertFalse(source.contains("stdout::write"), "bios.ck must not use stdout for visible splash UI")
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
    fun bundledRomTerminalStartsShellAfterDisplayReadyAndPreservesBufferOnResize() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.indexOf("var displayId: Int = waitDisplay()") < source.indexOf("process::spawn(\"shell.ck\""),
            "terminal.ck should start shell only after display init so greeting/prompt cannot be cleared by startup display events",
        )
        assertTrue(
            source.contains("renderAllRows"),
            "terminal.ck should redraw existing text after same-size display attach/resize",
        )
        assertTrue(
            source.contains("displayColumns: Int"),
            "terminal buffer should track the column geometry used to lay out cellsText",
        )
        assertTrue(
            source.contains("displayRows: Int"),
            "terminal buffer should track the row geometry used to lay out cellsText",
        )
        assertTrue(
            source.contains("buffer.displayColumns == columns(displayId) && buffer.displayRows == rows(displayId)"),
            "terminal.ck should preserve/redraw cells only when the display grid geometry is unchanged",
        )
        assertTrue(
            source.contains("newTerminalBuffer(displayId)"),
            "terminal.ck should reset through a single geometry-aware buffer initializer when the grid changes",
        )
    }

    @Test
    fun bundledRomTerminalUsesSingleVisibleStreamForStdoutAndStderr() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("val stream: Int = ipc::open()"),
            "terminal.ck should use one visible IPC stream so stdout/stderr cannot reorder around the prompt",
        )
        assertTrue(
            source.contains("\"stdio-v1 \" + input + \" \" + stream + \" \" + stream"),
            "terminal.ck should pass the same visible stream as stdout and stderr",
        )
        assertFalse(
            source.contains("ipc::tryRead(output) + ipc::tryRead(error)"),
            "terminal.ck must not concatenate separate stdout/stderr reads in a fixed order",
        )
    }

    @Test
    fun bundledRomShellOwnsSubmittedLineEchoAndTerminalHandlesControlChars() {
        val terminal = resourceText("rom/terminal.ck")
        val shell = resourceText("rom/shell.ck")

        assertTrue(
            shell.contains("val line: String = readLine(ctx)\n        write(ctx, line + \"\\n\")"),
            "shell.ck should echo submitted lines so blank Enter is shell-owned visible output",
        )
        assertFalse(
            terminal.contains("buffer = appendText(displayId, buffer, line + \"\\n\")"),
            "terminal.ck must not locally commit submitted lines on Enter",
        )
        assertTrue(terminal.contains("ch == \"\\r\""), "terminal.ck should handle carriage return output")
        assertTrue(terminal.contains("ch == \"\\b\""), "terminal.ck should handle backspace output")
        assertTrue(terminal.contains("clearCell"), "terminal.ck should clear a cell for backspace output")
    }

    @Test
    fun bundledRomTerminalHasSymmetricAngleGlyphs() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("if (ch == \"<\")"), "terminal.ck should define a '<' glyph")
        assertTrue(
            source.contains("if (ch == \">\") { return \"10000010000010000010001000100010000\" }"),
            "terminal.ck should use a balanced seven-row '>' glyph",
        )
        assertTrue(
            source.contains("if (ch == \"<\") { return \"00001000100010001000001000001000001\" }"),
            "terminal.ck should use a balanced seven-row '<' glyph",
        )
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
