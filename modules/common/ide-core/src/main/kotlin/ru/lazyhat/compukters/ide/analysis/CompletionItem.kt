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

import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

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
    TypeAlias,
    MemberFunction,
    PeripheralProvider,
}

data class CompletionTextEdit(
    val range: EditorRange,
    val text: String,
) {
    init {
        strictUtf8Size(text)
    }
}

data class CompletionSymbol(
    val fqName: String,
    val importFqName: String?,
) {
    init {
        require(fqName.isNotEmpty()) { "completion symbol name must not be empty" }
        strictUtf8Size(fqName)
        importFqName?.let { importName ->
            require(importName.isNotEmpty()) { "completion import name must not be empty" }
            strictUtf8Size(importName)
        }
    }
}

/** Semantic call shape; required arguments exclude the trailing lambda itself. */
data class CompletionCallShape(
    val hasParameters: Boolean,
    val hasRequiredArguments: Boolean,
    val trailingLambda: Boolean,
) {
    init {
        require(hasParameters || (!hasRequiredArguments && !trailingLambda)) { "parameterless call cannot require arguments or a lambda" }
    }
}

/** Independent callable columns; receiver keeps declaration notation while the result is specialized. */
data class CompletionCallablePresentation(
    val receiverType: String?,
    val packageName: String?,
    val returnType: String,
) {
    init {
        require(returnType.isNotEmpty()) { "completion return type must not be empty" }
        strictUtf8Size(returnType)
        receiverType?.let {
            require(it.isNotEmpty()) { "completion receiver type must not be empty" }
            strictUtf8Size(it)
        }
        packageName?.let {
            require(it.isNotEmpty()) { "completion package must not be empty" }
            strictUtf8Size(it)
        }
    }
}

@ConsistentCopyVisibility
data class CompletionItem private constructor(
    val label: String,
    val insertText: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val origin: DeclarationOrigin? = null,
    val symbol: CompletionSymbol? = null,
    val additionalEdits: List<CompletionTextEdit> = emptyList(),
    val callShape: CompletionCallShape? = null,
    val callablePresentation: CompletionCallablePresentation? = null,
    val matchedNameRanges: List<EditorRange> = emptyList(),
) {
    constructor(
        label: String,
        insertText: String,
        kind: CompletionKind,
        detail: String? = null,
        origin: DeclarationOrigin? = null,
        symbol: CompletionSymbol? = null,
        additionalEdits: Collection<CompletionTextEdit> = emptyList(),
        callShape: CompletionCallShape? = null,
        callablePresentation: CompletionCallablePresentation? = null,
        matchedNameRanges: Collection<EditorRange> = emptyList(),
    ) : this(
        label,
        insertText,
        kind,
        detail,
        origin,
        symbol,
        Collections.unmodifiableList(additionalEdits.toList()),
        callShape,
        callablePresentation,
        Collections.unmodifiableList(matchedNameRanges.toList()),
    )

    init {
        require(label.isNotEmpty()) { "completion label must not be empty" }
        require(insertText.isNotEmpty()) { "completion insert text must not be empty" }
        require(
            callShape == null || kind == CompletionKind.Function || kind == CompletionKind.ExtensionFunction ||
                kind == CompletionKind.MemberFunction,
        ) {
            "only function completions carry a call shape"
        }
        require(
            callablePresentation == null || kind == CompletionKind.Function || kind == CompletionKind.ExtensionFunction ||
                kind == CompletionKind.MemberFunction,
        ) {
            "only function completions carry callable presentation"
        }
        strictUtf8Size(label)
        strictUtf8Size(insertText)
        detail?.let(::strictUtf8Size)
        var end = 0
        matchedNameRanges.forEach { range ->
            require(range.length > 0 && range.startUtf16 >= end && range.endUtf16 <= label.length) {
                "completion name-match ranges must be nonempty ordered disjoint label ranges"
            }
            requireUtf16Boundary(label, range.startUtf16, "completion match range start")
            requireUtf16Boundary(label, range.endUtf16, "completion match range end")
            end = range.endUtf16
        }
    }
}
