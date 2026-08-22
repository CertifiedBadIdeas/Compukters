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

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

data class K2CompilerInputs(
    val temporaryRoot: Path,
    val workerJar: Path,
    val standardLibrary: Path,
    val jdkHome: Path,
    val expectedIdentity: WorkerIdentity,
)

data class K2CompilationResult(
    val exitCode: ExitCode,
    val diagnostics: List<WorkerDiagnostic>,
    val reachedIr: Boolean,
    val artifact: BinaryValue?,
    private val compilerHadErrors: Boolean,
) {
    val hasErrors: Boolean get() = compilerHadErrors
}

class K2CompilerAdapter(
    private val inputs: K2CompilerInputs,
) {
    fun compile(request: CompileRequest): K2CompilationResult {
        require(request.expectedIdentity == inputs.expectedIdentity) { "compile request identity does not match pinned worker" }
        require(Files.isRegularFile(inputs.workerJar)) { "validated worker jar is missing" }
        require(Files.isRegularFile(inputs.standardLibrary)) { "fixed standard library is missing" }
        require(Files.isDirectory(inputs.jdkHome)) { "fixed JDK home is missing" }
        val source = request.source.decodeUtf8()
        if (DEPENDENCY_ANNOTATION.containsMatchIn(source)) {
            return K2CompilationResult(
                ExitCode.COMPILATION_ERROR,
                listOf(
                    WorkerDiagnostic(
                        DiagnosticSeverity.ERROR,
                        DiagnosticCategory.TARGET,
                        null,
                        "script dependency refinement is unsupported",
                        request.path,
                        0u,
                        0u,
                    ),
                ),
                false,
                null,
                true,
            )
        }
        val requestRoot = inputs.temporaryRoot.resolve("request-${UUID.randomUUID()}")
        val physicalSource = requestRoot.resolve("source/main.kts")
        val output = requestRoot.resolve("output")
        var reachedIr = false
        var artifact: BinaryValue? = null
        try {
            physicalSource.parent.createDirectories()
            output.createDirectories()
            physicalSource.writeBytes(request.source.toByteArray())
            val collector = CompilerDiagnosticCollector(source, physicalSource, request.path, request.limits)
            val arguments = fixedArguments(physicalSource, output)
            val exitCode =
                CompilationBridge.withSession(
                    CompilationSession(
                        irSink = { _, _ -> reachedIr = true },
                        artifactSink = { artifact = it },
                    ),
                ) {
                    K2JVMCompiler().also { it.isReadingSettingsFromEnvironmentAllowed = false }.exec(collector, Services.EMPTY, arguments)
                }
            val failed = collector.hasErrors() || exitCode != ExitCode.OK
            return K2CompilationResult(exitCode, collector.diagnostics, reachedIr, artifact?.takeIf { !failed }, failed)
        } finally {
            deleteTree(requestRoot)
        }
    }

    private fun fixedArguments(
        source: Path,
        output: Path,
    ) = K2JVMCompilerArguments().apply {
        freeArgs = listOf(source.toString())
        destination = output.toString()
        moduleName = "compukter-script"
        languageVersion = "2.4"
        apiVersion = "2.4"
        jvmTarget = "17"
        classpath = inputs.standardLibrary.toString()
        jdkHome = inputs.jdkHome.toString()
        noStdlib = true
        noReflect = true
        pluginClasspaths = arrayOf(inputs.workerJar.toString())
    }

    private companion object {
        val DEPENDENCY_ANNOTATION = Regex("(?m)^\\s*@file:(DependsOn|Repository)\\b")
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
