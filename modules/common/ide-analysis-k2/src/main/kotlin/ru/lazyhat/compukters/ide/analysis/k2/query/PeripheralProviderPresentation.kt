/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.ide.analysis.k2.query

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtTypeReference

/** Device class names denote providers in expressions, while type references keep their ordinary class role. */
internal fun isPeripheralValuePosition(element: PsiElement): Boolean =
    generateSequence(element) { it.parent }.none { it is KtTypeReference || it is KtImportDirective || it is KtPackageDirective }

internal fun KaClassSymbol.hasPeripheralProviderValue(): Boolean {
    val value =
        when (classKind) {
            KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT -> this
            else -> (this as? KaNamedClassSymbol)?.companionObject ?: return false
        }
    val seen = mutableSetOf<KaClassSymbol>()

    fun inherits(symbol: KaClassSymbol): Boolean {
        if (!seen.add(symbol)) return false
        if ((symbol as? KaNamedClassSymbol)?.classId?.asSingleFqName()?.asString() == "compukter.peripheral.PeripheralProvider") return true
        return symbol.superTypes.filterIsInstance<KaClassType>().any { type -> (type.symbol as? KaClassSymbol)?.let(::inherits) == true }
    }
    return inherits(value)
}
