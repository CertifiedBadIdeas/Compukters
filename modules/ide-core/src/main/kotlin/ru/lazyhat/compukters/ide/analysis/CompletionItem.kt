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

package ru.lazyhat.compukters.ide.analysis

enum class CompletionKind {
    Class,
    Interface,
    TypeParameter,
    Function,
    ExtensionFunction,
    Property,
    LocalVariable,
    Parameter,
    Object,
    EnumEntry,
    Package,
    Keyword,
}

data class CompletionItem(
    val label: String,
    val insertText: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val origin: DeclarationOrigin? = null,
) {
    init {
        require(label.isNotEmpty()) { "completion label must not be empty" }
        require(insertText.isNotEmpty()) { "completion insert text must not be empty" }
        strictUtf8Size(label)
        strictUtf8Size(insertText)
        detail?.let(::strictUtf8Size)
    }
}
