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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.highlight

import ru.lazyhat.compukters.ide.editor.EditorDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinLineLexerTest {
    @Test
    fun `highlights value class modifier while preserving identifier and literal boundaries`() {
        val source = "value class Id(val raw: Int); values; `value`; \"value\"; /* value */ // value"
        val tokens = scan(source).spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind }

        assertEquals("value" to KotlinLexicalKind.Keyword, tokens.first())
        assertTrue("class" to KotlinLexicalKind.Keyword in tokens)
        assertTrue("values" to KotlinLexicalKind.Identifier in tokens)
        assertTrue("`value`" to KotlinLexicalKind.Identifier in tokens)
        assertTrue("\"value\"" to KotlinLexicalKind.String in tokens)
        assertTrue("/* value */" to KotlinLexicalKind.BlockComment in tokens)
        assertTrue("// value" to KotlinLexicalKind.LineComment in tokens)
    }

    @Test
    fun `numeric ranges member calls annotations and Unicode escapes have distinct spans`() {
        val source = "@file:pkg.Annotation val x = 1..10; 0xFFuL; 0b101L; 1.5e-2f; 1.toString(); foo (x); \"\\u0041\""
        val tokens = scan(source).spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind }
        assertTrue("@file:pkg.Annotation" to KotlinLexicalKind.Annotation in tokens)
        for (literal in listOf("1", "10", "0xFFuL", "0b101L", "1.5e-2f")) assertTrue(literal to KotlinLexicalKind.Number in tokens)
        assertTrue("toString" to KotlinLexicalKind.FunctionCall in tokens)
        assertTrue("foo" to KotlinLexicalKind.FunctionCall in tokens)
        assertTrue("\\u0041" to KotlinLexicalKind.Escape in tokens)
        assertFalse(tokens.any { it.first.contains("..") && it.second == KotlinLexicalKind.Number })
    }

    @Test
    fun `classifies the immediate lexical surface with non-overlapping spans`() {
        val source = "@Ann fun Main(argument: Int) = 1.5e+2f + \"a\\nb\" + 'x' // tail"
        val line = scan(source)

        assertEquals(
            setOf(
                KotlinLexicalKind.Annotation,
                KotlinLexicalKind.TypeLike,
                KotlinLexicalKind.Keyword,
                KotlinLexicalKind.Identifier,
                KotlinLexicalKind.Number,
                KotlinLexicalKind.String,
                KotlinLexicalKind.Escape,
                KotlinLexicalKind.Character,
                KotlinLexicalKind.LineComment,
                KotlinLexicalKind.Operator,
            ),
            line.spans.mapTo(linkedSetOf()) { it.kind },
        )
        line.spans.zipWithNext().forEach { (left, right) -> assertTrue(left.endUtf16 <= right.startUtf16) }
        assertEquals(KotlinLexicalState(), line.endState)
    }

    @Test
    fun `tracks nested block comments between lines and resumes Kotlin`() {
        val document = EditorDocument("val x = /* outer /* nested\nstill */ end */ val y = 1")
        val first = KotlinLineLexer.scan(document, 0, KotlinLexicalState())
        val second = KotlinLineLexer.scan(document, 1, first.endState)

        assertEquals(2, first.endState.blockCommentDepth)
        assertEquals(KotlinLexicalState(), second.endState)
        assertEquals(KotlinLexicalKind.BlockComment, first.spans.last().kind)
        assertEquals(KotlinLexicalKind.BlockComment, second.spans.first().kind)
        assertTrue(second.spans.any { it.kind == KotlinLexicalKind.Keyword })
        assertTrue(second.spans.any { it.kind == KotlinLexicalKind.Number })
    }

    @Test
    fun `tracks triple strings while ordinary malformed literals stop at the line`() {
        val multiline = EditorDocument("val s = \"\"\"hello\nworld\"\"\" + 1")
        val first = KotlinLineLexer.scan(multiline, 0, KotlinLexicalState())
        val second = KotlinLineLexer.scan(multiline, 1, first.endState)
        assertTrue(first.endState.inMultilineString)
        assertFalse(second.endState.inMultilineString)
        assertEquals(KotlinLexicalKind.MultilineString, first.spans.last().kind)
        assertEquals(KotlinLexicalKind.MultilineString, second.spans.first().kind)

        val malformed = scan("val a = \"unterminated")
        assertEquals(KotlinLexicalState(), malformed.endState)
        assertEquals(KotlinLexicalKind.String, malformed.spans.last().kind)
    }

    @Test
    fun `lexes Kotlin expressions inside quoted string templates`() {
        val source = """println("${'$'}{values.size}: ${'$'}{values[1]}")"""
        val line = scan(source)

        assertEquals(
            listOf(
                "println" to KotlinLexicalKind.FunctionCall,
                "(" to KotlinLexicalKind.Operator,
                "\"" to KotlinLexicalKind.String,
                "${'$'}{" to KotlinLexicalKind.Escape,
                "values" to KotlinLexicalKind.Identifier,
                "." to KotlinLexicalKind.Operator,
                "size" to KotlinLexicalKind.Identifier,
                "}" to KotlinLexicalKind.Escape,
                ": " to KotlinLexicalKind.String,
                "${'$'}{" to KotlinLexicalKind.Escape,
                "values" to KotlinLexicalKind.Identifier,
                "[" to KotlinLexicalKind.Operator,
                "1" to KotlinLexicalKind.Number,
                "]" to KotlinLexicalKind.Operator,
                "}" to KotlinLexicalKind.Escape,
                "\"" to KotlinLexicalKind.String,
                ")" to KotlinLexicalKind.Operator,
            ),
            line.spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind },
        )
    }

    @Test
    fun `lexes short identifiers inside quoted string templates`() {
        val source = "\"${'$'}name\""

        assertEquals(
            listOf(
                "\"" to KotlinLexicalKind.String,
                "${'$'}" to KotlinLexicalKind.Escape,
                "name" to KotlinLexicalKind.Identifier,
                "\"" to KotlinLexicalKind.String,
            ),
            scan(source).spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind },
        )
    }

    @Test
    fun `excludes line separators and respects keyword and identifier boundaries`() {
        val document = EditorDocument("whenish `fun` when\r\nnext")
        val first = KotlinLineLexer.scan(document, 0, KotlinLexicalState())

        assertEquals(18, first.sourceLengthUtf16)
        assertEquals(
            listOf(KotlinLexicalKind.Identifier, KotlinLexicalKind.Identifier, KotlinLexicalKind.Keyword),
            first.spans.map { it.kind },
        )
        assertTrue(first.spans.all { it.endUtf16 <= first.sourceLengthUtf16 })
    }

    private fun scan(source: String): KotlinLexicalLine = KotlinLineLexer.scan(EditorDocument(source), 0, KotlinLexicalState())
}
