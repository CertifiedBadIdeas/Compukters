/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.ide.analysis.CompletionCallShape
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.editor.EditorTextEdit
import ru.lazyhat.compukters.ide.editor.KotlinSmartTyping
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdeCompletionInsertionTest {
    @Test
    fun `completed lambda enters an indented block and generated closers are skipped`() {
        for (shape in listOf(
            CompletionCallShape(true, true, false),
            CompletionCallShape(true, false, true),
            CompletionCallShape(true, true, true),
        )) {
            val editor = EditorDocument("    fo")
            editor.setCaret(editor.length)
            val highlighter = IncrementalKotlinHighlighter(editor)
            KotlinSmartTyping(editor, highlighter).use { typing ->
                val plan = IdeCompletionInsertion.plan(item(shape), EditorRange(4, 6), "")
                editor.replaceRanges(plan.replacement, plan.text, emptyList(), plan.caretUtf16)
                val start = editor.caretOffset - plan.caretUtf16
                plan.automaticClosers.forEach { typing.rememberAutomaticCloser(start + it) }
                if (shape.hasRequiredArguments) {
                    typing.type("1")
                    assertEquals(EditorEditResult.NoChange, typing.type(")"))
                }
                if (shape.trailingLambda) {
                    editor.setCaret(editor.materialize().indexOf('{') + 2)
                    typing.enter()
                    val before = editor.materialize()
                    assertEquals(if (shape.hasRequiredArguments) "    foo(1) {\n        \n    }" else "    foo {\n        \n    }", before)
                    typing.type("body")
                    editor.setCaret(editor.length - 1)
                    assertEquals(EditorEditResult.NoChange, typing.type("}"))
                    assertEquals(before.replace("        \n", "        body\n"), editor.materialize())
                } else {
                    assertEquals("    foo(1)", editor.materialize())
                }
            }
            highlighter.close()
        }
    }

    @Test
    fun `calls place the caret at the first required input`() {
        assertInsertion("foo()|", CompletionCallShape(false, false, false))
        assertInsertion("foo(|)", CompletionCallShape(true, true, false))
        assertInsertion("foo(|)", CompletionCallShape(true, false, false))
        assertInsertion("foo { | }", CompletionCallShape(true, false, true))
        assertInsertion("foo(|) { }", CompletionCallShape(true, true, true))
    }

    @Test
    fun `existing call and lambda delimiters are reused without rewriting their bodies`() {
        assertInsertion("foo()|", CompletionCallShape(false, false, false), "()")
        assertInsertion("foo(|value)", CompletionCallShape(true, true, false), "(value)")
        assertInsertion("foo ( |value)", CompletionCallShape(true, true, false), " ( value)")
        assertInsertion("foo { |existing() }", CompletionCallShape(true, false, true), " { existing() }")
        assertInsertion("foo(|) { existing() }", CompletionCallShape(true, true, true), " { existing() }")
        assertInsertion("foo() { | }", CompletionCallShape(true, false, true), "() {  }")
        assertInsertion("foo(|) {  }", CompletionCallShape(true, true, true), "() {  }")
        assertInsertion("foo() { | }", CompletionCallShape(true, false, true), "()")
    }

    @Test
    fun `names without call metadata leave existing text alone`() {
        assertInsertion("foo|", null)
        assertInsertion("foo|()", null, "()")
    }

    @Test
    fun `call completion with import preserves the caret through one undo and redo`() {
        val source = "fun main() { mav }"
        val editor = EditorDocument(source)
        val start = source.indexOf("mav")
        editor.setCaret(start + 3)
        val proposal = CompletionItem("maven", "maven", CompletionKind.Function, callShape = CompletionCallShape(true, false, true))
        val plan = IdeCompletionInsertion.plan(proposal, EditorRange(start, start + 3), " }")
        val import = "import sample.maven\n\n"

        assertIs<EditorEditResult.Applied>(
            editor.replaceRanges(plan.replacement, plan.text, listOf(EditorTextEdit(EditorRange(0, 0), import)), plan.caretUtf16),
        )
        assertEquals(import + "fun main() { maven {  } }", editor.materialize())
        assertEquals(import.length + start + "maven { ".length, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals(source, editor.materialize())
        assertEquals(start + 3, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.redo())
        assertEquals(import + "fun main() { maven {  } }", editor.materialize())
        assertEquals(import.length + start + "maven { ".length, editor.caretOffset)
    }

    @Test
    fun `call completion without imports also records its inner caret for redo`() {
        val editor = EditorDocument("fo")
        editor.setCaret(2)
        val plan = IdeCompletionInsertion.plan(item(CompletionCallShape(true, true, false)), EditorRange(0, 2), "")
        assertIs<EditorEditResult.Applied>(editor.replaceRanges(plan.replacement, plan.text, emptyList(), plan.caretUtf16))
        assertEquals(4, editor.caretOffset)
        editor.undo()
        assertEquals("fo", editor.materialize())
        assertEquals(2, editor.caretOffset)
        editor.redo()
        assertEquals("foo()", editor.materialize())
        assertEquals(4, editor.caretOffset)
    }

    private fun item(shape: CompletionCallShape?) = CompletionItem("foo", "foo", CompletionKind.Function, callShape = shape)

    private fun assertInsertion(
        expected: String,
        shape: CompletionCallShape?,
        suffix: String = "",
    ) {
        val source = "fo$suffix"
        val plan = IdeCompletionInsertion.plan(item(shape), EditorRange(0, 2), suffix)
        val result = plan.text + source.substring(plan.replacement.endUtf16)
        assertEquals(expected, result.substring(0, plan.caretUtf16) + "|" + result.substring(plan.caretUtf16))
    }
}
