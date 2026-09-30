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

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** A read-only refactoring admission: speculative PSI must always be restored. */
internal object RenameQuery {
    fun execute(
        query: AnalysisQuery.Rename,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.References {
        val lexer = KotlinLexer().apply { start(query.newName) }
        rejectUnless(lexer.tokenType == KtTokens.IDENTIFIER && lexer.tokenEnd == query.newName.length, "Enter one Kotlin identifier")
        lexer.advance()
        rejectUnless(lexer.tokenType == null, "Enter one Kotlin identifier")
        val prepared = ReadAction.compute<PreparedRename, RuntimeException> { prepare(query, snapshot, limits) }
        val edits =
            prepared.locations.groupBy { it.path }.mapValues { (_, locations) ->
                locations.map { it.range }.sortedBy { it.startUtf16 }
            }
        val changedTexts =
            ReadAction.compute<Map<VirtualSourcePath, String>, RuntimeException> {
                edits.mapValues { (path, ranges) ->
                    val text = StringBuilder(snapshot.files.getValue(path).text)
                    ranges.asReversed().forEach { text.replace(it.startUtf16, it.endUtf16, query.newName) }
                    text.toString()
                }
            }
        snapshot.preview(changedTexts, limits) { candidate ->
            ReadAction.run<RuntimeException> {
                requireNoErrors(candidate, limits)
                val actual = bindings(candidate, limits)
                val editedSites = prepared.locations.toSet()
                val shifts = edits.mapValues { (_, ranges) -> RenameOffsetShift(ranges, query.newName.length) }
                val expected =
                    prepared.bindings.map { binding ->
                        binding.copy(
                            site = binding.site.shift(shifts),
                            name = if (binding.site in editedSites) query.newName.removeSurrounding("`") else binding.name,
                            target =
                                (binding.target as? ProjectBinding)?.let { ProjectBinding(it.location.shift(shifts)) }
                                    ?: binding.target,
                        )
                    }
                rejectUnless(expected == actual, "The new name changes resolution of another reference")
            }
        }
        return AnalysisResult.References.create(
            query.identity,
            prepared.locations,
            snapshot.sourceLengthsUtf16,
            AnalysisResultLimits(maxReferences = limits.references),
        )
    }

    @OptIn(KaExperimentalApi::class)
    private fun prepare(
        query: AnalysisQuery.Rename,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): PreparedRename {
        requireNoErrors(snapshot, limits)
        val file = requireNotNull(snapshot.files[query.path])
        val target =
            analyze(file) {
                val symbol = resolveCursorSymbols(file, query.offsetUtf16).singleOrNull()
                rejectUnless(symbol != null, "Place the caret on one unambiguous project symbol")
                val mapped = DeclarationOriginMapper.run { map(requireNotNull(symbol), snapshot) } as? MappedDeclaration.Location
                val location = mapped?.value as? DeclarationLocation.Source
                rejectUnless(location?.origin == DeclarationOrigin.Project, "Library symbols are read-only")
                requireNotNull(location)
            }
        val declaration =
            generateSequence(snapshot.files.getValue(target.path).findElementAt(target.range.startUtf16)) { it.parent }
                .filterIsInstance<KtNamedDeclaration>()
                .firstOrNull { it.nameIdentifier?.textRange?.startOffset == target.range.startUtf16 }
        rejectUnless(declaration != null, "This declaration cannot be renamed")
        val named = requireNotNull(declaration)
        val family = generateSequence(named as com.intellij.psi.PsiElement) { it.parent }.filterIsInstance<KtNamedDeclaration>()
        rejectUnless(
            family.none { owner ->
                listOf(
                    KtTokens.OVERRIDE_KEYWORD,
                    KtTokens.OPEN_KEYWORD,
                    KtTokens.ABSTRACT_KEYWORD,
                    KtTokens.OPERATOR_KEYWORD,
                    KtTokens.INFIX_KEYWORD,
                ).any(owner::hasModifier)
            },
            "Inheritance and convention-based declarations are not supported yet",
        )
        rejectUnless(
            !(named is KtNamedFunction && named.name == "main" && named.parent is org.jetbrains.kotlin.psi.KtFile),
            "The project entry point must remain main",
        )
        val allBindings = bindings(snapshot, limits)
        val locations =
            (
                listOf(target) +
                    allBindings.filter { it.target == ProjectBinding(target) && it.name == named.name }.map { it.site }
            ).distinct()
                .sortedWith(compareBy({ it.path.value }, { it.range.startUtf16 }))
        if (locations.size > limits.references) throw AnalysisOutputLimitException("rename references exceed negotiated limit")
        return PreparedRename(locations, allBindings)
    }

    private fun requireNoErrors(
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ) {
        snapshot.files.forEach { (path, file) ->
            ProgressManager.checkCanceled()
            analyze(file) {
                val error = DiagnosticQuery.collect(this, path, file, limits).firstOrNull { it.severity == EditorDiagnosticSeverity.Error }
                rejectUnless(error == null, "Rename requires a project without analysis errors: ${path.value}: ${error?.message}")
            }
        }
    }

    @OptIn(KaExperimentalApi::class)
    private fun bindings(
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<Binding> {
        val result = ArrayList<Binding>()
        var signatureBytes = 0L
        snapshot.files.entries.sortedBy { it.key.value }.forEach { (path, file) ->
            analyze(file) {
                file.accept(
                    object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            ProgressManager.checkCanceled()
                            if (expression is KtNameReferenceExpression) {
                                if (result.size >=
                                    limits.semanticTokens
                                ) {
                                    throw AnalysisOutputLimitException("rename binding count exceeds negotiated limit")
                                }
                                val symbol = expression.resolveSymbol() ?: expression.mainReference.resolveToSymbols().singleOrNull()
                                val target = symbol?.let { bindingKey(it, snapshot) }
                                if (target is SignatureBinding) {
                                    signatureBytes += target.value.encodeToByteArray().size
                                    if (signatureBytes >
                                        limits.sourceBytes.toLong() * 4
                                    ) {
                                        throw AnalysisOutputLimitException("rename binding text exceeds negotiated limit")
                                    }
                                }
                                result +=
                                    Binding(
                                        DeclarationLocation.Source(
                                            DeclarationOrigin.Project,
                                            path,
                                            EditorRange(expression.textRange.startOffset, expression.textRange.endOffset),
                                        ),
                                        expression.getReferencedName(),
                                        target,
                                    )
                            }
                            super.visitSimpleNameExpression(expression)
                        }
                    },
                )
            }
        }
        return result
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.bindingKey(
        symbol: KaSymbol,
        snapshot: AdmittedK2Snapshot,
    ): BindingKey? {
        val mapped = DeclarationOriginMapper.run { map(symbol, snapshot) }
        if (mapped is MappedDeclaration.Location && mapped.value is DeclarationLocation.Source &&
            mapped.value.origin == DeclarationOrigin.Project
        ) {
            return ProjectBinding(mapped.value)
        }
        if (mapped is MappedDeclaration.PlatformTarget) return PlatformBinding(mapped)
        return (symbol as? KaDeclarationSymbol)?.let { SignatureBinding(it.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)) }
    }
}

