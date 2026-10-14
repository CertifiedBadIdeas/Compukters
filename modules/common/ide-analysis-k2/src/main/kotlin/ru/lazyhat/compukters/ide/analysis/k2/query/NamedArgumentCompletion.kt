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

package ru.lazyhat.compukters.ide.analysis.k2.query

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Names come from the actual call candidates; value completion remains independent. */
@OptIn(KaExperimentalApi::class)
internal object NamedArgumentCompletion {
    data class Candidate(
        val item: CompletionItem,
        val best: Boolean,
        val name: String,
    )

    fun KaSession.candidates(
        context: CompletionContext,
        file: KtFile,
        limits: AnalysisLimits,
    ): List<Candidate> {
        if (context.receiver != null) return emptyList()
        val offset = context.replacement.endUtf16
        val list = CallArguments.listAt(file, offset, file.textLength) ?: return emptyList()
        val call = list.parent as? KtCallElement ?: return emptyList()
        val index = CallArguments.indexAt(list, offset)
        val argument = list.arguments.getOrNull(index)
        val nameExpression = argument?.getArgumentName()?.referenceExpression
        val expression = argument?.getArgumentExpression()
        val existingName = nameExpression != null && context.replacement.startUtf16 == nameExpression.textRange.startOffset
        if (!existingName) {
            if (argument?.isNamed() == true || argument?.isSpread == true) return emptyList()
            if (expression != null &&
                (
                    expression.textRange.startOffset != context.replacement.startUtf16 ||
                        expression.textRange.endOffset != offset
                )
            ) {
                return emptyList()
            }
            if (expression == null && context.prefix.isNotEmpty()) return emptyList()
        }
        val matcher = CompletionNameMatcher(context.prefix)
        return (call as KtElement).resolveToCallCandidates().flatMap { candidate ->
            val function = candidate.candidate as? KaFunctionCall<*> ?: return@flatMap emptyList()
            val signature = function.partiallyAppliedSymbol.signature
            if (!signature.symbol.hasStableParameterNames) return@flatMap emptyList()
            val occupied =
                function.valueArgumentMapping
                    .filterKeys { it != expression }
                    .values
                    .map { it.name.asString() }
                    .toMutableSet()
            // Explicit names remain occupied even while an incomplete call prevents argument mapping.
            list.arguments.filter { it != argument }.mapNotNullTo(occupied) { it.getArgumentName()?.asName?.asString() }
            signature.valueParameters.mapNotNull { parameter ->
                val name = parameter.name.asString()
                if (name in occupied || matcher.quality(name) == 0) return@mapNotNull null
                val renderedName = parameter.name.render()
                val label = "$renderedName ="
                Candidate(
                    CompletionItem(
                        label,
                        if (existingName) renderedName else "$renderedName = ",
                        CompletionKind.Parameter,
                        requiredBoundedUtf8(
                            parameter.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT),
                            limits.detailTextBytes,
                            "named argument type",
                        ),
                        matchedNameRanges =
                            matcher.ranges(name).map { range ->
                                if (renderedName.startsWith('`')) {
                                    EditorRange(range.startUtf16 + 1, range.endUtf16 + 1)
                                } else {
                                    range
                                }
                            },
                    ),
                    candidate.isInBestCandidates,
                    name,
                )
            }
        }
    }
}
