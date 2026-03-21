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

package ru.lazyhat.compukterkraft.scripting.api

import ru.lazyhat.compukterkraft.machine.ComputerFileSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerPeripheralApi
import ru.lazyhat.compukterkraft.machine.ComputerRedstoneApi
import ru.lazyhat.compukterkraft.machine.ComputerRuntime
import ru.lazyhat.compukterkraft.machine.ComputerScriptBindings
import ru.lazyhat.compukterkraft.machine.ComputerSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerTerminalApi
import kotlin.reflect.KClass

data class ScriptPropertyDescriptor(
    val name: String,
    val type: KClass<*>,
)

data class ScriptDefinitionDescriptor(
    val fileExtension: String,
    val displayName: String,
    val baseClass: String = "kotlin.Any",
    val defaultImports: List<String> = emptyList(),
    val providedProperties: List<ScriptPropertyDescriptor> = emptyList(),
)

data class ScriptingEnvironmentConfig(
    val modId: String,
    val bundledScriptsRoot: String,
    val externalScriptsDirectory: String? = null,
    val definitions: List<ScriptDefinitionDescriptor> = emptyList(),
)

data class Position(
    val line: Int,
    val column: Int,
)

data class Range(
    val start: Position,
    val end: Position,
)

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

data class Diagnostic(
    val range: Range? = null,
    val severity: DiagnosticSeverity,
    val message: String,
)

data class CompilationResult<T>(
    val value: T? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val exceptionMessage: String? = null,
) {
    val isSuccess: Boolean
        get() =
            value != null && exceptionMessage == null &&
                diagnostics.none { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
}

data class ScriptExecutionResult(
    val value: Any? = null,
    val returnValue: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val exceptionMessage: String? = null,
) {
    val isSuccess: Boolean
        get() =
            exceptionMessage == null &&
                diagnostics.none { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
}

enum class CompletionItemKind {
    KEYWORD,
    SYMBOL,
    IMPORT,
    SNIPPET,
}

data class CompletionItem(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
    val kind: CompletionItemKind = CompletionItemKind.SYMBOL,
)

data class HoverInfo(
    val contents: String,
    val range: Range? = null,
)

data class DefinitionTarget(
    val path: String,
    val range: Range,
)

enum class HighlightTokenType {
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    FUNCTION,
    PROPERTY,
    TYPE,
}

data class HighlightToken(
    val range: Range,
    val type: HighlightTokenType,
)

object ScriptDefinitionPresets {
    fun standardKts(modId: String): ScriptDefinitionDescriptor =
        ScriptDefinitionDescriptor(
            fileExtension = ".kts",
            displayName = "$modId Kotlin Script",
            defaultImports =
                listOf(
                    "kotlin.math.*",
                    "kotlin.io.*",
                    "ru.lazyhat.compukterkraft.scripting.api.*",
                ),
        )

    fun computerKts(modId: String): ScriptDefinitionDescriptor =
        ScriptDefinitionDescriptor(
            fileExtension = ComputerScriptBindings.FILE_EXTENSION,
            displayName = "$modId Computer Script",
            defaultImports =
                listOf(
                    "kotlin.math.*",
                    "kotlin.io.*",
                    "ru.lazyhat.compukterkraft.machine.*",
                    "ru.lazyhat.compukterkraft.scripting.api.*",
                ),
            providedProperties =
                listOf(
                    ScriptPropertyDescriptor(ComputerScriptBindings.RUNTIME_PROPERTY, ComputerRuntime::class),
                    ScriptPropertyDescriptor(ComputerScriptBindings.SYSTEM_PROPERTY, ComputerSystemApi::class),
                    ScriptPropertyDescriptor(ComputerScriptBindings.TERMINAL_PROPERTY, ComputerTerminalApi::class),
                    ScriptPropertyDescriptor(ComputerScriptBindings.FILESYSTEM_PROPERTY, ComputerFileSystemApi::class),
                    ScriptPropertyDescriptor(ComputerScriptBindings.REDSTONE_PROPERTY, ComputerRedstoneApi::class),
                    ScriptPropertyDescriptor(ComputerScriptBindings.PERIPHERALS_PROPERTY, ComputerPeripheralApi::class),
                ),
        )
}
