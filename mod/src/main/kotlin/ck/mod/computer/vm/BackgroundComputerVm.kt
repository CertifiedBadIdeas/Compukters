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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerProgram
import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.ComputerWorkspace
import ck.lang.runtime.HostCall
import ck.lang.runtime.HostResult
import ck.lang.runtime.ScreenBuffer
import ck.lang.runtime.ScreenBufferSnapshot
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmSnapshot
import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
import ck.mod.application.runtime.ComputerProgramCompiler
import ck.mod.application.runtime.WorkspaceProgramLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.yield as coroutineYield

fun interface ComputerVmLogger {
    fun log(message: String)
}

/**
 * The main VM host for a single computer instance.
 *
 * Runs a compiled [ComputerProgram] on a background coroutine [dispatcher]. Owns a [ScreenBuffer]
 * that the VM coroutine writes to directly (no HostCall roundtrip for terminal I/O).
 *
 * ## Thread model
 * - **VM coroutine:** calls `runtime.terminal.write()`, `runtime.filesystem.*()`, etc.
 *   Terminal writes go directly to [screenBuffer]. Filesystem ops go through [HostCallManager].
 * - **Server tick thread:** calls [requestSlice], [drainHostCalls], [deliverHostResults],
 *   [readScreenSnapshot], and [snapshot]. These are the cross-thread entry points.
 *
 * ## Lifecycle
 * Created by [ComputerManager][ck.mod.context.ComputerManager], started with [boot],
 * stopped with [stop]. On reboot, the old VM is stopped and a new one is created.
 */
