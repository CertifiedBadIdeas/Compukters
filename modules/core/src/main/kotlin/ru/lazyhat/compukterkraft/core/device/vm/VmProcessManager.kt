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
) : VmProcessStateReporter {
    private val nextPid = AtomicInteger(2)
    private val processes = ConcurrentHashMap<Int, ProcessHandle>()
    private val processTable = VmProcessTable()
    private val processScheduler = VmProcessScheduler(processTable)

    init {
        processTable.registerProcess(
            pid = 1,
            parentPid = 0,
            programPath = profile.bootScriptName,
            argument = "",
            workingDirectory = "",
        )
        if (nativeProcessBridge.registerProcess(pid = 1, parentPid = 0, programPath = profile.bootScriptName)) {
            runtimeMetricsCollector.recordNativeProcessRegistration()
        }
    }

    fun processSnapshot(pid: Int): VmProcessRecord? = processTable.snapshot(pid)

    fun schedulerTick(currentTick: Long): VmProcessSchedulerTick {
        val kotlinTick = processScheduler.tick(currentTick)
        val nativeTick = nativeProcessBridge.schedulerTick(currentTick)
        val effectiveTick =
            if (nativeTick == null) {
                kotlinTick
            } else {
                val matched = nativeTick == kotlinTick
                runtimeMetricsCollector.recordNativeProcessSchedulerComparison(matched)
                runtimeMetricsCollector.recordNativeProcessSchedulerSource(acceptedNative = matched)
                if (matched) nativeTick else kotlinTick
            }
        return effectiveTick.also { tick ->
            runtimeMetricsCollector.recordProcessSchedulerTick(
                wokenProcesses = tick.wokenPids.size,
                selected = tick.selectedPid != null,
            )
        }
    }

    override fun markRunnable(pid: Int) {
        processTable.markRunnable(pid)
        nativeProcessBridge.markRunnable(pid)
    }

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ) {
        processTable.markWaitingEvent(pid, filter)
        nativeProcessBridge.markWaitingEvent(pid, filter)
    }

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ) {
        processTable.markWaitingIpc(pid, channelId)
        nativeProcessBridge.markWaitingIpc(pid, channelId)
    }

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) {
        processTable.markWaitingProcess(pid, targetPid)
        nativeProcessBridge.markWaitingProcess(pid, targetPid)
    }

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ) {
        processTable.markSleeping(pid, untilTick)
        nativeProcessBridge.markSleeping(pid, untilTick)
    }

    override fun markExited(
        pid: Int,
        exitCode: Int,
    ) {
        processTable.markExited(pid, exitCode)
    }

    override fun markCrashed(
        pid: Int,
        message: String,
    ) {
        processTable.markCrashed(pid, message)
        nativeProcessBridge.markCrashed(pid, message)
    }

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
                val result = execute(pid, parentPid, path, argument, workingDirectory)
                if (result.crashMessage != null) {
                    markCrashed(pid, result.crashMessage)
                } else {
                    markExited(pid, result.exitCode)
                }
                processTable.wakeProcessWaiters(pid)
                if (nativeRegistered) {
                    if (nativeProcessBridge.completeProcess(pid, result.exitCode)) {
                        runtimeMetricsCollector.recordNativeProcessCompletion()
                    } else {
                        runtimeMetricsCollector.recordNativeProcessStaleCompletion()
                    }
                }
                exitCode.complete(result.exitCode)
            }
        processes[pid] = ProcessHandle(job, exitCode)
        job.start()
        job.invokeOnCompletion { failure ->
            if (failure != null && !exitCode.isCompleted) {
                markCrashed(pid, failure.message ?: failure.javaClass.simpleName)
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
            markWaitingProcess(waiterPid, pid)
        }
        val code =
            try {
                handle.exitCode.await()
            } finally {
                if (waiterPid != null && processTable.snapshot(waiterPid)?.state == VmProcessState.WaitingProcess(pid)) {
                    markRunnable(waiterPid)
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
    ): ProcessExecutionResult {
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
                return ProcessExecutionResult(exitCode = 1)
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
            return ProcessExecutionResult(exitCode = 1)
        }

        return try {
            program.run(runtimeCreator(pid, parentPid, workingDirectory, argument))
            ProcessExecutionResult(exitCode = 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val message = "Program error in ${programSource.path}: ${failure.message ?: failure.javaClass.simpleName}"
            reportError(message)
            ProcessExecutionResult(exitCode = 1, crashMessage = message)
        }
    }

    private data class ProcessExecutionResult(
        val exitCode: Int,
        val crashMessage: String? = null,
    )

    private data class ProcessHandle(
        val job: Job,
        val exitCode: CompletableDeferred<Int>,
    )
}
