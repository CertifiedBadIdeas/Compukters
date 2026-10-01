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

import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.editor.EditorRange

internal data class IdeCompletionInsertion(
    val replacement: EditorRange,
    val text: String,
    val caretUtf16: Int,
    val automaticClosers: List<Int> = emptyList(),
) {
    companion object {
        // Inspect only a bounded immediate suffix, never scan or rewrite a whole existing call body.
        const val SUFFIX_LIMIT = 256

        fun plan(
            proposal: CompletionItem,
            replacement: EditorRange,
            suffix: String,
        ): IdeCompletionInsertion {
            val name = proposal.insertText
            val shape = proposal.callShape ?: return IdeCompletionInsertion(replacement, name, name.length)
            val tail = suffix.take(SUFFIX_LIMIT)

            fun skipSpaces(start: Int): Int {
                var index = start
                while (index < tail.length && (tail[index] == ' ' || tail[index] == '\t')) index++
                return index
            }

            fun reuse(
                end: Int,
                caret: Int,
                prefix: String = name,
            ): IdeCompletionInsertion =
                IdeCompletionInsertion(
                    EditorRange(replacement.startUtf16, replacement.endUtf16 + end),
                    prefix + tail.substring(0, end),
                    caret,
                    if (prefix != name) listOf(name.length + 1) else emptyList(),
                )

            fun lambdaCaret(opening: Int): Int {
                val body = skipSpaces(opening + 1)
                return if (tail.getOrNull(body) == '}' && body > opening + 1) opening + 2 else body
            }

            val opening = skipSpaces(0)
            if (tail.getOrNull(opening) == '(') {
                val inside = skipSpaces(opening + 1)
                if (tail.getOrNull(inside) != ')') return reuse(inside, name.length + inside)
                val end = inside + 1
                if (!shape.trailingLambda) {
                    return reuse(end, name.length + if (shape.hasParameters) inside else end)
                }
                val lambda = skipSpaces(end)
                if (tail.getOrNull(lambda) == '{') {
                    val body = skipSpaces(lambda + 1)
                    return reuse(body, name.length + if (shape.hasRequiredArguments) inside else lambdaCaret(lambda))
                }
                val call = name + tail.substring(0, end)
                return IdeCompletionInsertion(
                    EditorRange(replacement.startUtf16, replacement.endUtf16 + end),
                    "$call {  }",
                    if (shape.hasRequiredArguments) name.length + inside else call.length + 3,
                    listOf(call.length + 4),
                )
            }
            if (shape.trailingLambda && tail.getOrNull(opening) == '{') {
                val body = skipSpaces(opening + 1)
                return if (shape.hasRequiredArguments) {
                    reuse(body, name.length + 1, "$name()")
                } else {
                    reuse(
                        body,
                        name.length + lambdaCaret(opening),
                    )
                }
            }
            val text =
                when {
                    shape.trailingLambda && shape.hasRequiredArguments -> "$name() { }"
                    shape.trailingLambda -> "$name {  }"
                    else -> "$name()"
                }
            val caret =
                when {
                    shape.trailingLambda && !shape.hasRequiredArguments -> name.length + 3
                    shape.hasParameters -> name.length + 1
                    else -> text.length
                }
            val closers =
                buildList {
                    if (!shape.trailingLambda || shape.hasRequiredArguments) add(name.length + 1)
                    if (shape.trailingLambda) add(text.lastIndex)
                }
            return IdeCompletionInsertion(replacement, text, caret, closers)
        }
    }
}
