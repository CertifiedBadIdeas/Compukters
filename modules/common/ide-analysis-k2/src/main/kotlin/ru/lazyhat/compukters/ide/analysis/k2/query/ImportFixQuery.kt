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
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticHighlight
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.ImportQuickFix
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Explicit imports must resolve this reference without changing existing resolved bindings. */
internal object ImportFixQuery {
    @OptIn(KaExperimentalApi::class)
    fun execute(
        query: AnalysisQuery.ImportFixes,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.ImportFixes {
        val original = snapshot.sourceText(query.path)
        require(query.offsetUtf16 in 0..original.length)
        val coordinates = KotlinSourceCoordinates(original)
        val cursor = coordinates.toPsi(query.offsetUtf16)
        val prepared =
            ReadAction.compute<Prepared?, RuntimeException> {
                val file = requireNotNull(snapshot.files[query.path])
                require(cursor <= file.textLength)
                val reference =
                    listOfNotNull(
                        file.findElementAt(cursor),
                        if (cursor > 0) file.findElementAt(cursor - 1) else null,
                    ).firstNotNullOfOrNull { leaf ->
                        generateSequence(leaf) { it.parent }.filterIsInstance<KtNameReferenceExpression>().firstOrNull()
                    }
                        ?: return@compute null
                if (cursor !in reference.textRange.startOffset..reference.textRange.endOffset ||
                    generateSequence(reference.parent) { it.parent }.any { it is KtImportDirective || it is KtPackageDirective }
                ) {
                    return@compute null
                }
                if (snapshot.files.values.any { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) != null }) return@compute null
                val range = EditorRange(reference.textRange.startOffset, reference.textRange.endOffset)
                val originalErrors = errors(snapshot, limits)
                if (originalErrors.none {
                        it.path == query.path && it.highlight == EditorDiagnosticHighlight.UnresolvedReference &&
                            it.range?.let { error -> error.startUtf16 <= range.startUtf16 && error.endUtf16 >= range.endUtf16 } == true
                    }
                ) {
                    return@compute null
                }
                val name = reference.getReferencedName()
                val candidates =
                    (
                        snapshot.projectCompletionIndex.lookup(name, limits.completionItems) +
                            snapshot.platformCompletionIndex.lookup(name, limits.completionItems)
                    ).filter {
                        it.shortName == name && (
                            it.origin == DeclarationOrigin.Project ||
                                (it.origin as? DeclarationOrigin.Platform)?.identity in snapshot.moduleIdentities.values
                        )
                    }
                val fixes =
                    candidates
                        .distinctBy { it.fqName }
                        .mapNotNull { declaration ->
                            val plan = KotlinImportPlanner.plan(file, declaration, candidates)
                            if (plan.insertText != name || plan.symbol.importFqName == null) {
                                null
                            } else {
                                ImportQuickFix(declaration.fqName, plan.additionalEdits.single())
                            }
                        }.sortedBy { it.fqName }
                        .take(minOf(limits.completionItems, MAX_PREVIEWS))
                Prepared(file.text, range, fixes, originalErrors, SemanticBindings.collect(snapshot, limits))
            }
        val items =
            prepared
                ?.fixes
                ?.filter { fix ->
                    ProgressManager.checkCanceled()
                    val offset = fix.edit.range.startUtf16
                    val changed = prepared.source.substring(0, offset) + fix.edit.text + prepared.source.substring(offset)
                    snapshot.preview(mapOf(query.path to changed), limits) { candidate ->
                        ReadAction.compute<Boolean, RuntimeException> {
                            val shifts = mapOf(query.path to SourceOffsetShift(listOf(fix.edit.range), fix.edit.text.length))
                            val shifted =
                                prepared.reference.let {
                                    EditorRange(
                                        it.startUtf16 + fix.edit.text.length,
                                        it.endUtf16 + fix.edit.text.length,
                                    )
                                }
                            val file = candidate.files.getValue(query.path)
                            val reference =
                                generateSequence(file.findElementAt(shifted.startUtf16)) { it.parent }
                                    .filterIsInstance<KtNameReferenceExpression>()
                                    .firstOrNull()
                            val target =
                                analyze(file) {
                                    when (
                                        val symbol =
                                            reference?.resolveSymbol() ?: reference?.mainReference?.resolveToSymbols()?.singleOrNull()
                                    ) {
                                        is KaConstructorSymbol -> symbol.containingClassId?.asSingleFqName()?.asString()
                                        is KaCallableSymbol -> symbol.callableId?.asSingleFqName()?.asString()
                                        is KaClassLikeSymbol -> symbol.classId?.asSingleFqName()?.asString()
                                        else -> null
                                    }
                                }
                            val previousErrors =
                                prepared.errors
                                    .map { error ->
                                        val shift = shifts[error.path]
                                        val range = error.range
                                        if (shift == null || range == null) {
                                            error
                                        } else {
                                            error.copy(
                                                range = EditorRange(shift.offset(range.startUtf16), shift.offset(range.endUtf16, false)),
                                            )
                                        }
                                    }.toMutableList()
                            val actual = SemanticBindings.collect(candidate, limits).associateBy { it.site }
                            target == fix.fqName && errors(candidate, limits).all { previousErrors.remove(it) } &&
                                prepared.bindings.filter { it.target != null }.all { binding ->
                                    val expected =
                                        binding.copy(
                                            site = binding.site.shift(shifts),
                                            target =
                                                (binding.target as? ProjectBinding)?.let { ProjectBinding(it.location.shift(shifts)) }
                                                    ?: binding.target,
                                        )
                                    actual[expected.site] == expected
                                }
                        }
                    }
                }.orEmpty()
        return AnalysisResult.ImportFixes.create(
            query.identity,
            prepared?.reference?.let(coordinates::toSource),
            items.map { fix ->
                fix.copy(
                    edit =
                        fix.edit.copy(
                            range = coordinates.toSource(fix.edit.range),
                            text = if ("\r\n" in original) fix.edit.text.replace("\n", "\r\n") else fix.edit.text,
                        ),
                )
            },
            snapshot.sourceLengthsUtf16.getValue(query.path),
            AnalysisResultLimits(maxCompletionItems = limits.completionItems, maxDetailUtf8Bytes = limits.detailTextBytes),
        )
    }

    private fun errors(
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<EditorDiagnostic> =
        buildList {
            snapshot.files.forEach { (path, file) ->
                ProgressManager.checkCanceled()
                analyze(file) {
                    DiagnosticQuery.collect(this, path, file, limits).filter { it.severity == EditorDiagnosticSeverity.Error }.forEach {
                        if (size >= limits.diagnostics) throw AnalysisOutputLimitException("import validation diagnostics exceed limit")
                        add(it)
                    }
                }
            }
        }

    private data class Prepared(
        val source: String,
        val reference: EditorRange,
        val fixes: List<ImportQuickFix>,
        val errors: List<EditorDiagnostic>,
        val bindings: List<Binding>,
    )

    // Each candidate reanalyzes the admitted snapshot; bound speculation independently of index size.
    private const val MAX_PREVIEWS = 16
}
