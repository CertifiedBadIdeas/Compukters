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

fun lex(source: String): LexResult {
    val maximumWords = 17
    val maximumWordCodeUnits = 256
    val maximumArgumentCodeUnits = 256
    val unquoted = 0
    val singleQuoted = 1
    val doubleQuoted = 2
    val escaped = 3
    val words = arrayOf("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    val token = CharArray(maximumWordCodeUnits)
    var wordCount = 0
    var tokenLength = 0
    var totalLength = 0
    var tokenPresent = false
    var state = unquoted
    var escapedState = unquoted
    var index = 0

    while (index < source.length) {
        val character = source[index]
        if (state == escaped) {
            if (tokenLength >= maximumWordCodeUnits) {
                return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
            }
            if (totalLength >= maximumArgumentCodeUnits) {
                return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
            }
            token[tokenLength] = character
            tokenLength = tokenLength + 1
            totalLength = totalLength + 1
            tokenPresent = true
            state = escapedState
        } else if (state == singleQuoted) {
            if (character == '\'') {
                state = unquoted
            } else {
                if (tokenLength >= maximumWordCodeUnits) {
                    return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
                }
                if (totalLength >= maximumArgumentCodeUnits) {
                    return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
                }
                token[tokenLength] = character
                tokenLength = tokenLength + 1
                totalLength = totalLength + 1
            }
        } else if (state == doubleQuoted) {
            if (character == '"') {
                state = unquoted
            } else if (character == '\\') {
                escapedState = doubleQuoted
                state = escaped
            } else {
                if (tokenLength >= maximumWordCodeUnits) {
                    return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
                }
                if (totalLength >= maximumArgumentCodeUnits) {
                    return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
                }
                token[tokenLength] = character
                tokenLength = tokenLength + 1
                totalLength = totalLength + 1
            }
        } else if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
            if (tokenPresent) {
                if (wordCount >= maximumWords) return LexResult.Error("too many words (maximum 17)")
                words[wordCount] = token.concatToString(0, tokenLength)
                wordCount = wordCount + 1
                tokenLength = 0
                tokenPresent = false
            }
        } else if (character == '\'') {
            tokenPresent = true
            state = singleQuoted
        } else if (character == '"') {
            tokenPresent = true
            state = doubleQuoted
        } else if (character == '\\') {
            tokenPresent = true
            escapedState = unquoted
            state = escaped
        } else {
            if (tokenLength >= maximumWordCodeUnits) {
                return LexResult.Error("word too long (maximum 256 UTF-16 code units)")
            }
            if (totalLength >= maximumArgumentCodeUnits) {
                return LexResult.Error("arguments too long (maximum 256 UTF-16 code units)")
            }
            token[tokenLength] = character
            tokenLength = tokenLength + 1
            totalLength = totalLength + 1
            tokenPresent = true
        }
        index = index + 1
    }

    if (state == escaped) return LexResult.Error("trailing escape")
    if (state == singleQuoted) return LexResult.Error("unterminated single quote")
    if (state == doubleQuoted) return LexResult.Error("unterminated double quote")
    if (tokenPresent) {
        if (wordCount >= maximumWords) return LexResult.Error("too many words (maximum 17)")
        words[wordCount] = token.concatToString(0, tokenLength)
        wordCount = wordCount + 1
    }
    return LexResult.Success(words.copyOfRange(0, wordCount))
}
