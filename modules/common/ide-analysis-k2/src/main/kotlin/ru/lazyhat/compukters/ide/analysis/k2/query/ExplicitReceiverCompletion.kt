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
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.createExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaSubstitutor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/** A query-only expression in the real lexical context, never a mutation of admitted source PSI. */
@OptIn(KaIdeApi::class, KaExperimentalApi::class)
internal class ExplicitReceiverCompletion(
    private val file: KtFile,
    context: KtElement,
    receiverText: String,
    safeCall: Boolean,
) {
    private val fragment =
        KtPsiFactory(file.project).createExpressionCodeFragment(
            "($receiverText)${if (safeCall) "?." else "."}__compukters_completion__",
            context,
        )

    private val checker by lazy(LazyThreadSafetyMode.NONE) {
        analyze(fragment) {
            val expression = fragment.getContentElement() as? KtQualifiedExpression ?: return@analyze null
            val name = expression.selectorExpression as? KtSimpleNameExpression ?: return@analyze null
            createExtensionCandidateChecker(file, name, expression.receiverExpression)
        }
    }

    fun substitutor(function: KaFunctionSymbol): KaSubstitutor? =
        analyze(fragment) {
            (checker?.computeApplicability(function) as? KaExtensionApplicabilityResult.Applicable)?.substitutor
        }
}
