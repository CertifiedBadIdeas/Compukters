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

package ru.lazyhat.compukters.platform.k2.contracts

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms

/** Report the intrinsic boundary in both standalone analysis and native IDEA source modules. */
internal class GuestContractCheckers(
    session: FirSession,
) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers =
        object : DeclarationCheckers() {
            override val simpleFunctionCheckers = setOf(GuestOperationChecker)
        }
}

private object GuestOperationChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val session = context.session
        if (session.moduleData.platform != CompuktersPlatforms.default || declaration.origin != FirDeclarationOrigin.Source) return
        if (GuestContractAnnotations.operations.none { declaration.symbol.hasAnnotation(it, session) }) return
        val owner = context.containingDeclarations.filterIsInstance<FirRegularClassSymbol>().lastOrNull()
        val validOwner =
            owner != null && owner.classKind == ClassKind.INTERFACE && owner.typeParameterSymbols.isEmpty() &&
                owner.classId.outerClassId == null &&
                session.isGuestContractOwner(owner)
        if (!validOwner) {
            reporter.reportOn(
                declaration.source,
                FirErrors.UNSUPPORTED,
                "Guest server operations require a top-level non-generic annotated interface",
            )
            return
        }
        if (declaration.body != null) {
            reporter.reportOn(declaration.source, FirErrors.UNSUPPORTED, "Guest server operations must have no body")
        }
    }
}
