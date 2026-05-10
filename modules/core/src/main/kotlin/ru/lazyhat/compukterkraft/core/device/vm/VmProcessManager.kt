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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
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
    private val runtimeCreator: (Int, Int, String, String) -> DeviceRuntime,
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    private val nativeProcessBridge: NativeProcessBridge = NoOpNativeProcessBridge,
) {
    private val nextPid = AtomicInteger(2)
    private val processes = ConcurrentHashMap<Int, ProcessHandle>()
    private val processTable = VmProcessTable()

    init {
        processTable.registerProcess(
            pid = 1,
            parentPid = 0,
            programPath = profile.bootScriptName,
            argument = "",
            workingDirectory = "",
        )
    }

    fun processSnapshot(pid: Int): VmProcessRecord? = processTable.snapshot(pid)

    fun spawn(
        path: String,
        argument: String,
        workingDirectory: String,
        parentPid: Int = 1,
    ): Int {
        val pid = nextPid.getAndIncrement()
        val exitCode = CompletableDeferred<Int>()
        processTable.registerProcess(
            pid = pid,
            parentPid = parentPid,
            programPath = path,
            argument = argument,
            workingDirectory = workingDirectory,
        )
        val nativeRegistered = nativeProcessBridge.registerProcess(pid = pid, parentPid = parentPid, programPath = path)
        if (nativeRegistered) {
            runtimeMetricsCollector.recordNativeProcessRegistration()
        }
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val code = execute(pid, parentPid, path, argument, workingDirectory)
                processTable.markExited(pid, code)
                if (nativeRegistered) {
                    if (nativeProcessBridge.completeProcess(pid, code)) {
                        runtimeMetricsCollector.recordNativeProcessCompletion()
                    } else {
                        runtimeMetricsCollector.recordNativeProcessStaleCompletion()
                    }
                }
                exitCode.complete(code)
            }
        processes[pid] = ProcessHandle(job, exitCode)
        job.start()
        job.invokeOnCompletion { failure ->
            if (failure != null && !exitCode.isCompleted) {
                processTable.markExited(pid, 1)
                exitCode.complete(1)
            }
        }
        return pid
    }

    suspend fun wait(
        pid: Int,
        waiterPid: Int? = null,
    ): Int {
        val handle = processes[pid] ?: return 1
        if (waiterPid != null) {
            processTable.markWaitingProcess(waiterPid, pid)
        }
        val code =
            try {
                handle.exitCode.await()
            } finally {
                if (waiterPid != null && processTable.snapshot(waiterPid)?.state == VmProcessState.WaitingProcess(pid)) {
                    processTable.markRunnable(waiterPid)
                }
            }
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
        pid: Int,
        parentPid: Int,
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
            program.run(runtimeCreator(pid, parentPid, workingDirectory, argument))
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
