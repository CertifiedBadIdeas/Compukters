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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.canBeCalledAsExtensionOn
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits

internal object CompletionQuery {
    @OptIn(KaExperimentalApi::class)
    fun execute(
        query: AnalysisQuery.Completion,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.Completion {
        val file = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        val source = file.text
        require(query.offsetUtf16 <= source.length) { "analysis cursor exceeds source" }
        val context = CompletionContext.parse(file, source, query.offsetUtf16)
        val items = analyze(file) { collect(context, file, snapshot, limits) }
        return AnalysisResult.Completion.create(
            query.identity,
            context.replacement,
            items,
            AnalysisResultLimits(maxCompletionItems = limits.completionItems, maxDetailUtf8Bytes = limits.detailTextBytes),
        )
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.collect(
        context: CompletionContext,
        file: org.jetbrains.kotlin.psi.KtFile,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<CompletionItem> {
        if (limits.completionItems == 0) return emptyList()
        val visibility = createUseSiteVisibilityChecker(file.symbol, context.receiver, context.position)
        val rankedComparator = Comparator<RankedCompletion> { left, right -> completionRankComparator.compare(left.rank, right.rank) }
        val ranked = BoundedUniqueBest(limits.completionItems, rankedComparator, RankedCompletion::item)
        val nameMatches: (org.jetbrains.kotlin.name.Name) -> Boolean = { name ->
            name.asString().startsWith(context.prefix)
        }

        fun accept(
            symbol: KaDeclarationSymbol,
            locality: Int,
        ) {
            if (!visibility.isVisible(symbol)) return
            val named = symbol as? KaNamedSymbol ?: return
            val name = named.name.asString()
            val detail =
                requiredBoundedUtf8(
                    symbol.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES),
                    limits.detailTextBytes,
                    "completion detail",
                )
            val origin =
                when (val mapped = DeclarationOriginMapper.run { map(symbol, snapshot) }) {
                    is MappedDeclaration.Location -> mapped.value.origin
                    is MappedDeclaration.BundleTarget -> DeclarationOrigin.Bundle(mapped.identity)
                    null -> null
                }
            val item = CompletionItem(name, name, symbol.completionKind(), detail, origin)
            ranked.offer(
                RankedCompletion(
                    item,
                    CompletionRank(
                        applicability = 1,
                        prefixQuality = if (name == context.prefix) 2 else 1,
                        locality = locality,
                        nameUtf8 = name.encodeToByteArray(),
                        signatureUtf8 = detail.encodeToByteArray(),
                    ),
                ),
            )
        }
        val scopeContext = file.scopeContext(context.position)
        val receiverType = context.receiver?.expressionType
        if (receiverType == null) {
            scopeContext.scopes.forEachIndexed { scopeIndex, scopeWithKind ->
                val locality = Int.MAX_VALUE - scopeIndex
                scopeWithKind.scope
                    .callables(nameMatches)
                    .filter { callable ->
                        !callable.isExtension ||
                            scopeContext.implicitReceivers.any { receiver ->
                                callable.canBeCalledAsExtensionOn(receiver.type)
                            }
                    }.forEach { accept(it, locality) }
                scopeWithKind.scope.classifiers(nameMatches).forEach { accept(it, locality) }
            }
        } else {
            receiverType.scope?.let { memberScope ->
                memberScope.getCallableSignatures(nameMatches).forEach { accept(it.symbol, Int.MAX_VALUE) }
                memberScope.getClassifierSymbols(nameMatches).forEach { accept(it, Int.MAX_VALUE) }
            }
            scopeContext.scopes.forEachIndexed { scopeIndex, scopeWithKind ->
                scopeWithKind.scope
                    .callables(nameMatches)
                    .filter { it.isExtension && it.canBeCalledAsExtensionOn(receiverType) }
                    .forEach { accept(it, Int.MAX_VALUE - scopeIndex - 1) }
            }
        }
        return ranked
            .sorted()
            .map { it.item }
    }
}

private data class RankedCompletion(
    val item: CompletionItem,
    val rank: CompletionRank,
)

private fun org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol.completionKind(): CompletionKind =
    when (this) {
        is KaValueParameterSymbol -> {
            CompletionKind.Parameter
        }

        is KaLocalVariableSymbol -> {
            CompletionKind.LocalVariable
        }

        is KaFunctionSymbol -> {
            if (isExtension) CompletionKind.ExtensionFunction else CompletionKind.Function
        }

        is KaPropertySymbol -> {
            CompletionKind.Property
        }

        is KaTypeParameterSymbol -> {
            CompletionKind.TypeParameter
        }

        is KaNamedClassSymbol -> {
            when (classKind) {
                KaClassKind.INTERFACE -> CompletionKind.Interface

                KaClassKind.OBJECT,
                KaClassKind.COMPANION_OBJECT,
                KaClassKind.ANONYMOUS_OBJECT,
                -> CompletionKind.Object

                else -> CompletionKind.Class
            }
        }

        else -> {
            CompletionKind.Property
        }
    }
