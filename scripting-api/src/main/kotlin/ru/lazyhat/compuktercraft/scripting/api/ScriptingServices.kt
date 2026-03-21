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
