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

package compukter.system.shell

sealed interface LexResult {
    data class Success(val words: Array<String>) : LexResult

    data class Error(val message: String) : LexResult
}

private const val MAXIMUM_WORDS = 17
private const val MAXIMUM_WORD_CODE_UNITS = 256
private const val MAXIMUM_ARGUMENT_CODE_UNITS = 256

private const val UNQUOTED = 0
private const val SINGLE_QUOTED = 1
private const val DOUBLE_QUOTED = 2
private const val ESCAPED = 3

fun lex(source: String): LexResult {
    val words = arrayOf("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    val token = CharArray(MAXIMUM_WORD_CODE_UNITS)
    var wordCount = 0
    var tokenLength = 0
    var totalLength = 0
    var tokenPresent = false
    var state = UNQUOTED
    var escapedState = UNQUOTED
    var index = 0

    while (index < source.length) {
        val character = source[index]
        if (state == ESCAPED) {
            if (tokenLength >= MAXIMUM_WORD_CODE_UNITS) {
                return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
            }
            if (totalLength >= MAXIMUM_ARGUMENT_CODE_UNITS) {
                return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
            }
            token[tokenLength] = character
            tokenLength = tokenLength + 1
            totalLength = totalLength + 1
            tokenPresent = true
            state = escapedState
        } else if (state == SINGLE_QUOTED) {
            if (character == '\'') {
                state = UNQUOTED
            } else {
                if (tokenLength >= MAXIMUM_WORD_CODE_UNITS) {
                    return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
                }
                if (totalLength >= MAXIMUM_ARGUMENT_CODE_UNITS) {
                    return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
                }
                token[tokenLength] = character
                tokenLength = tokenLength + 1
                totalLength = totalLength + 1
            }
        } else if (state == DOUBLE_QUOTED) {
            if (character == '"') {
                state = UNQUOTED
            } else if (character == '\\') {
                escapedState = DOUBLE_QUOTED
                state = ESCAPED
            } else {
                if (tokenLength >= MAXIMUM_WORD_CODE_UNITS) {
                    return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
                }
                if (totalLength >= MAXIMUM_ARGUMENT_CODE_UNITS) {
                    return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
                }
                token[tokenLength] = character
                tokenLength = tokenLength + 1
                totalLength = totalLength + 1
            }
        } else if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
            if (tokenPresent) {
                if (wordCount >= MAXIMUM_WORDS) return LexResult.Error("too many words (maximum 17)")
                words[wordCount] = token.concatToString(0, tokenLength)
                wordCount = wordCount + 1
                tokenLength = 0
                tokenPresent = false
            }
        } else if (character == '\'') {
            tokenPresent = true
            state = SINGLE_QUOTED
        } else if (character == '"') {
            tokenPresent = true
            state = DOUBLE_QUOTED
        } else if (character == '\\') {
            tokenPresent = true
            escapedState = UNQUOTED
            state = ESCAPED
        } else {
            if (tokenLength >= MAXIMUM_WORD_CODE_UNITS) {
                return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
            }
            if (totalLength >= MAXIMUM_ARGUMENT_CODE_UNITS) {
                return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
            }
            token[tokenLength] = character
            tokenLength = tokenLength + 1
            totalLength = totalLength + 1
            tokenPresent = true
        }
        index = index + 1
    }

    if (state == ESCAPED) return LexResult.Error("trailing escape")
    if (state == SINGLE_QUOTED) return LexResult.Error("unterminated single quote")
    if (state == DOUBLE_QUOTED) return LexResult.Error("unterminated double quote")
    if (tokenPresent) {
        if (wordCount >= MAXIMUM_WORDS) return LexResult.Error("too many words (maximum 17)")
        words[wordCount] = token.concatToString(0, tokenLength)
        wordCount = wordCount + 1
    }
    return LexResult.Success(words.copyOfRange(0, wordCount))
}
