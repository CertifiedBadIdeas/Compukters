/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path

class CompilerDiagnosticCollector(
    private val source: String,
    private val physicalPath: Path,
    private val virtualPath: VirtualSourcePath,
    private val limits: WorkerLimits,
) : MessageCollector {
    private val collected = mutableListOf<WorkerDiagnostic>()
    private var textBytes = 0
    private var errors = false

    val diagnostics: List<WorkerDiagnostic> get() = collected.toList()

    override fun clear() {
        collected.clear()
        textBytes = 0
        errors = false
    }

    override fun hasErrors(): Boolean = errors

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity == CompilerMessageSeverity.LOGGING || severity == CompilerMessageSeverity.OUTPUT) return
        val mappedSeverity =
            when {
                severity.isError -> DiagnosticSeverity.ERROR
                severity.isWarning -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            }
        val sanitized = message.replace(physicalPath.toString(), virtualPath.value)
        val diagnosticCategory = category(sanitized)
        val sourceLocation =
            location?.takeIf {
                runCatching {
                    Path
                        .of(
                            it.path,
                        ).normalize() == physicalPath.normalize()
                }.getOrDefault(false)
            }
        admit(
            WorkerDiagnostic(
                mappedSeverity,
                diagnosticCategory,
                null,
                sanitized,
                sourceLocation?.let { virtualPath },
                sourceLocation?.let { offset(it.line, it.column) },
                sourceLocation?.let { offset(it.lineEnd, it.columnEnd) },
            ),
        )
    }

    fun report(diagnostic: WorkerDiagnostic) = admit(diagnostic)

    private fun admit(diagnostic: WorkerDiagnostic) {
        errors = errors || diagnostic.severity == DiagnosticSeverity.ERROR
        if (collected.size >= limits.diagnostics || textBytes >= limits.diagnosticTextBytes) return
        val admitted = truncateUtf8(diagnostic.message, limits.diagnosticTextBytes - textBytes)
        textBytes += admitted.encodeToByteArray().size
        collected += diagnostic.copy(message = admitted)
    }

    private fun offset(
        line: Int,
        column: Int,
    ): UInt? {
        if (line < 1 || column < 1) return null
        var currentLine = 1
        var index = 0
        while (currentLine < line && index < source.length) if (source[index++] == '\n') currentLine++
        if (currentLine != line) return null
        return (index + column - 1).coerceAtMost(source.length).toUInt()
    }

    private fun category(message: String): DiagnosticCategory =
        if (
            message.contains("syntax", ignoreCase = true) ||
            message.contains("expecting", ignoreCase = true) ||
            message.contains("initializer required", ignoreCase = true) ||
            message.contains("unexpected tokens", ignoreCase = true)
        ) {
            DiagnosticCategory.SYNTAX
        } else {
            DiagnosticCategory.TYPE
        }
}

private fun truncateUtf8(
    value: String,
    maximumBytes: Int,
): String {
    val result = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val encoded = text.encodeToByteArray().size
        if (bytes + encoded > maximumBytes) break
        result.append(text)
        bytes += encoded
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