class BackgroundComputerVm(
    override val computerId: Int,
    override val profile: ComputerProfile,
    dispatcher: CoroutineDispatcher,
    private val labelProvider: () -> String?,
    private val logger: ComputerVmLogger,
    workspace: ComputerWorkspace,
) : ComputerVmHandle,
    VmContext {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val slicePermits = Channel<Unit>(capacity = 1)
    private val stateManager = VmStateManager()
    private val eventManager = EventManager(profile.maxEventQueueSize)
    private val hostCallManager = HostCallManager()
    private val programLoader = WorkspaceProgramLoader(workspace)
    private val pathResolver = VmPathResolver()
    private val screenBuffer = ScreenBuffer(profile.terminalWidth, profile.terminalHeight, profile.colorTerminal)

    /**
     * Observe terminal VM states (stopped, crashed).
     * Derived from [VmStateManager.stateFlow] — emits only terminal transitions.
     */
    val terminalStates: SharedFlow<VmState> =
        stateManager.stateFlow
            .filter { it.isTerminal }
            .shareIn(scope, SharingStarted.Eagerly)

    private var runner: Job? = null
    private val runtime: VmRuntime = createRuntime("", "")

    // ── ComputerVmHandle ────────────────────────────────────────────

    override fun boot(): Boolean {
        if (runner?.isActive == true) return false

        stateManager.setState(VmState.Booting)

        runner =
            scope.launch {
                try {
                    val source =
                        programLoader.load(computerId, profile.bootScriptName)
                            ?: run {
                                stopInternal(errorMessage = "Missing boot script: ${profile.bootScriptName}")
                                return@launch
                            }

                    val compiled = ComputerProgramCompiler.compile(source.path, source.source)

                    val program =
                        compiled.program
                            ?: run {
                                stopInternal(errorMessage = "Boot compilation failed: ${compiled.errorMessage.orEmpty()}")
                                return@launch
                            }

                    awaitSlicePermit()
                    logger.log("VM[$computerId] boot program started")
                    program.run(runtime)
                    stopInternal(VmStopReason.REQUESTED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    stopInternal(errorMessage = failure.message ?: failure.javaClass.simpleName)
                }
            }

        enqueueEvent(VmEvent("boot"))
        return true
    }

    override fun stop(reason: VmStopReason) {
        scope.launch {
            stopInternal(reason)
        }
    }

    override fun enqueueEvent(event: VmEvent): Boolean = eventManager.enqueueEvent(event)

    override fun requestSlice(serverTick: Long) {
        stateManager.updateCurrentTick(serverTick)
        val wakeTick = stateManager.sleepUntilTick
        if (wakeTick != null && serverTick < wakeTick) return
        slicePermits.trySend(Unit)
    }

    override fun drainHostCalls(): List<HostCall> = hostCallManager.drainHostCalls()

    override fun deliverHostResults(results: List<HostResult>) {
        hostCallManager.deliverHostResults(results)
    }

    override fun snapshot(): VmSnapshot =
        VmSnapshot(
            computerId = computerId,
            profile = profile,
            state = stateManager.state,
            currentTick = stateManager.currentTick,
            queuedEvents = eventManager.queuedCount(),
            pendingHostCalls = hostCallManager.pendingCallsCount(),
        )

    override fun readScreenSnapshot(): ScreenBufferSnapshot? = screenBuffer.snapshot()

    override fun forceScreenSnapshot(): ScreenBufferSnapshot = screenBuffer.forceSnapshot()

    // ── VmContext ───────────────────────────────────────────────────

    override suspend fun receiveEvent(): VmEvent = eventManager.receiveEvent()

    override fun deferEvent(event: VmEvent) = eventManager.deferEvent(event)

    override fun setState(state: VmState) = stateManager.setState(state)

    override fun setSleepUntil(tick: Long?) = stateManager.setSleepUntil(tick)

    override suspend fun schedulingPoint() = applySchedulingPoint()

    override suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T = hostCallManager.awaitHostCall(callFactory)

    override fun resolvePath(path: String): String = pathResolver.resolve(path)

    override fun log(message: String) = logger.log(message)

    // ── Internal ────────────────────────────────────────────────────

    private suspend fun stopInternal(
        reason: VmStopReason = VmStopReason.REQUESTED,
        errorMessage: String? = null,
    ) {
        if (stateManager.isStopped) return

        stateManager.withStateLock {
            stateManager.stopVm(reason, errorMessage)
            runner?.cancel()
            runner = null
        }
    }

    private suspend fun awaitSlicePermit() {
        stateManager.setState(
            when {
                stateManager.sleepUntilTick != null -> VmState.Sleeping
                stateManager.isBooting -> VmState.Booting
                else -> VmState.Running
            },
        )
        slicePermits.receive()
        stateManager.updateSliceDeadlineNanos(profile.cpuBudgetNanosPerSlice)
        stateManager.setState(VmState.Running)
    }

    private suspend fun applySchedulingPoint() {
        coroutineContext.ensureActive()
        if (System.nanoTime() >= stateManager.sliceDeadlineNanos) {
            awaitSlicePermit()
        } else {
            coroutineYield()
        }
    }

    private fun createRuntime(
        workingDirectory: String,
        argument: String,
    ): VmRuntime {
        pathResolver.updateWorkingDirectory(workingDirectory)

        val systemApi =
            VmSystemApi(
                ctx = this,
                computerId = computerId,
                currentTickProvider = { stateManager.currentTick },
                labelProvider = labelProvider,
            )
        val terminalApi = VmTerminalApi(screenBuffer = screenBuffer, ctx = this)
        val filesystemApi = VmFileSystemApi(ctx = this)
        val processApi =
            VmProcessApi(
                ctx = this,
                initialArgument = argument,
                computerId = computerId,
                pathResolver = pathResolver,
                filesystemApi = filesystemApi,
                programLoader = programLoader,
                runtimeCreator = { wd, arg -> createRuntime(wd, arg) },
                terminal = terminalApi,
            )

        return VmRuntime(
            ctx = this,
            initialProfile = profile,
            systemApi = systemApi,
            terminalApi = terminalApi,
            filesystemApi = filesystemApi,
            processApi = processApi,
        )
    }
}
