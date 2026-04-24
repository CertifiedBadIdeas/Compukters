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

import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.ModuleOrigin
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.HighlightTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageIdeTest {
    private val ide = LanguageIde()

    @Test
    fun providesCompletionHoverAndDefinition() {
        val completionSource =
            """
            import terminal;

            fun main() {
                terminal.
            }
            """.trimIndent()
        val completionCursor = lineAndColumnOf(completionSource, "terminal.") + 9
        val completion =
            ide.complete(
                "completion.ck",
                completionSource,
                completionCursor.first,
                completionCursor.second,
            )
        assertTrue(completion.any { it.label == "printLine" })

        val source =
            """
            import terminal;

            fun helper() {
                terminal.printLine("hi");
            }

            fun main() {
                helper();
            }
            """.trimIndent()

        val hoverPosition = lineAndColumnOf(source, "printLine")
        val hover = ide.hover("test.ck", source, hoverPosition.first, hoverPosition.second)
        assertNotNull(hover)
        assertTrue(hover.contents.contains("terminal.printLine"))

        val definitionPosition = lineAndColumnOfLast(source, "helper")
        val definition =
            ide.definition(
                "test.ck",
                source,
                definitionPosition.first,
                definitionPosition.second,
            )
        assertNotNull(definition)
        assertEquals("test.ck", definition.path)
    }

    @Test
    fun producesDiagnosticsAndHighlights() {
        val source =
            """
            import terminal;

            fun main() {
                val text: Bool = "oops";
                terminal.printLine("hi");
            }
            """.trimIndent()

        val snapshot = ide.analyze("broken.ck", source)
        assertTrue(snapshot.diagnostics.any { it.message.contains("Expected Bool") })
        assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.KEYWORD })
        assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.FUNCTION })
    }

    private fun lineAndColumnOf(
        source: String,
        needle: String,
    ): Pair<Int, Int> =
        lineAndColumnForOffset(
            source,
            source.indexOf(needle).also { require(it >= 0) },
        )

    private fun lineAndColumnOfLast(
        source: String,
        needle: String,
    ): Pair<Int, Int> =
        lineAndColumnForOffset(
            source,
            source.lastIndexOf(needle).also { require(it >= 0) },
        )

    private fun lineAndColumnForOffset(
        source: String,
        offset: Int,
    ): Pair<Int, Int> {
        var line = 0
        var column = 0
        repeat(offset) { index ->
            if (source[index] == '\n') {
                line += 1
                column = 0
            } else {
                column += 1
            }
        }
        return line to column
    }

    private operator fun Pair<Int, Int>.plus(columnDelta: Int): Pair<Int, Int> = first to (second + columnDelta)

    @Test
    fun recoversFromIncompleteImport() {
        val source = "import terminal;\nimport \nfun main() {\n    terminal.printLine(\"hi\");\n}"
        val snapshot = ide.analyze("recovery.ck", source)
        assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for incomplete import")
        // main function should be visible — this requires the AST (parser recovery)
        val completions = ide.complete("recovery.ck", source, 4, 0)
        assertTrue(completions.any { it.label == "main" }, "Should see main function after recovery")
    }

    @Test
    fun recoversFromGarbageToken() {
        val source = "import terminal;\n123\nfun main() {}"
        val snapshot = ide.analyze("garbage.ck", source)
        assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for garbage tokens")
        // main function should be visible — this requires the AST (parser recovery)
        val completions = ide.complete("garbage.ck", source, 2, 0)
        assertTrue(completions.any { it.label == "main" }, "Should see main function after recovery")
    }

    @Test
    fun importPrefixDetectsImportContext() {
        // "import te" — cursor at end
        val prefix1 = SourceTextSupport.importPrefix("import te", 9)
        assertEquals("te", prefix1)

        // "import " — cursor right after space
        val prefix2 = SourceTextSupport.importPrefix("import ", 7)
        assertEquals("", prefix2)

        // "terminal." — not import context
        val prefix3 = SourceTextSupport.importPrefix("terminal.", 9)
        assertNull(prefix3)

        // "import terminal;\nimport sy" — second import
        val prefix4 = SourceTextSupport.importPrefix("import terminal;\nimport sy", 26)
        assertEquals("sy", prefix4)
    }

    @Test
    fun completesImportModules() {
        val allModules = ide.complete("test.ck", "import ", 0, 7)
        val moduleLabels = allModules.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
        assertEquals(setOf("terminal", "stdout", "filesystem", "system", "events", "process", "strings"), moduleLabels)
    }

    @Test
    fun completesImportModulesWithPrefix() {
        val filtered = ide.complete("test.ck", "import te", 0, 9)
        val moduleLabels = filtered.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }
        assertEquals(listOf("terminal"), moduleLabels)
    }

    @Test
    fun completesImportModulesExcludingAlreadyImported() {
        val source = "import terminal;\nimport "
        val completions = ide.complete("test.ck", source, 1, 7)
        val moduleLabels = completions.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
        assertFalse(moduleLabels.contains("terminal"), "Should not suggest already-imported terminal")
        assertEquals(setOf("filesystem", "stdout", "system", "events", "process", "strings"), moduleLabels)
    }

    @Test
    fun keywordCompletionsHaveTrailingSpace() {
        val completions = ide.complete("test.ck", "imp", 0, 3)
        val importItem = completions.first { it.label == "import" }
        assertEquals("import ", importItem.insertText, "import keyword should have trailing space")
    }

    @Test
    fun literalCompletionsHaveNoTrailingSpace() {
        val completions = ide.complete("test.ck", "tru", 0, 3)
        val trueItem = completions.first { it.label == "true" }
        assertNull(trueItem.insertText, "true literal should not have trailing space")
    }

    @Test
    fun defaultRuntimeRegistryExposesBaseModuleMetadata() {
        val registry = LanguageBuiltins.defaultRuntimeRegistry

        assertTrue(registry.modules.any { it.name == "terminal" && it.origin == ModuleOrigin.BASE_VM })
        assertTrue(registry.modules.none { it.name == "monitor" })
    }

    @Test
    fun reportsUnavailableRuntimeModuleForTargetVm() {
        val terminalOnly =
            BuiltinRegistry(
                modules = listOf(requireNotNull(LanguageBuiltins.defaultRuntimeRegistry.module("terminal"))),
                globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
            )
        val ide = LanguageIde(LanguageFrontend(terminalOnly))

        val snapshot = ide.analyze("test.ck", "import filesystem;\nfun main() {}")
        assertTrue(snapshot.diagnostics.any { it.message.contains("not supported by this VM") })
    }

    @Test
    fun importCompletionUsesInjectedRuntimeRegistry() {
        val terminalOnly =
            BuiltinRegistry(
                modules = listOf(requireNotNull(LanguageBuiltins.defaultRuntimeRegistry.module("terminal"))),
                globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
            )
        val ide = LanguageIde(LanguageFrontend(terminalOnly))

        val items = ide.complete("test.ck", "import ", 0, 7)
        assertEquals(listOf("terminal"), items.map { it.label })
    }
}
