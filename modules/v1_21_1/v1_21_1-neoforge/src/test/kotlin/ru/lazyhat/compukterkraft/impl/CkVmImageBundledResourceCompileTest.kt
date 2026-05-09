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

import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class CkVmImageBundledResourceCompileTest {
    private val classLoader: ClassLoader = CkVmImageBundledResourceCompileTest::class.java.classLoader

    @Test
    fun bundledFirmwareAndRomCompileToCkVmImage() {
        val sources = bundledSources()
        val sourceLoader = MapSourceLoader(sources)
        val frontend = LanguageFrontend(LanguageBuiltins.defaultRuntimeRegistry)

        val failures =
            sources.entries.mapNotNull { (path, source) ->
                val artifact =
                    try {
                        frontend.compileImage(path, source, sourceLoader)
                    } catch (error: Throwable) {
                        return@mapNotNull buildString {
                            appendLine(path)
                            appendLine("  error: ${error.message ?: error::class.qualifiedName}")
                        }
                    }
                if (artifact.image != null) {
                    null
                } else {
                    val diagnostics =
                        artifact.bytecode.analysis.diagnostics.joinToString(separator = "\n") { diagnostic ->
                            val range = diagnostic.range
                            if (range == null) {
                                "  - ${diagnostic.message}"
                            } else {
                                "  - ${diagnostic.message} @ ${range.start.line}:${range.start.column}-${range.end.line}:${range.end.column}"
                            }
                        }
                    buildString {
                        appendLine(path)
                        if (diagnostics.isNotBlank()) {
                            appendLine(diagnostics)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            fail("Bundled CKL resources failed to compile to CkVmImage:\n" + failures.joinToString("\n"))
        }
    }

    private fun bundledSources(): Map<String, String> {
        val romFiles = romIndex()
        val firmwareFiles = firmwareIndex()
        assertNotNull(romFiles.firstOrNull(), "rom.index is empty")
        assertNotNull(firmwareFiles.firstOrNull(), "firmware.index is empty")
        return buildMap {
            for (firmwareFile in firmwareFiles) {
                put("firmware/$firmwareFile", resourceText("firmware/$firmwareFile"))
            }
            for (romFile in romFiles) {
                put(romFile, resourceText("rom/$romFile"))
            }
        }
    }

    private fun romIndex(): List<String> =
        resourceText("rom/rom.index")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .toList()

    private fun firmwareIndex(): List<String> =
        resourceText("firmware/firmware.index")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .toList()

    private fun resourceText(path: String): String =
        classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: fail("$path missing from classpath")
}
