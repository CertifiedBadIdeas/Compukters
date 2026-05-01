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
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit
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
            fun main() {
                terminal::
            }
            """.trimIndent()
        val completionCursor = lineAndColumnOf(completionSource, "terminal::") + 10
        val completion =
            ide.complete(
                "completion.ck",
                completionSource,
                completionCursor.first,
                completionCursor.second,
            )
        assertTrue(completion.any { it.label == "println" })

        val source =
            """
            fun helper() {
                terminal::println("hi");
            }

            fun main() {
                helper();
            }
            """.trimIndent()

        val hoverPosition = lineAndColumnOf(source, "println")
        val hover = ide.hover("test.ck", source, hoverPosition.first, hoverPosition.second)
        assertNotNull(hover)
        assertTrue(hover.contents.contains("terminal::println"))

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
            fun main() {
                val text: Bool = "oops";
                terminal::println("hi");
            }
            """.trimIndent()

        val snapshot = ide.analyze("broken.ck", source)
        assertTrue(snapshot.diagnostics.any { it.message.contains("Expected Bool") })
        assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.KEYWORD })
        assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.FUNCTION })
    }

    @Test
    fun completesBuiltinMembersAfterDoubleColon() {
        val source =
            """
            fun main() { terminal:: }
            """.trimIndent()
        val column = source.indexOf("terminal::") + "terminal::".length

        val items = ide.complete("main.ck", source, line = 0, column = column)

        assertTrue(items.any { it.label == "println" }, items.joinToString { it.label })
        assertTrue(items.any { it.label == "write" }, items.joinToString { it.label })
    }

    @Test
    fun suggestsBuiltinMemberWithNamespaceAndImportEdit() {
        val source = "fun main() { pri }"
        val cursor = lineAndColumnOf(source, "pri") + 3

        val items = ide.complete("main.ck", source, cursor.first, cursor.second)
        val println = items.single { it.label == "println" && it.sourceNamespace == "terminal" }

        assertEquals("println()", println.insertText)
        assertEquals("println(".length, println.cursorOffset)
        assertEquals(listOf(TextEdit(0, 0, "import terminal { println };\n")), println.additionalTextEdits)
    }

    @Test
    fun updatesExistingBuiltinImportGroupInCompletionEdit() {
        val source = "import terminal { clear };\nfun main() { pri }"
        val cursor = lineAndColumnOf(source, "pri") + 3

        val items = ide.complete("main.ck", source, cursor.first, cursor.second)
        val println = items.single { it.label == "println" && it.sourceNamespace == "terminal" }

        assertEquals(
            listOf(TextEdit("import terminal { ".length, "import terminal { clear".length, "clear, println")),
            println.additionalTextEdits,
        )
    }

    @Test
    fun suggestsUserFileFunctionWithPathAndImportEdit() {
        val loader = MapSourceLoader(mapOf("main.ck" to "fun main() { ad }", "lib/math.ck" to "fun add(): Int { return 1; }"))
        val ide = LanguageIde(sourceIndex = loader)
        val source = loader.read("main.ck")!!
        val cursor = lineAndColumnOf(source, "ad") + 2

        val items = ide.complete("main.ck", source, cursor.first, cursor.second)
        val add = items.single { it.label == "add" && it.sourceNamespace == "lib/math.ck" }

        assertEquals(listOf(TextEdit(0, 0, "import \"lib/math.ck\" { add };\n")), add.additionalTextEdits)
    }

    @Test
    fun suggestsUserFileClassWithPathAndImportEdit() {
        val loader = MapSourceLoader(mapOf("main.ck" to "fun main() { Cou }", "model.ck" to "class Counter(var value: Int) {}"))
        val ide = LanguageIde(sourceIndex = loader)
        val source = loader.read("main.ck")!!
        val cursor = lineAndColumnOf(source, "Cou") + 3

        val items = ide.complete("main.ck", source, cursor.first, cursor.second)
        val counter = items.single { it.label == "Counter" && it.sourceNamespace == "model.ck" }

        assertEquals("Counter(", counter.insertText)
        assertEquals(listOf(TextEdit(0, 0, "import \"model.ck\" { Counter };\n")), counter.additionalTextEdits)
    }

    @Test
    fun completesMembersAfterThisDot() {
        val source =
            """
            class Counter(var value: Int) {
                fun current(): Int { return this. }
            }
            fun main() {}
            """.trimIndent()
        val cursor = lineAndColumnOf(source, "this.") + 5

        val items = ide.complete("counter.ck", source, cursor.first, cursor.second)

        assertTrue(items.any { it.label == "value" }, items.joinToString { it.label })
    }

    @Test
    fun completesMembersAfterInstanceVariableDot() {
        val source =
            """
            class Counter(var value: Int) {
                fun current(): Int { return this.value; }
            }
            fun main() {
                val counter: Counter = Counter(value = 1);
                terminal::println(counter.)
            }
            """.trimIndent()
        val cursor = lineAndColumnOf(source, "counter.)") + "counter.".length

        val items = ide.complete("counter.ck", source, cursor.first, cursor.second)

        assertTrue(items.any { it.label == "value" }, items.joinToString { it.label })
        assertTrue(items.any { it.label == "current" }, items.joinToString { it.label })
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
        val source = "import \nfun main() {\n    terminal::println(\"hi\");\n}"
        val snapshot = ide.analyze("recovery.ck", source)
        assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for incomplete import")
        // main function should be visible — this requires the AST (parser recovery)
        val completions = ide.complete("recovery.ck", source, 4, 0)
        assertTrue(completions.any { it.label == "main" }, "Should see main function after recovery")
    }

    @Test
    fun recoversFromGarbageToken() {
        val source = "123\nfun main() {}"
        val snapshot = ide.analyze("garbage.ck", source)
        assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for garbage tokens")
        // main function should be visible — this requires the AST (parser recovery)
        val completions = ide.complete("garbage.ck", source, 1, 0)
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

        // "import alpha;\nimport sy" — second import
        val prefix4 = SourceTextSupport.importPrefix("import alpha;\nimport sy", 23)
        assertEquals("sy", prefix4)
    }

    @Test
    fun completesAmbientModulesAsVisibleSymbols() {
        val allModules = ide.complete("test.ck", "", 0, 0)
        val moduleLabels = allModules.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
        assertEquals(setOf("terminal", "stdout", "filesystem", "system", "events", "process", "strings"), moduleLabels)
    }

    @Test
    fun completesAmbientModulesWithPrefix() {
        val filtered = ide.complete("test.ck", "te", 0, 2)
        val moduleLabels = filtered.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }
        assertEquals(listOf("terminal"), moduleLabels)
    }

    @Test
    fun doesNotCompleteBuiltinModulesInImportContext() {
        val source = "import "
        val completions = ide.complete("test.ck", source, 0, 7)
        val moduleLabels = completions.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
        assertTrue(moduleLabels.isEmpty(), "Should not suggest built-in modules for import")
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

        val snapshot = ide.analyze("test.ck", "fun main() { filesystem::list(); }")
        assertTrue(snapshot.diagnostics.any { it.message.contains("Unknown namespace") })
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

        val items = ide.complete("test.ck", "", 0, 0)
        assertEquals(listOf("terminal"), items.filter { it.kind == CompletionItemKind.MODULE }.map { it.label })
    }

    @Test
    fun formatsDocumentThroughIdeFacade() {
        val source = "fun main(){terminal::println(\"hi\");}"

        val result = ide.formatDocument("main.ck", source)

        assertEquals("fun main() {\n    terminal::println(\"hi\");\n}\n", applySingleEdit(source, result.edits))
    }

    @Test
    fun cleanupDocumentThroughIdeFacadeUsesSourceIndex() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "main.ck" to "import \"lib.ck\" { add, unused };\nfun main(){add();}",
                    "lib.ck" to "fun add() {}\nfun unused() {}",
                ),
            )
        val ide = LanguageIde(sourceIndex = loader)
        val source = loader.read("main.ck")!!

        val result = ide.cleanupDocument("main.ck", source)
        val cleaned = applySingleEdit(source, result.edits)

        assertTrue(cleaned.contains("import \"lib.ck\" { add };"), cleaned)
        assertFalse(cleaned.contains("unused"), cleaned)
    }

    private fun applySingleEdit(
        source: String,
        edits: List<TextEdit>,
    ): String {
        assertEquals(1, edits.size)
        val edit = edits.single()
        return source.substring(0, edit.startOffset) + edit.replacement + source.substring(edit.endOffset)
    }
}
