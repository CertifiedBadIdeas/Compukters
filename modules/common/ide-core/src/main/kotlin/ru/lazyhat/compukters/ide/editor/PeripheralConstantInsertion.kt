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

package ru.lazyhat.compukters.ide.editor

import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalState
import ru.lazyhat.compukters.ide.highlight.KotlinLineLexer
import java.util.Locale

/** A source edit independent of Minecraft, applied atomically by the editor controller. */
object PeripheralConstantInsertion {
    data class Insertion(
        val offset: Int,
        val text: String,
        val identifier: String,
    )

    fun plan(
        source: String,
        name: String,
    ): Insertion? {
        require(name.isNotBlank())
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        var state = KotlinLexicalState()
        val visible = StringBuilder()
        val code = StringBuilder()
        var insertion = 0
        var header = true
        var directives = false
        var annotationDepth = 0
        var offset = 0
        for (line in source.splitToSequence('\n')) {
            val scan = KotlinLineLexer.scan(line, state)
            state = scan.endState
            val raw = CharArray(line.length) { ' ' }
            val masked = raw.copyOf()
            for (span in scan.spans) {
                if (span.kind in setOf(KotlinLexicalKind.LineComment, KotlinLexicalKind.BlockComment) || span.inDocumentation) continue
                for (i in span.startUtf16 until span.endUtf16) raw[i] = line[i]
                if (span.kind !in
                    setOf(
                        KotlinLexicalKind.String,
                        KotlinLexicalKind.Escape,
                        KotlinLexicalKind.MultilineString,
                        KotlinLexicalKind.Character,
                    )
                ) {
                    for (i in span.startUtf16 until span.endUtf16) masked[i] = line[i]
                }
            }
            val content = String(masked).trim()
            val next = minOf(source.length, offset + line.length + 1)
            val directive = content.startsWith("package ") || content.startsWith("import ") || content.startsWith("@file:")
            val annotation = annotationDepth > 0 || content.startsWith("@file:")
            val comment = scan.spans.any { it.kind == KotlinLexicalKind.BlockComment || it.kind == KotlinLexicalKind.LineComment }
            val declarationDocumentation = line.trimStart().startsWith("/**")
            if (header && !declarationDocumentation &&
                (directive || annotation || (content.isEmpty() && (!directives || !comment)) || (offset == 0 && line.startsWith("#!")))
            ) {
                insertion = next
                if (directive) directives = true
                if (annotation) annotationDepth += content.count { it == '(' || it == '[' } - content.count { it == ')' || it == ']' }
            } else {
                header = false
            }
            visible.append(raw).append('\n')
            code.append(masked).append('\n')
            offset = next
        }
        val literal = quote(name)
        // Match actual code, excluding comments and multiline strings.
        val constants = Regex("\\bconst\\s+val\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(?::\\s*String\\s*)?=\\s*(\"(?:\\\\.|[^\"\\\\])*\")")
        var scopeDepth = 0
        var scanned = 0
        for (constant in constants.findAll(visible)) {
            while (scanned < constant.range.first) {
                when (code[scanned++]) {
                    '{' -> scopeDepth++
                    '}' -> scopeDepth--
                }
            }
            if (scopeDepth == 0 && constant.groupValues[2] == literal &&
                code.substring(constant.range.first, constant.range.first + 5) == "const"
            ) {
                return null
            }
        }
        val words =
            name
                .replace(Regex("([\\p{Ll}\\d])([\\p{Lu}])"), "$1_$2")
                .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
                .trim('_')
                .uppercase(Locale.ROOT)
        val base = words.ifEmpty { "PERIPHERAL" }.let { if (it.first().isDigit()) "PERIPHERAL_$it" else it }
        val used = Regex("[\\p{L}_][\\p{L}\\p{N}_]*").findAll(code).map { it.value }.toSet()
        var identifier = base
        var suffix = 2
        while (identifier in used) identifier = "${base}_${suffix++}"
        val prefix = if (insertion > 0 && source[insertion - 1] != '\n') newline else ""
        val separator = if (insertion < source.length && !source.substring(insertion).startsWith("const val ")) newline else ""
        return Insertion(insertion, "${prefix}const val $identifier = $literal$newline$separator", identifier)
    }

    private fun quote(name: String): String =
        buildString {
            append('"')
            for (char in name) {
                append(
                    when (char) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '$' -> "\\$"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> if (char.isISOControl()) "\\u" + char.code.toString(16).padStart(4, '0') else char.toString()
                    },
                )
            }
            append('"')
        }
}
