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
import org.jetbrains.kotlin.config.Services
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudget
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryUsage
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Files
import java.nio.file.Path
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
        val forbiddenSource =
            request.sources.firstOrNull {
                DEPENDENCY_ANNOTATION.containsMatchIn(
                    it.content.toByteArray().decodeToString(),
                )
            }
        if (forbiddenSource != null) {
            return K2CompilationResult(
                ExitCode.COMPILATION_ERROR,
                listOf(
                    WorkerDiagnostic(
                        DiagnosticSeverity.ERROR,
                        DiagnosticCategory.TARGET,
                        null,
                        "script dependency refinement is unsupported",
                        forbiddenSource.path,
                        0u,
                        0u,
                    ),
                ),
                false,
                null,
                true,
            )
        }
        val temporaryBudget = TemporaryBudget(inputs.temporaryRoot, request.limits)
        val terminalApi = loadTerminalApi()
        val processApi = loadProcessApi()
        temporaryBudget.requireCapacity(sourceFootprint(request, terminalApi.size + processApi.size, 2))
        return temporaryBudget.useRequestDirectory { requestRoot ->
            val sourceRoot = requestRoot.resolve("source").toAbsolutePath().normalize()
            val trustedRoot = requestRoot.resolve("trusted").toAbsolutePath().normalize()
            val output = requestRoot.resolve("output").toAbsolutePath().normalize()
            var reachedIr = false
            var artifact: BinaryValue? = null
            sourceRoot.createDirectories()
            val physicalSources =
                request.sources.map { source ->
                    val physical = sourceRoot.resolve(source.path.value).normalize()
                    require(physical.startsWith(sourceRoot)) { "source path escapes request tree" }
                    physical.parent.createDirectories()
                    physical.writeBytes(source.content.toByteArray())
                    physical to DiagnosticSource(source.content.toByteArray().decodeToString(), source.path)
                }
            val terminalApiSource = trustedRoot.resolve("terminal.kt")
            val processApiSource = trustedRoot.resolve("process.kt")
            terminalApiSource.parent.createDirectories()
            terminalApiSource.writeBytes(terminalApi)
            processApiSource.writeBytes(processApi)
            val collector = CompilerDiagnosticCollector(physicalSources.toMap(), request.limits)
            val arguments = fixedArguments(physicalSources.map { it.first } + listOf(terminalApiSource, processApiSource), output)
            val exitCode =
                CompilationBridge.withSession(
                    CompilationSession(
                        irSink = { _, _ -> reachedIr = true },
                        artifactSink = { artifact = it },
                        diagnosticSink = collector::report,
                        sourcePaths =
                            physicalSources.associate { (physical, source) ->
                                physical.toString() to source.virtualPath
                            },
                        trustedApiSourceIdentities =
                            mapOf(
                                terminalApiSource.toString() to TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                                processApiSource.toString() to TrustedIntrinsicRegistry.PROCESS_BUNDLE_ID,
                            ),
                        limits = request.limits,
                    ),
                ) {
                    IrOnlyJvmCliPipeline().execute(arguments, Services.EMPTY, collector)
                }
            val failed = collector.hasErrors() || exitCode != ExitCode.OK
            K2CompilationResult(exitCode, collector.diagnostics, reachedIr, artifact?.takeIf { !failed }, failed)
        }
    }

    private fun sourceFootprint(
        request: CompileRequest,
        trustedApiBytes: Int,
        trustedApiFiles: Int,
    ): TemporaryUsage {
        val directories = mutableSetOf("source", "trusted")
        request.sources.forEach { source ->
            var parent = Path.of(source.path.value).parent
            while (parent != null) {
                directories += "source/$parent"
                parent = parent.parent
            }
        }
        return TemporaryUsage(
            files = Math.addExact(Math.addExact(request.sources.size, trustedApiFiles), directories.size),
            bytes = Math.addExact(request.sources.sumOf { it.content.size.toLong() }, trustedApiBytes.toLong()),
        )
    }

    private fun loadTerminalApi(): ByteArray =
        checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream(TERMINAL_API_RESOURCE)) {
            "fixed terminal API source is missing from the worker payload"
        }.use { it.readBytes() }

    private fun loadProcessApi(): ByteArray =
        checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream(PROCESS_API_RESOURCE)) {
            "fixed process API source is missing from the worker payload"
        }.use { it.readBytes() }

    private fun fixedArguments(
        sources: List<Path>,
        output: Path,
    ) = K2JVMCompilerArguments().apply {
        freeArgs = sources.map(Path::toString)
        destination = output.toString()
        moduleName = "compukter-project"
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
        const val TERMINAL_API_RESOURCE = "/compukter-api/terminal.kt"
        const val PROCESS_API_RESOURCE = "/compukter-api/process.kt"
        val DEPENDENCY_ANNOTATION = Regex("(?m)^\\s*@file:(DependsOn|Repository)\\b")
    }
}
