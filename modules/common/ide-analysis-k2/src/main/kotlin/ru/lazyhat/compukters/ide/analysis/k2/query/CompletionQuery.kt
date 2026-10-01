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

import com.intellij.psi.PsiComment
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.asSignature
import org.jetbrains.kotlin.analysis.api.components.canBeCalledAsExtensionOn
import org.jetbrains.kotlin.analysis.api.components.createExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.fullyExpandedType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.components.staticMemberScope
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaFunctionalTypeRenderer
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeNullability
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.CompletionCallShape
import ru.lazyhat.compukters.ide.analysis.CompletionCallablePresentation
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionSymbol
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits

internal object CompletionQuery {
    @OptIn(KaExperimentalApi::class)
    private val lambdaLabelTypeRenderer =
        KaTypeRendererForSource.WITH_SHORT_NAMES.with {
            expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_EXPANDED_TYPE
            functionalTypeRenderer = KaFunctionalTypeRenderer.AS_FUNCTIONAL_TYPE
        }

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
        val items =
            if (query.trigger == CompletionTrigger.Automatic && context.isFunctionDeclarationName) {
                emptyList()
            } else {
                analyze(file) { collect(context, file, snapshot, limits) }
            }
        return AnalysisResult.Completion.create(
            query.identity,
            context.replacement,
            items,
            source.length,
            AnalysisResultLimits(maxCompletionItems = limits.completionItems, maxDetailUtf8Bytes = limits.detailTextBytes),
        )
    }

    @OptIn(KaExperimentalApi::class, KaIdeApi::class)
    private fun KaSession.collect(
        context: CompletionContext,
        file: org.jetbrains.kotlin.psi.KtFile,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<CompletionItem> {
        if (limits.completionItems == 0) return emptyList()
        val visibility = createUseSiteVisibilityChecker(file.symbol, context.receiver, context.position)
        val rankedComparator = Comparator<RankedCompletion> { left, right -> completionRankComparator.compare(left.rank, right.rank) }
        val ranked = BoundedUniqueBest(limits.completionItems, rankedComparator, RankedCompletion::identity)
        val scopedFqNames = mutableSetOf<String>()
        val nameMatches: (org.jetbrains.kotlin.name.Name) -> Boolean = { name ->
            name.asString().startsWith(context.prefix)
        }
        val scopeContext = file.scopeContext(context.position)
        val receiverType = context.receiver?.expressionType
        val nameExpression =
            generateSequence(context.position as com.intellij.psi.PsiElement?) { it.parent }
                .filterIsInstance<KtSimpleNameExpression>()
                .firstOrNull()
        val extensionChecker = nameExpression?.let { createExtensionCandidateChecker(file, it, context.receiver) }

        fun functionSignature(function: KaFunctionSymbol): KaFunctionSignature<*> {
            val signature = function.asSignature()
            if (!function.isExtension) return signature
            val applicable =
                extensionChecker?.computeApplicability(function) as? KaExtensionApplicabilityResult.Applicable ?: return signature
            return signature.substitute(applicable.substitutor)
        }

        KeywordCompletion.candidates(context).forEach { keyword ->
            val item = CompletionItem(keyword, keyword, CompletionKind.Keyword)
            ranked.offer(
                RankedCompletion(
                    item,
                    "keyword\u0000$keyword",
                    CompletionRank(
                        applicability = 2,
                        prefixQuality = if (keyword == context.prefix) 2 else 1,
                        locality = Int.MAX_VALUE,
                        nameUtf8 = keyword.encodeToByteArray(),
                        signatureUtf8 = ByteArray(0),
                    ),
                ),
            )
        }

        fun accept(
            symbol: KaDeclarationSymbol,
            locality: Int,
            resolvedSignature: KaCallableSignature<*>? = null,
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
                    is MappedDeclaration.PlatformTarget -> DeclarationOrigin.Platform(mapped.identity)
                    null -> null
                }
            val fqName = symbol.fqName()
            fqName?.let(scopedFqNames::add)
            val signature = (resolvedSignature as? KaFunctionSignature<*>) ?: (symbol as? KaFunctionSymbol)?.let(::functionSignature)
            val item =
                CompletionItem(
                    signature?.let { completionLabel(it, name) } ?: name,
                    name,
                    symbol.completionKind(),
                    detail,
                    origin,
                    fqName?.let { CompletionSymbol(it, null) },
                    callShape = if (context.allowsCall()) signature?.let { callShape(it) } else null,
                    callablePresentation = signature?.let { callablePresentation(it) },
                )
            ranked.offer(
                RankedCompletion(
                    item,
                    fqName?.let { "$it\u0000$detail" } ?: "scope\u0000$name\u0000$detail",
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
            val projectCandidates = snapshot.projectCompletionIndex.lookup(context.prefix, limits.completionItems)
            val platformCandidates = snapshot.platformCompletionIndex.lookup(context.prefix, limits.completionItems)
            val exactCandidates = projectCandidates + platformCandidates
            (projectCandidates + platformCandidates).forEach { declaration ->
                if (declaration.fqName in scopedFqNames) return@forEach
                val importPlan =
                    KotlinImportPlanner.plan(
                        file,
                        declaration,
                        exactCandidates.filter { it.shortName == declaration.shortName },
                    )
                val function = indexedFunction(declaration, snapshot)
                val signature = function?.let(::functionSignature)
                val item =
                    CompletionItem(
                        signature?.let { completionLabel(it, declaration.shortName) } ?: declaration.shortName,
                        importPlan.insertText,
                        declaration.kind,
                        declaration.signature,
                        declaration.origin,
                        importPlan.symbol,
                        importPlan.additionalEdits,
                        if (context.allowsCall()) signature?.let { callShape(it) } else null,
                        signature?.let { callablePresentation(it) },
                    )
                val locality =
                    when (declaration.origin) {
                        DeclarationOrigin.Project -> 1_000_000
                        is DeclarationOrigin.Platform -> if (declaration.origin.identity in snapshot.moduleIdentities.values) 500_000 else 0
                    }
                ranked.offer(
                    RankedCompletion(
                        item,
                        "${declaration.fqName}\u0000${declaration.signature}",
                        CompletionRank(
                            applicability = 1,
                            prefixQuality = if (declaration.shortName == context.prefix) 2 else 1,
                            locality = locality,
                            nameUtf8 = declaration.shortName.encodeToByteArray(),
                            signatureUtf8 = declaration.signature.encodeToByteArray(),
                        ),
                    ),
                )
            }
        } else {
            receiverType.scope?.let { memberScope ->
                memberScope.getCallableSignatures(nameMatches).forEach { accept(it.symbol, Int.MAX_VALUE, it) }
                memberScope.getClassifierSymbols(nameMatches).forEach { accept(it, Int.MAX_VALUE) }
            }
            val receiverClass =
                when (val receiver = context.receiver) {
                    is KtNameReferenceExpression -> {
                        receiver.resolveSymbol() as? KaNamedClassSymbol
                    }

                    is KtDotQualifiedExpression -> {
                        (receiver.selectorExpression as? KtNameReferenceExpression)?.resolveSymbol() as? KaNamedClassSymbol
                    }

                    else -> {
                        null
                    }
                }
            val staticScope: KaScope? = receiverClass?.staticMemberScope
            staticScope?.let {
                it.callables(nameMatches).forEach { symbol -> accept(symbol, Int.MAX_VALUE) }
                it.classifiers(nameMatches).forEach { symbol -> accept(symbol, Int.MAX_VALUE) }
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

    private fun CompletionContext.allowsCall(): Boolean {
        val ancestors = generateSequence(position as com.intellij.psi.PsiElement?) { it.parent }.toList()
        if (ancestors.any { it is KtStringTemplateExpression } && ancestors.none { it is KtBlockStringTemplateEntry }) return false
        return ancestors.none {
            it is KtImportDirective || it is KtPackageDirective || it is KtCallableReferenceExpression ||
                it is KtTypeReference || it is KtSimpleNameStringTemplateEntry || it is PsiComment
        }
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.indexedFunction(
        declaration: GlobalCompletionDeclaration,
        snapshot: AdmittedK2Snapshot,
    ): KaFunctionSymbol? {
        if (declaration.kind != CompletionKind.Function && declaration.kind != CompletionKind.ExtensionFunction &&
            declaration.kind != CompletionKind.MemberFunction
        ) {
            return null
        }
        if (declaration.origin == DeclarationOrigin.Project) {
            val file = snapshot.files[declaration.sourcePath] ?: return null
            return generateSequence(file.findElementAt(declaration.sourceRange.startUtf16)) { it.parent }
                .filterIsInstance<KtNamedFunction>()
                .firstOrNull { it.textRange.startOffset == declaration.sourceRange.startUtf16 }
                ?.symbol
        }
        val fqName = FqName(declaration.fqName)
        val candidates =
            findTopLevelCallables(
                fqName.parent(),
                Name.identifier(declaration.shortName),
            ).filterIsInstance<KaFunctionSymbol>().toList()
        return candidates.singleOrNull()
            ?: candidates.singleOrNull { canonicalPlatformSignature(it) == declaration.signature }
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.callShape(signature: KaFunctionSignature<*>): CompletionCallShape {
        val parameters = signature.valueParameters
        val last = parameters.lastOrNull()
        val trailingLambda = trailingLambdaType(last?.symbol, last?.returnType) != null
        val ordinary = if (trailingLambda) parameters.dropLast(1) else parameters
        return CompletionCallShape(
            parameters.isNotEmpty(),
            ordinary.any { !it.symbol.hasDefaultValue && !it.symbol.isVararg },
            trailingLambda,
        )
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.trailingLambdaType(
        parameter: KaValueParameterSymbol?,
        parameterType: KaType?,
    ): KaFunctionType? {
        if (parameter == null || parameter.isVararg) return null
        val type = parameterType?.fullyExpandedType as? KaFunctionType ?: return null
        return type.takeIf { it.nullability == KaTypeNullability.NON_NULLABLE }
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.completionLabel(
        signature: KaFunctionSignature<*>,
        name: String,
    ): String {
        val single = signature.valueParameters.singleOrNull()
        val lambdaType = trailingLambdaType(single?.symbol, single?.returnType)
        return if (lambdaType != null) {
            buildString {
                append(name)
                append(" { ")
                append(requireNotNull(single).name.asString())
                append(": ")
                append(lambdaType.render(lambdaLabelTypeRenderer, Variance.INVARIANT))
                if (single.symbol.hasDefaultValue) append(" = …")
                append(" }")
            }
        } else {
            signature.valueParameters.joinToString(prefix = "$name(", postfix = ")") { parameter ->
                buildString {
                    if (parameter.symbol.isVararg) append("vararg ")
                    append(parameter.name.asString())
                    append(": ")
                    append(parameter.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
                    if (parameter.symbol.hasDefaultValue) append(" = …")
                }
            }
        }
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.callablePresentation(signature: KaFunctionSignature<*>): CompletionCallablePresentation =
        CompletionCallablePresentation(
            signature.symbol.receiverParameter
                ?.returnType
                ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT),
            signature.symbol.callableId
                ?.packageName
                ?.asString()
                ?.takeIf { it.isNotEmpty() },
            signature.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT),
        )
}

private data class RankedCompletion(
    val item: CompletionItem,
    val identity: String,
    val rank: CompletionRank,
)

private fun KaDeclarationSymbol.fqName(): String? =
    when (this) {
        is KaCallableSymbol -> callableId?.asSingleFqName()?.asString()
        is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()
        else -> null
    }

private fun org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol.completionKind(): CompletionKind =
    when (this) {
        is KaValueParameterSymbol -> {
            CompletionKind.Parameter
        }

        is KaLocalVariableSymbol -> {
            CompletionKind.LocalVariable
        }

        is KaFunctionSymbol -> {
            when {
                isExtension -> CompletionKind.ExtensionFunction
                callableId?.classId != null -> CompletionKind.MemberFunction
                else -> CompletionKind.Function
            }
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
