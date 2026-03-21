package ru.lazyhat.compuktercraft.scripting.api

data class ScriptDefinitionDescriptor(
    val fileExtension: String,
    val displayName: String,
    val baseClass: String = "kotlin.Any",
    val defaultImports: List<String> = emptyList(),
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
        get() = value != null && exceptionMessage == null && diagnostics.none { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
}

data class ScriptExecutionResult(
    val returnValue: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val exceptionMessage: String? = null,
) {
    val isSuccess: Boolean
        get() = exceptionMessage == null && diagnostics.none { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
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
            defaultImports = listOf(
                "kotlin.math.*",
                "kotlin.io.*",
                "ru.lazyhat.compuktercraft.scripting.api.*",
            ),
        )
}