private data class PreparedRename(
    val locations: List<DeclarationLocation.Source>,
    val bindings: List<Binding>,
)

private data class Binding(
    val site: DeclarationLocation.Source,
    val name: String,
    val target: BindingKey?,
)

private sealed interface BindingKey

private data class ProjectBinding(
    val location: DeclarationLocation.Source,
) : BindingKey

private data class PlatformBinding(
    val target: MappedDeclaration.PlatformTarget,
) : BindingKey

private data class SignatureBinding(
    val value: String,
) : BindingKey

private fun DeclarationLocation.Source.shift(shifts: Map<VirtualSourcePath, RenameOffsetShift>): DeclarationLocation.Source =
    shifts[path]?.let { copy(range = EditorRange(it.offset(range.startUtf16), it.offset(range.endUtf16))) } ?: this

private class RenameOffsetShift(
    private val ranges: List<EditorRange>,
    newLength: Int,
) {
    private val deltas =
        IntArray(ranges.size + 1).apply {
            ranges.forEachIndexed { index, range -> this[index + 1] = this[index] + newLength - range.length }
        }

    fun offset(value: Int): Int {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle].endUtf16 <= value) low = middle + 1 else high = middle
        }
        return value + deltas[low]
    }
}

private fun rejectUnless(
    condition: Boolean,
    detail: String,
) {
    if (!condition) throw RenameAdmissionException(detail)
}

internal class RenameAdmissionException(
    message: String,
) : RuntimeException(message)
