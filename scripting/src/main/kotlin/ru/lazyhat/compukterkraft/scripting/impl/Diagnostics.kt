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

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.frontend.FrontendDiagnostic
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.scripting.api.Diagnostic
import ru.lazyhat.compukterkraft.scripting.api.DiagnosticSeverity
import ru.lazyhat.compukterkraft.scripting.api.Position
import ru.lazyhat.compukterkraft.scripting.api.Range

internal fun FrontendDiagnostic.toSharedDiagnostic(): Diagnostic =
    Diagnostic(
        range = range?.toSharedRange(),
        severity =
            when (severity) {
                FrontendSeverity.INFO -> DiagnosticSeverity.INFO
                FrontendSeverity.WARNING -> DiagnosticSeverity.WARNING
                FrontendSeverity.ERROR -> DiagnosticSeverity.ERROR
            },
        message = message,
    )

internal fun SourceRange.toSharedRange(): Range =
    Range(
        start = Position(start.line, start.column),
        end = Position(end.line, end.column),
    )
