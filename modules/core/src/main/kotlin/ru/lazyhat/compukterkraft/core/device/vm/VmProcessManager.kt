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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.lang.frontend.CompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class VmProcessManager(
    private val scope: CoroutineScope,
    private val ctx: VmContext,
    private val deviceId: Int,
    private val programLoader: WorkspaceProgramLoader,
    private val profile: DeviceProfile,
    private val runtimeCreator: (String, String) -> DeviceRuntime,
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
) {
    private val nextPid = AtomicInteger(2)
    private val processes = ConcurrentHashMap<Int, ProcessHandle>()

    fun spawn(
        path: String,
        argument: String,
        workingDirectory: String,
    ): Int {
        val pid = nextPid.getAndIncrement()
        val exitCode = CompletableDeferred<Int>()
        val job =
            scope.launch {
                val code = execute(path, argument, workingDirectory)
                exitCode.complete(code)
            }
        job.invokeOnCompletion { failure ->
            if (failure != null && !exitCode.isCompleted) {
                exitCode.complete(1)
            }
        }
        processes[pid] = ProcessHandle(job, exitCode)
        return pid
    }

    suspend fun wait(pid: Int): Int {
        val handle = processes[pid] ?: return 1
        val code = handle.exitCode.await()
        processes.remove(pid, handle)
        return code
    }

    suspend fun cancelAll() {
        val jobs = processes.values.map { it.job }
        jobs.forEach { it.cancel() }
        processes.clear()
        jobs.joinAll()
    }

    private suspend fun execute(
        path: String,
        argument: String,
        workingDirectory: String,
    ): Int {
        val stderr = StdioDescriptor.decode(argument)?.stderr

        suspend fun reportError(message: String) {
            ctx.log("VM[$deviceId] $message")
            if (stderr != null && stderr >= 0) {
                ctx.writeIpc(stderr, "$message\n")
            }
        }

        val resolved = path
        val programSource =
            programLoader.load(deviceId, resolved) ?: run {
                val message = "Program not found: $resolved"
                reportError(message)
                return 1
            }
        val compiledProgram =
            ComputerProgramCompiler.compile(
                programSource.path,
                programSource.source,
                profile,
                sourceLoader = programLoader.sourceLoader(deviceId),
                compilerMetricsCollector = compilerMetricsCollector,
            )
        val program = compiledProgram.program
        if (program == null) {
            val message = compiledProgram.errorMessage.orEmpty().ifEmpty { "Compilation failed." }
            reportError("Compilation Error in ${programSource.path}: $message")
            return 1
        }

        return try {
            program.run(runtimeCreator(workingDirectory, argument))
            0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            reportError("Program error in ${programSource.path}: ${failure.message ?: failure.javaClass.simpleName}")
            1
        }
    }

    private data class ProcessHandle(
        val job: Job,
        val exitCode: CompletableDeferred<Int>,
    )
}
