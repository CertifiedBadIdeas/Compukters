package ru.lazyhat.compuktercraft.scripting.api

import java.io.File

interface CompiledScript {
    val name: String

    fun execute(properties: Map<String, Any?> = emptyMap()): ScriptExecutionResult
}

interface ScriptCompiler {
    fun compile(
        name: String,
        code: String,
    ): CompilationResult<CompiledScript>

    fun compile(file: File): CompilationResult<CompiledScript> = compile(file.name, file.readText())
}

interface ScriptIdeService {
    fun analyze(
        name: String,
        code: String,
    ): List<Diagnostic>

    fun highlight(
        name: String,
        code: String,
    ): List<HighlightToken>

    fun complete(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun hover(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): HoverInfo?

    fun definition(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): DefinitionTarget?
}

interface ScriptingEnvironment : AutoCloseable {
    val config: ScriptingEnvironmentConfig
    val definitions: List<ScriptDefinitionDescriptor>
    val compiler: ScriptCompiler
    val ide: ScriptIdeService
    val isAvailable: Boolean

    fun bundledScript(relativePath: String): String?
}

interface ScriptingEnvironmentInitializer {
    fun initialize(config: ScriptingEnvironmentConfig): ScriptingEnvironment
}
