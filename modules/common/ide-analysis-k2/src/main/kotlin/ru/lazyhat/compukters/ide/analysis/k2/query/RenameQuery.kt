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
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
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
                val shifts = edits.mapValues { (_, ranges) -> SourceOffsetShift(ranges, query.newName.length) }
                val expectedErrors = prepared.errors.map { it.shift(shifts) }.toMutableList()
                errors(candidate, limits).forEach { error ->
                    rejectUnless(expectedErrors.remove(error), "The new name introduces an analysis error: ${error.message}")
                }
                val actual = SemanticBindings.collect(candidate, limits)
                val editedSites = prepared.locations.toSet()
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
        val originalErrors = errors(snapshot, limits)
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
                ).any(owner::hasModifier)
            },
            "Inheritance and convention-based declarations are not supported yet",
        )
        rejectUnless(
            !(named is KtNamedFunction && named.name == "main" && named.parent is org.jetbrains.kotlin.psi.KtFile),
            "The project entry point must remain main",
        )
        val allBindings = SemanticBindings.collect(snapshot, limits)
        val locations =
            (
                listOf(target) +
                    allBindings.filter { it.target == ProjectBinding(target) && it.name == named.name }.map { it.site }
            ).distinct()
                .sortedWith(compareBy({ it.path.value }, { it.range.startUtf16 }))
        if (locations.size > limits.references) throw AnalysisOutputLimitException("rename references exceed negotiated limit")
        return PreparedRename(locations, allBindings, originalErrors)
    }

    private fun errors(
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<EditorDiagnostic> =
        buildList {
            snapshot.files.forEach { (path, file) ->
                ProgressManager.checkCanceled()
                rejectUnless(
                    PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null,
                    "Repair syntax errors before Rename: ${path.value}",
                )
                analyze(file) {
                    DiagnosticQuery
                        .collect(this, path, file, limits)
                        .filter { it.severity == EditorDiagnosticSeverity.Error }
                        .forEach { error ->
                            rejectUnless(error.range != null, "Rename cannot validate an error without a source range")
                            if (size >= limits.diagnostics) {
                                throw AnalysisOutputLimitException("rename diagnostic count exceeds negotiated limit")
                            }
                            add(error)
                        }
                }
            }
        }
}

private data class PreparedRename(
    val locations: List<DeclarationLocation.Source>,
    val bindings: List<Binding>,
    val errors: List<EditorDiagnostic>,
)

private fun rejectUnless(
    condition: Boolean,
    detail: String,
) {
    if (!condition) throw RenameAdmissionException(detail)
}

internal class RenameAdmissionException(
    message: String,
) : RuntimeException(message)

private fun EditorDiagnostic.shift(shifts: Map<VirtualSourcePath, SourceOffsetShift>): EditorDiagnostic {
    val shift = shifts[path] ?: return this
    val original = range ?: return this
    return copy(range = EditorRange(shift.offset(original.startUtf16), shift.offset(original.endUtf16)))
}
