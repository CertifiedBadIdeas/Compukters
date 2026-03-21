package ru.lazyhat.compuktercraft.scripting.impl

import ru.lazyhat.compuktercraft.scripting.api.Diagnostic
import ru.lazyhat.compuktercraft.scripting.api.DiagnosticSeverity
import ru.lazyhat.compuktercraft.scripting.api.Position
import ru.lazyhat.compuktercraft.scripting.api.Range
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal fun ScriptDiagnostic.asSharedDiagnostic(): Diagnostic =
    Diagnostic(
        range = location?.let {
            Range(
                start = Position(it.start.line.coerceAtLeast(0), it.start.col.coerceAtLeast(0)),
                end = Position(it.end?.line ?: it.start.line, it.end?.col ?: it.start.col),
            )
        },
        severity = when (severity) {
            ScriptDiagnostic.Severity.DEBUG -> DiagnosticSeverity.DEBUG
            ScriptDiagnostic.Severity.INFO -> DiagnosticSeverity.INFO
            ScriptDiagnostic.Severity.WARNING -> DiagnosticSeverity.WARNING
            ScriptDiagnostic.Severity.ERROR -> DiagnosticSeverity.ERROR
            ScriptDiagnostic.Severity.FATAL -> DiagnosticSeverity.FATAL
        },
        message = message,
    )

internal fun ResultWithDiagnostics<*>.sharedDiagnostics(): List<Diagnostic> = reports.map { it.asSharedDiagnostic() }
