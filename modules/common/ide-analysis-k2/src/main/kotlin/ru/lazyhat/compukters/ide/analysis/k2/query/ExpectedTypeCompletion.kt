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
import org.jetbrains.kotlin.analysis.api.components.builtinTypes
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.expectedType
import org.jetbrains.kotlin.analysis.api.components.fullyExpandedType
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.components.staticMemberScope
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/** Analysis types remain session-local; the wire carries only the resulting item order. */
@OptIn(KaExperimentalApi::class)
internal object ExpectedTypeCompletion {
    fun KaSession.types(
        context: CompletionContext,
        file: KtFile,
    ): List<KaType> {
        val offset = context.replacement.endUtf16
        val list = CallArguments.listAt(file, offset, file.textLength) ?: return emptyList()
        val index = CallArguments.indexAt(list, offset)
        val argument = list.arguments.getOrNull(index)
        val name = argument?.getArgumentName()?.referenceExpression
        if (name != null && offset <= name.textRange.endOffset) return emptyList()
        val expression = argument?.getArgumentExpression()
        if (expression != null && (expression.textRange.endOffset != offset || argument.isSpread)) {
            return usable(listOfNotNull(context.position.expectedType))
        }
        val call = list.parent as? KtCallElement ?: return emptyList()
        val candidates = (call as KtElement).resolveToCallCandidates()
        val best = candidates.filter { it.isInBestCandidates }.ifEmpty { candidates }
        val types =
            usable(
                best.mapNotNull { candidate ->
                    val function = candidate.candidate as? KaFunctionCall<*> ?: return@mapNotNull null
                    val parameters = function.partiallyAppliedSymbol.signature.valueParameters
                    val mapped = expression?.let { function.valueArgumentMapping[it] }
                    val named = argument?.getArgumentName()?.asName
                    val parameter =
                        mapped ?: if (named != null) {
                            parameters.firstOrNull { it.name == named }
                        } else {
                            parameters.getOrNull(index) ?: parameters.lastOrNull()?.takeIf { it.symbol.isVararg }
                        }
                    parameter?.returnType
                },
            )
        return types.ifEmpty { usable(listOfNotNull(context.position.expectedType)) }
    }

    private fun KaSession.usable(types: List<KaType>): List<KaType> =
        types.filter { it.fullyExpandedType !is KaErrorType && it.fullyExpandedType !is KaTypeParameterType }

    fun KaSession.quality(
        actual: KaType?,
        expected: List<KaType>,
    ): Int {
        if (expected.isEmpty() || actual == null || actual.fullyExpandedType is KaErrorType) return 0
        return expected.maxOfOrNull { type ->
            when {
                actual.semanticallyEquals(type) -> 3
                actual.isSubtypeOf(type) -> 2
                else -> 0
            }
        } ?: 0
    }

    fun KaSession.literals(expected: List<KaType>): List<Pair<String, KaType>> =
        buildList {
            if (quality(builtinTypes.boolean, expected) > 0) {
                add("true" to builtinTypes.boolean)
                add("false" to builtinTypes.boolean)
            }
            if (quality(builtinTypes.nullableNothing, expected) > 0) add("null" to builtinTypes.nullableNothing)
        }

    fun KaSession.enumEntries(expected: List<KaType>): List<KaEnumEntrySymbol> =
        expected
            .flatMap { type ->
                val symbol = type.expandedSymbol?.takeIf { it.classKind == KaClassKind.ENUM_CLASS }
                symbol
                    ?.staticMemberScope
                    ?.callables { true }
                    ?.filterIsInstance<KaEnumEntrySymbol>()
                    ?.toList()
                    .orEmpty()
            }.distinctBy { it.callableId }
}
