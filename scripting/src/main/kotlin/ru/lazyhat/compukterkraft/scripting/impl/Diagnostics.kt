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

import ru.lazyhat.compukterkraft.scripting.api.Diagnostic
import ru.lazyhat.compukterkraft.scripting.api.DiagnosticSeverity
import ru.lazyhat.compukterkraft.scripting.api.Position
import ru.lazyhat.compukterkraft.scripting.api.Range
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal fun ScriptDiagnostic.asSharedDiagnostic(): Diagnostic =
    Diagnostic(
        range =
            location?.let {
                Range(
                    start = Position(it.start.line.coerceAtLeast(0), it.start.col.coerceAtLeast(0)),
                    end = Position(it.end?.line ?: it.start.line, it.end?.col ?: it.start.col),
                )
            },
        severity =
            when (severity) {
                ScriptDiagnostic.Severity.DEBUG -> DiagnosticSeverity.DEBUG
                ScriptDiagnostic.Severity.INFO -> DiagnosticSeverity.INFO
                ScriptDiagnostic.Severity.WARNING -> DiagnosticSeverity.WARNING
                ScriptDiagnostic.Severity.ERROR -> DiagnosticSeverity.ERROR
                ScriptDiagnostic.Severity.FATAL -> DiagnosticSeverity.FATAL
            },
        message = message,
    )

internal fun ResultWithDiagnostics<*>.sharedDiagnostics(): List<Diagnostic> = reports.map { it.asSharedDiagnostic() }

internal fun ResultWithDiagnostics.Failure.renderFailureMessage(defaultMessage: String): String =
    reports.firstNotNullOfOrNull { report ->
        report.exception?.let { throwable ->
            buildString {
                appendLine(report.message)
                append(throwable.stackTraceToString())
            }
        }
    } ?: reports.firstOrNull()?.message ?: defaultMessage
