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

package ru.lazyhat.compukters.ide.documentation

/** Semantic identities stay independent of English labels and translated descriptions. */
data class KDocDocument(
    val description: String,
    val tags: List<KDocTag>,
) {
    companion object {
        fun parse(text: String): KDocDocument {
            val description = StringBuilder()
            val tags = mutableListOf<KDocTag>()
            var kind: KDocTagKind? = null
            var name: String? = null
            var subject: String? = null
            var body = StringBuilder()
            var fence: Char? = null
            var fenceLength = 0

            fun finish() {
                kind?.let { tags += KDocTag(it, name.orEmpty(), subject, body.toString().trimBlankLines()) }
            }
            for (line in text.lines()) {
                val trimmed = line.trimStart()
                val marker = trimmed.firstOrNull()
                val markerLength = if (marker == '`' || marker == '~') trimmed.takeWhile { it == marker }.length else 0
                val indentation = line.takeWhile(Char::isWhitespace)
                val blockStart = indentation.length < 4 && '\t' !in indentation
                if (blockStart && markerLength >= 3) {
                    if (fence == null) {
                        fence = marker
                        fenceLength = markerLength
                    } else if (fence == marker && markerLength >= fenceLength && trimmed.drop(markerLength).isBlank()) {
                        fence = null
                    }
                }
                val match = if (fence == null && blockStart) TAG.find(trimmed) else null
                if (match != null) {
                    finish()
                    name = match.groupValues[1]
                    kind = KDocTagKind.entries.firstOrNull { name in it.names } ?: KDocTagKind.Unknown
                    val value = trimmed.substring(match.range.last + 1).trimStart()
                    if (kind.hasSubject) {
                        val end =
                            if (value.startsWith('[')) {
                                value.indexOf(']').takeIf { it >= 0 }?.plus(1) ?: value.length
                            } else {
                                value.indexOfFirst(Char::isWhitespace).takeIf {
                                    it >=
                                        0
                                }
                                    ?: value.length
                            }
                        subject = value.substring(0, end).removeSurrounding("[", "]").takeIf(String::isNotEmpty)
                        body = StringBuilder(value.substring(end).trimStart())
                    } else {
                        subject = null
                        body = StringBuilder(value)
                    }
                } else {
                    val target = if (kind == null) description else body
                    if (target.isNotEmpty()) target.append('\n')
                    target.append(line)
                }
            }
            finish()
            return KDocDocument(description.toString().trimBlankLines(), tags.toList())
        }

        private fun String.trimBlankLines(): String = lines().dropWhile(String::isBlank).dropLastWhile(String::isBlank).joinToString("\n")

        private val TAG = Regex("^@([A-Za-z]+)(?=\\s|\\[|$)")
    }
}

data class KDocTag(
    val kind: KDocTagKind,
    val sourceName: String,
    val subject: String?,
    val description: String,
)

enum class KDocTagKind(
    val hasSubject: Boolean,
    vararg val names: String,
) {
    Param(true, "param"),
    Return(false, "return"),
    Throws(true, "throws", "exception"),
    Receiver(false, "receiver"),
    Property(true, "property"),
    Constructor(false, "constructor"),
    Sample(true, "sample"),
    See(true, "see"),
    Author(false, "author"),
    Since(false, "since"),
    Suppress(false, "suppress"),
    Unknown(false),
}
