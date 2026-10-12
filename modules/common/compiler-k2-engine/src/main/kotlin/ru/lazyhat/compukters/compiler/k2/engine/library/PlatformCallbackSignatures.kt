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

package ru.lazyhat.compukters.compiler.k2.engine.library

/** Canonicalize callback syntax for linking only; source metadata retains extension-function semantics. */
internal fun canonicalCallbackSignature(signature: String): String {
    if (!signature.startsWith("fun(")) return signature
    val close = signature.matchingParenthesis(3)
    val parameters = signature.substring(4, close).splitTypes().joinToString(",", transform = ::canonicalCallbackType)
    return "fun($parameters):${canonicalCallbackType(signature.substring(close + 2))}"
}

private fun canonicalCallbackType(source: String): String {
    if (source.isEmpty()) return source
    if (source.startsWith('(')) {
        val close = source.matchingParenthesis(0)
        if (close == source.lastIndex || (close == source.lastIndex - 1 && source.endsWith('?'))) {
            return canonicalCallbackType(source.substring(1, close)) + if (source.endsWith('?')) "?" else ""
        }
    }
    var parentheses = 0
    var angles = 0
    for (index in 0 until source.length - 1) {
        val character = source[index]
        if (character == '-' && source[index + 1] == '>' && parentheses == 0 && angles == 0) {
            val input = source.substring(0, index)
            val open = input.indexOf('(')
            require(open >= 0 && input.matchingParenthesis(open) == input.lastIndex) { "invalid callback type: $source" }
            val receiver = input.substring(0, open).removeSuffix(".")
            val parameters =
                buildList {
                    if (receiver.isNotEmpty()) add(canonicalCallbackType(receiver))
                    input.substring(open + 1, input.lastIndex).splitTypes().forEach { add(canonicalCallbackType(it)) }
                }
            return "Function${parameters.size}<${(parameters + canonicalCallbackType(source.substring(index + 2))).joinToString(",")}>"
        }
        when (character) {
            '(' -> parentheses++
            ')' -> parentheses--
            '<' -> angles++
            '>' -> if (index == 0 || source[index - 1] != '-') angles--
        }
    }
    val open = source.indexOf('<')
    if (open >= 0) {
        val close = source.lastIndexOf('>')
        require(close > open)
        return source.substring(0, open + 1) +
            source
                .substring(open + 1, close)
                .splitTypes()
                .joinToString(",", transform = ::canonicalCallbackType) + source.substring(close)
    }
    return source
}

private fun String.splitTypes(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var parentheses = 0
    var angles = 0
    var start = 0
    forEachIndexed { index, character ->
        when (character) {
            '(' -> {
                parentheses++
            }

            ')' -> {
                parentheses--
            }

            '<' -> {
                angles++
            }

            '>' -> {
                if (index == 0 || this[index - 1] != '-') angles--
            }

            ',' -> {
                if (parentheses == 0 && angles == 0) {
                    result += substring(start, index)
                    start = index + 1
                }
            }
        }
    }
    result += substring(start)
    return result
}

private fun String.matchingParenthesis(open: Int): Int {
    var depth = 0
    for (index in open until length) {
        if (this[index] == '(') depth++
        if (this[index] == ')' && --depth == 0) return index
    }
    error("unbalanced type signature: $this")
}
