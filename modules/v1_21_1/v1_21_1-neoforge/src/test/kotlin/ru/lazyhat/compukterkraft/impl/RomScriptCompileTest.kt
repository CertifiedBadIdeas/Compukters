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
    fun bundledRomTerminalUsesAsciiGlyphTableFastPath() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("glyphs: Array<Long>"), "terminal buffer should keep a reusable ASCII glyph table")
        assertTrue(source.contains("Array<Long>(size = 128"), "terminal should allocate the ASCII glyph table once per buffer")
        assertTrue(source.contains("strings::charCodeAt(ch, 0)"), "terminal glyph lookup should index by character code")
        assertFalse(source.contains("if (ch == \"A\""), "terminal should not linearly scan glyph names for ASCII")
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
            source.contains("buffer.displayColumns == columns(nextDisplayId) && buffer.displayRows == rows(nextDisplayId)"),
            "terminal.ck should preserve/redraw cells only when the display grid geometry is unchanged",
        )
        assertTrue(
            source.contains("newTerminalBuffer(nextDisplayId)"),
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
    fun bundledRomTerminalUsesRuntimePollInsteadOfBusyLoop() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("runtime::poll(stream)"), "terminal.ck should wait for IPC or events through one runtime poll")
        assertFalse(source.contains("ipc::tryRead(stream)"), "terminal.ck should not poll IPC from CKL")
        assertFalse(
            source.contains("while true {\n        events::tryPull()"),
            "terminal.ck should not busy-poll events from CKL",
        )
    }

    @Test
    fun bundledRomTerminalBatchesQueuedInputEventsBeforeRendering() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("fun inputBatchLimit(): Int"),
            "terminal.ck should bound non-blocking input burst draining",
        )
        assertTrue(source.contains("fun drainInputBatch("), "terminal.ck should drain queued input events in one batch")
        assertTrue(
            source.contains("events::tryPull()"),
            "terminal.ck should drain already queued input events after runtime.poll wakes it",
        )
        assertTrue(
            source.contains("renderInputLine(displayId, buffer, renderedLine, line)"),
            "terminal.ck should render the input overlay once after a batch",
        )
    }

    @Test
    fun bundledRomShellOwnsSubmittedLineEchoAndTerminalHandlesControlChars() {
        val terminal = resourceText("rom/terminal.ck")
        val stdio = resourceText("rom/stdio.ck")
        val shell = resourceText("rom/shell.ck")

        assertTrue(
            terminal.contains("ipc::write(input, line + \"\\n\")"),
            "terminal.ck should send newline-delimited stdin so empty Enter is a non-empty IPC payload",
        )
        assertTrue(
            stdio.contains("return stripLineDelimiter(ipc::read(ctx.input))"),
            "stdio.readLine should strip the stdin line delimiter before returning command text",
        )
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
    fun bundledRomTerminalWrapsRenderedInputOverlayByDisplayBounds() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("fun inputOverlayRows(displayId: Int, buffer: TerminalBuffer, line: String): Int"),
            "terminal.ck should calculate how many rows a typed input overlay occupies",
        )
        assertTrue(
            source.contains("fun clearRenderedInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String)"),
            "terminal.ck should clear every row previously occupied by typed input",
        )
        assertTrue(
            source.contains("fun renderInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String, line: String)"),
            "terminal.ck should render typed input with access to the previous overlay text",
        )
        assertTrue(
            source.contains("if (x >= cols)"),
            "terminal.ck should wrap typed input when it reaches the right display bound",
        )
        assertTrue(
            source.contains("x = 0\n            y = y + 1"),
            "terminal.ck should continue wrapped typed input on the next row",
        )
        assertTrue(
            source.contains("var renderedLine: String = \"\""),
            "terminal.ck should track the last rendered input overlay",
        )
        assertTrue(
            source.contains("renderInputLine(displayId, buffer, renderedLine, line)"),
            "terminal.ck should redraw input using previous and current overlay text",
        )
    }

    @Test
    fun bundledRomTerminalSupportsScrollbackViewportHotkeys() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("historyCells: String"),
            "terminal.ck should keep committed terminal history in the buffer state",
        )
        assertTrue(
            source.contains("historyRows: Int"),
            "terminal.ck should track the number of committed history rows",
        )
        assertTrue(
            source.contains("viewportOffset: Int"),
            "terminal.ck should track how far the user scrolled above bottom",
        )
        assertTrue(
            source.contains("fun renderViewport(displayId: Int, buffer: TerminalBuffer)"),
            "terminal.ck should redraw visible rows from committed history",
        )
        assertTrue(
            source.contains("fun scrollViewportBy(displayId: Int, buffer: TerminalBuffer, deltaRows: Int): TerminalBuffer"),
            "terminal.ck should expose viewport page scrolling as a helper",
        )
        assertTrue(
            source.contains("key == 266"),
            "terminal.ck should handle PageUp scrollback hotkeys",
        )
        assertTrue(
            source.contains("key == 267"),
            "terminal.ck should handle PageDown scrollback hotkeys",
        )
        assertTrue(
            source.contains("key == 265"),
            "terminal.ck should handle one-row scrollback up hotkeys",
        )
        assertTrue(
            source.contains("key == 264"),
            "terminal.ck should handle one-row scrollback down hotkeys",
        )
        assertTrue(
            source.contains("if (buffer.viewportOffset == 0 && line != \"\")"),
            "terminal.ck should only redraw the draft input overlay while following the bottom viewport",
        )
        assertTrue(
            source.contains("viewportOffset = 0"),
            "terminal.ck should snap back to bottom on local input edits",
        )
    }

    @Test
    fun bundledRomTerminalKeepsAutoscrollIncremental() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("fun renderAutoscrolledRows(displayId: Int, buffer: TerminalBuffer, startRow: Int)"),
            "terminal.ck should redraw only newly exposed rows after display copyRect autoscroll",
        )
        assertTrue(
            source.contains("renderAutoscrolledRows(displayId, updated, startRow)"),
            "terminal.ck should use incremental autoscroll rendering instead of full viewport redraw",
        )
        assertTrue(
            source.contains("startVisibleRow"),
            "terminal.ck should translate history cursor rows to visible rows before incremental redraw",
        )
        assertFalse(
            source.contains("if (scrolled) {\n            renderViewport(displayId, updated)\n        }"),
            "terminal.ck should not repaint the whole viewport after copyRect autoscroll",
        )
    }

    @Test
    fun bundledRomTerminalHasSymmetricAngleGlyphs() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("glyphs[60]"), "terminal.ck should define a '<' glyph")
        assertTrue(
            source.contains("glyphs[62] = 0b10000010000010000010001000100010000L"),
            "terminal.ck should use a balanced packed seven-row '>' glyph",
        )
        assertTrue(
            source.contains("glyphs[60] = 0b00001000100010001000001000001000001L"),
            "terminal.ck should use a balanced packed seven-row '<' glyph",
        )
    }

    @Test
    fun bundledRomTerminalUsesPackedBitwiseGlyphs() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("fun glyphBits(glyphs: Array<Long>, ch: String): Long"), "terminal.ck should map characters to packed glyph bits")
        assertTrue(source.contains("display::blitMono5x7Packed"), "terminal.ck should render glyphs through the packed display API")
        assertFalse(source.contains("pub struct Glyph5x7"), "terminal.ck should not allocate glyph row structs")
        assertFalse(source.contains("fun glyphRows(ch: String): Glyph5x7"), "terminal.ck should not return glyph row structs")
        assertFalse(source.contains("Glyph5x7("), "terminal.ck should not construct glyph row structs")
        assertFalse(source.contains("fun glyphPattern(ch: String): String"), "terminal.ck should not keep string glyph masks")
        assertFalse(
            Regex("return \\\"[01]{35}\\\"").containsMatchIn(source),
            "terminal.ck should not return 35-character string glyph masks",
        )
        assertTrue(
            source.contains("glyphs[62] = 0b10000010000010000010001000100010000L"),
            "terminal.ck should preserve the balanced '>' glyph as packed bits",
        )
        assertTrue(
            source.contains("glyphs[60] = 0b00001000100010001000001000001000001L"),
            "terminal.ck should preserve the balanced '<' glyph as packed bits",
        )
    }

    @Test
    fun bundledRomShellChecksExternalCommandBeforeRun() {
        val source = resourceText("rom/shell.ck")

        assertTrue(source.contains("import filesystem { exists }"), "shell.ck should query filesystem before external command launch")
        assertTrue(source.contains("exists(command + \".ck\")"), "shell.ck should reject missing commands before process::run")
        assertFalse(
            source.contains("if (process::run(command + \".ck\""),
            "shell.ck must not call process::run directly for unknown commands",
        )
    }

    @Test
    fun bundledRomStdioUsesTaggedDescriptorOnly() {
        val source = resourceText("rom/stdio.ck")

        assertTrue(source.contains("stdio-v1"), "rom/stdio.ck must emit tagged stdio-v1 descriptors")
        assertFalse(source.contains("return ctx.input +"), "rom/stdio.ck must not emit untagged descriptors")
    }

    @Test
    fun bundledRomIncludesYesProgram() {
        val index = resourceText("rom/rom.index")
        assertContains(index.lineSequence().map { it.trim() }.toList(), "yes.ck")

        val source = resourceText("rom/yes.ck")
        assertTrue(source.contains("fromArgument(process::argument())"), "yes.ck should use stdio-v1 descriptors")
        assertTrue(source.contains("while true"), "yes.ck should keep writing until the VM stops the process")
        assertTrue(source.contains("\"y\""), "yes.ck should default to Unix-like 'y' output")
        assertTrue(source.contains("println(ctx, text)"), "yes.ck should emit one line per iteration")
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
                val details =
                    artifact.analysis.diagnostics.joinToString { diagnostic ->
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
