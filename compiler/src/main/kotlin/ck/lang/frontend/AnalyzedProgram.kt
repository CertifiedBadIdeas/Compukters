/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ck.lang.frontend

import ck.lang.api.BuiltinFunction
import ck.lang.api.BuiltinModule
import ck.lang.api.Program
import ck.lang.api.Token

class AnalyzedProgram(
    val name: String,
    val source: String,
    val tokens: List<Token>,
    val program: Program?,
    val diagnostics: List<FrontendDiagnostic>,
    val symbols: List<SymbolInfo>,
    val references: List<ReferenceInfo>,
    private val builtinModules: List<BuiltinModule>,
    private val builtinGlobals: List<BuiltinFunction>,
) {
    fun symbolAt(offset: Int): SymbolInfo? =
        references.firstOrNull { it.range.contains(offset) }?.target
            ?: symbols.firstOrNull { it.range?.contains(offset) == true }

    fun referenceAt(offset: Int): ReferenceInfo? = references.firstOrNull { it.range.contains(offset) }

    fun visibleSymbolsAt(offset: Int): List<SymbolInfo> =
        buildList {
            addAll(symbols.filter { it.kind == SymbolKind.MODULE || it.ownerFunctionRange == null })
            addAll(
                symbols.filter { symbol ->
                    val owner = symbol.ownerFunctionRange
                    owner != null && owner.contains(offset) && (symbol.range?.start?.offset ?: Int.MAX_VALUE) <= offset
                },
            )
            addAll(
                builtinGlobals.map {
                    SymbolInfo(
                        name = it.name,
                        kind = SymbolKind.BUILTIN_FUNCTION,
                        range = null,
                        detail = "${it.name}(${it.parameterTypes.joinToString()}) : ${it.returnType}",
                        documentation = it.documentation,
                    )
                },
            )
        }.distinctBy { it.kind to it.name }

    fun moduleMembers(moduleName: String): List<SymbolInfo> =
        builtinModules
            .firstOrNull { it.name == moduleName }
            ?.functions
            ?.map {
                SymbolInfo(
                    name = it.name,
                    kind = SymbolKind.BUILTIN_FUNCTION,
                    range = null,
                    detail = "$moduleName.${it.name}(${it.parameterTypes.joinToString()}) : ${it.returnType}",
                    documentation = it.documentation,
                )
            }.orEmpty()
}
