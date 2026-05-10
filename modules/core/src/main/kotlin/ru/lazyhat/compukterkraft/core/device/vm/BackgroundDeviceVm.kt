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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.device.runtime.ClasspathFirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmEventApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmFileSystemApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmIpcApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmPeripheralRegistry
import ru.lazyhat.compukterkraft.core.device.vm.api.VmPeripheralRuntimeApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmProcessApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmSystemApi
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayRegistry
import ru.lazyhat.compukterkraft.core.device.vm.display.NoOpDisplayMetricsCollector
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.CompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntimeMetrics
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.HostResult
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmInstructionKind
import ru.lazyhat.compukterkraft.lang.runtime.VmPollResult
import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
import ru.lazyhat.compukterkraft.lang.runtime.VmSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonHostRequest
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunner
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield as coroutineYield

fun interface DeviceVmLogger {
    fun log(message: String)
}

private data class RuntimeApiRegistryProfile(
    val baseRegistry: BuiltinRegistry,
    val optionalModules: List<BuiltinModule> = emptyList(),
)

/**
 * The main VM host for a single runtime device instance.
 *
 * Runs a compiled program on a background coroutine [dispatcher]. Visible output is owned by
 * programs through display APIs.
 *
 * ## Thread model
 * - **VM coroutine:** calls `runtime.display.*()`, `runtime.filesystem.*()`, etc.
 *   Filesystem ops go through [HostCallManager].
 * - **Server tick thread:** calls [requestSlice], [drainHostCalls], [deliverHostResults],
 *   [drainDisplayFrames], and [snapshot]. These are the cross-thread entry points.
 *
 * ## Lifecycle
 * Created by `DeviceManager`, started with [boot], stopped with [stop]. On reboot, the old VM
 * is stopped and a new one is created.
 */
class BackgroundDeviceVm(
    override val deviceId: Int,
    override val profile: DeviceProfile,
    dispatcher: CoroutineDispatcher,
    private val labelProvider: () -> String?,
    private val logger: DeviceVmLogger,
    workspace: DeviceWorkspace,
    private val firmwareLoader: FirmwareProgramLoader = ClasspathFirmwareProgramLoader(),
    private val displayMetricsCollector: DisplayMetricsCollector = NoOpDisplayMetricsCollector,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
    private val nativeDisplayEnabled: Boolean = System.getProperty("ckl.vm.native.display") == "true",
    private val strictNativeSchedulerParity: Boolean = System.getProperty("ckl.vm.native.scheduler.strict") == "true",
    private val nativeFilesystemRoot: Path? = null,
    private val nativeDaemonBindings: NativeDaemonBindings = NativeVmDaemonBindings,
) : DeviceVmHandle,
    VmContext {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executionQuota = DeviceExecutionQuota()
    private val stateManager = VmStateManager()
    private val eventManager = EventManager(profile.resources.queues.eventQueueSlots)
    private val eventPayloadStore = EventPayloadStore(profile.resources.queues.eventQueueSlots)
    private val ipcRegistry = IpcChannelRegistry(profile.resources.queues.ipcChannelBytes)
    private val hostCallManager = HostCallManager(profile.resources.queues.hostCallQueueSlots)
    private val programLoader = WorkspaceProgramLoader(workspace)
    private val pathResolver = VmPathResolver()
    private val nativeLibraryPath: String? = System.getProperty("ckl.vm.native.library")
    private val effectiveNativeFilesystemRoot: Path? =
        nativeFilesystemRoot ?: (workspace as? DeviceWorkspaceHost)?.computerRoot(deviceId)
    private val nativeDaemonEnabled: Boolean =
        System.getProperty("ckl.vm.native.daemon") == "true" &&
            (nativeDaemonBindings !== NativeVmDaemonBindings || NativeImageVmRunner.isAvailable(nativeLibraryPath))
    private val nativeDeviceDaemonHandle: Long? =
        if (nativeDaemonEnabled) {
            val handle = nativeDaemonBindings.createDeviceDaemon(
                maxEventQueueSize = profile.resources.queues.eventQueueSlots,
                maxBufferedBytesPerChannel = profile.resources.queues.ipcChannelBytes,
                instructionBudget = profile.resources.cpu.instructionsPerSlice,
            )
            effectiveNativeFilesystemRoot?.let { root ->
                nativeDaemonBindings.attachDeviceDaemonFilesystem(
                    daemonHandle = handle,
                    rootPath = root.toAbsolutePath().normalize().toString(),
                    quotaBytes = profile.resources.storage.diskBytes,
                )
            }
            handle
        } else {
            null
        }
    private val nativeDaemonRuntime: NativeDeviceDaemonRuntime? =
        nativeDeviceDaemonHandle?.let { handle ->
            NativeDeviceDaemonRuntime(
                daemonHandle = handle,
                profile = profile,
                bindings = nativeDaemonBindings,
                runtimeMetricsCollector = runtimeMetricsCollector,
                hostBridge = ::handleNativeDaemonHostRequest,
                compileBridge = ::handleNativeDaemonCompileProgram,
            )
        }
    private val nativeDeviceKernelHandle: Long? =
        if (nativeDaemonEnabled) {
            null
        } else {
            nativeLibraryPath
            ?.takeIf(NativeImageVmRunner::isAvailable)
            ?.let {
                val handle =
                    NativeVmBindings.createDeviceKernel(
                        maxEventQueueSize = profile.resources.queues.eventQueueSlots,
                    maxBufferedBytesPerChannel = profile.resources.queues.ipcChannelBytes,
                    )
                effectiveNativeFilesystemRoot?.let { root ->
                    NativeVmBindings.attachNativeFilesystem(
                        kernelHandle = handle,
                        rootPath = root.toAbsolutePath().normalize().toString(),
                        quotaBytes = profile.resources.storage.diskBytes,
                    )
                }
                handle
            }
        }
    private val nativeDisplayRegistry: NativeDisplayRegistry? =
        nativeDeviceKernelHandle
            ?.takeIf { nativeDisplayEnabled }
            ?.let(::NativeDisplayRegistry)
    private val nativeProcessBridge: NativeProcessBridge =
        nativeDeviceKernelHandle?.let(::NativeVmProcessBridge) ?: NoOpNativeProcessBridge
    private val processManager =
        VmProcessManager(
            scope = scope,
            ctx = this,
            deviceId = deviceId,
            programLoader = programLoader,
            profile = profile,
            runtimeCreator = { pid, parentPid, wd, arg -> createRuntime(pid, parentPid, wd, arg) },
            compilerMetricsCollector = compilerMetricsCollector,
            runtimeMetricsCollector = runtimeMetricsCollector,
            nativeProcessBridge = nativeProcessBridge,
            strictNativeSchedulerParity = strictNativeSchedulerParity,
        )
    private val displayRegistry = DisplayRegistry(displayMetricsCollector)
    private val peripheralRegistry = VmPeripheralRegistry()
    private val runtimeRegistryProfile = createRuntimeRegistryProfile()
    private val stoppedNativeDisplayFrames = mutableListOf<DisplayFrameDelta>()
    private val daemonWakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private var nativeDeviceKernelFreed: Boolean = false
    private var daemonExecutor: Job? = null
    private val nativeDeviceKernelLock = ReentrantReadWriteLock()
    private var executionWindowStartedNanos: Long? = null

    private inner class RuntimeMetricsApi : DeviceRuntimeMetrics {
        override val collectsDetailedMetrics: Boolean = runtimeMetricsCollector !== NoOpRuntimeMetricsCollector

        override fun recordVmSignal(kind: VmSignalKind) {
            runtimeMetricsCollector.recordVmSignal(kind)
        }

        override fun recordVmHostCall(
            moduleName: String,
            functionName: String,
            nanos: Long,
        ) {
            runtimeMetricsCollector.recordVmHostCall(moduleName, functionName, nanos)
        }

        override fun recordVmHostCallWait(
            moduleName: String,
            functionName: String,
            nanos: Long,
        ) {
            runtimeMetricsCollector.recordVmHostCallWait(moduleName, functionName, nanos)
        }

        override fun recordVmInstruction(
            kind: VmInstructionKind,
            nanos: Long,
        ) {
            runtimeMetricsCollector.recordVmInstruction(kind, nanos)
        }

        override fun recordNativeWait(
            kind: String,
            nanos: Long,
            woke: Boolean,
        ) {
            runtimeMetricsCollector.recordNativeWait(kind, nanos, woke)
        }
    }

    /**
     * Observe terminal VM states (stopped, crashed).
     * Derived from [VmStateManager.stateFlow] — emits only terminal transitions.
     */
    val terminalStates: SharedFlow<VmState> =
        stateManager.stateFlow
            .filter { it.isTerminal }
            .shareIn(scope, SharingStarted.Eagerly)

    private var runner: Job? = null
    private val runtime: VmRuntime = createRuntime(processId = 1, parentProcessId = 0, workingDirectory = "", argument = "")

    // ── DeviceVmHandle ────────────────────────────────────────────

    override fun boot(): Boolean {
        nativeDaemonRuntime?.let {
            return bootNativeDaemon(it)
        }
        if (runner?.isActive == true) return false

        stateManager.setState(VmState.Booting)

        runner =
            scope.launch {
                try {
                    val source =
                        firmwareLoader.load(profile.bootScriptName)
                            ?: run {
                                stopInternal(errorMessage = "Missing firmware script: ${profile.bootScriptName}")
                                return@launch
                            }

                    val compiled =
                        ComputerProgramCompiler.compile(
                            source.path,
                            source.source,
                            profile,
                            runtimeRegistryProfile.baseRegistry,
                            programLoader.sourceLoader(deviceId),
                            compilerMetricsCollector,
                        )

                    val program =
                        compiled.program
                            ?: run {
                                stopInternal(errorMessage = "Boot compilation failed: ${compiled.errorMessage.orEmpty()}")
                                return@launch
                            }

                    awaitSlicePermit(processId = 1)
                    logger.log("VM[$deviceId] boot program started")
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
            LOGGER.debug { "DeviceID: $deviceId stop requested, reason: $reason" }
            stopInternal(reason)
        }
    }

    override fun enqueueEvent(event: VmEvent): Boolean {
        val accepted = eventManager.enqueueEvent(event)
        if (accepted) {
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDeviceKernelHandle?.let { handle ->
                        NativeVmBindings.enqueueDeviceEvent(handle, event.name, event.arguments)
                    }
                    if (nativeDaemonRuntime?.enqueueEvent(event) == true) {
                        wakeNativeDaemonExecutor()
                    }
                }
            }
        }
        return accepted
    }

    override fun requestSlice(serverTick: Long) {
        stateManager.updateCurrentTick(serverTick)
        nativeDaemonRuntime?.let { daemon ->
            daemon.refillQuota(serverTick)
            wakeNativeDaemonExecutor()
            return
        }
        var nativeDryRunFirstSelectedPid: Int? = null
        var nativeDryRunSelectedCount = 0L
        var nativeDryRunTurns = 0L
        var nativeDryRunRemainingInstructions = 0L
        var nativeDryRunObserved = false
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDeviceKernelHandle?.let { handle ->
                    val snapshot =
                        NativeVmBindings.addDeviceExecutionQuota(
                            kernelHandle = handle,
                            instructions = profile.resources.cpu.instructionsPerSlice.toLong(),
                            wallNanos = profile.resources.cpu.wallTimeGuardNanosPerSlice,
                            serverTick = serverTick,
                        )
                    runtimeMetricsCollector.recordNativeExecutionQuotaRefill(
                        instructions = snapshot.instructions,
                        wallNanos = snapshot.wallNanos,
                        serverTick = snapshot.serverTick,
                    )
                    val dryRun =
                        NativeVmBindings.runDeviceSchedulerDryRun(
                            kernelHandle = handle,
                            maxTurns = profile.resources.cpu.instructionsPerSlice,
                        )
                    nativeDryRunFirstSelectedPid = dryRun.selectedPids.firstOrNull()
                    nativeDryRunSelectedCount = dryRun.selectedPids.size.toLong()
                    nativeDryRunTurns = dryRun.turns
                    nativeDryRunRemainingInstructions = dryRun.remainingInstructions
                    nativeDryRunObserved = true
                }
            }
        }
        val schedulerTick = processManager.schedulerTick(serverTick)
        if (nativeDryRunObserved) {
            runtimeMetricsCollector.recordNativeSchedulerDryRun(
                turns = nativeDryRunTurns,
                selectedPids = nativeDryRunSelectedCount,
                remainingInstructions = nativeDryRunRemainingInstructions,
                firstSelectionMatched = nativeDryRunFirstSelectedPid == schedulerTick.selectedPid,
            )
        }
        val sent = executionQuota.refill(selectedPid = schedulerTick.selectedPid)
        runtimeMetricsCollector.recordSliceRequest(sent = sent, sleepGated = false)
        runtimeMetricsCollector.recordExecutionQuotaRefill(accepted = sent, unavailable = schedulerTick.selectedPid == null)
    }

    override fun drainHostCalls(): List<HostCall> = hostCallManager.drainHostCalls()

    override fun deliverHostResults(results: List<HostResult>) {
        hostCallManager.deliverHostResults(results)
    }

    override fun snapshot(): VmSnapshot =
        VmSnapshot(
            deviceId = deviceId,
            profile = profile,
            state = stateManager.state,
            currentTick = stateManager.currentTick,
            queuedEvents = eventManager.queuedCount(),
            pendingHostCalls = hostCallManager.pendingCallsCount(),
        )

    override fun attachDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat,
    ): DisplayInfo =
        displayRegistry.attach(displayId, width, height, pixelFormat).also {
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDisplayRegistry?.attach(displayId, width, height, pixelFormat)
                    nativeDaemonRuntime?.attachDisplay(displayId, width, height, pixelFormat)
                }
            }
            if (stateManager.state.isActive) {
                enqueueEvent(VmEvent("display_attach", listOf(displayId, width, height)))
            }
        }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat,
    ): DisplayInfo =
        displayRegistry.resize(displayId, width, height, pixelFormat).also {
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDisplayRegistry?.attach(displayId, width, height, pixelFormat)
                    nativeDaemonRuntime?.attachDisplay(displayId, width, height, pixelFormat)
                }
            }
            enqueueEvent(VmEvent("display_resize", listOf(displayId, width, height)))
        }

    override fun detachDisplay(displayId: Int) {
        displayRegistry.detach(displayId)
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDisplayRegistry?.detach(displayId)
                nativeDaemonRuntime?.detachDisplay(displayId)
            }
        }
        enqueueEvent(VmEvent("display_detach", listOf(displayId)))
    }

    override fun drainDisplayFrames(): List<DisplayFrameDelta> {
        val nativeFrames =
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDisplayRegistry?.drainFrames()
                        ?: nativeDaemonRuntime?.drainDisplayFrames()
                } else {
                    null
                }
            }
        if (nativeFrames != null || stoppedNativeDisplayFrames.isNotEmpty()) {
            displayRegistry.drainFrames()
            return buildList {
                addAll(stoppedNativeDisplayFrames)
                stoppedNativeDisplayFrames.clear()
                if (nativeFrames != null) {
                    addAll(nativeFrames)
                }
            }
        }
        return displayRegistry.drainFrames()
    }

    fun supportsNativeDisplayFramePump(): Boolean =
        nativeDeviceKernelLock.read {
            (nativeDisplayRegistry != null || nativeDaemonRuntime != null) && !nativeDeviceKernelFreed
        }

    fun nativeDisplayWakeSequence(): Long? =
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDisplayRegistry?.displayWakeSequence()
                    ?: nativeDaemonRuntime?.displayWakeSequence()
            } else {
                null
            }
        }

    fun waitForNativeDisplayWake(
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long? =
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDisplayRegistry?.waitForDisplayWake(observedWakeSequence, timeoutMillis)
                    ?: nativeDaemonRuntime?.waitForDisplayWake(observedWakeSequence, timeoutMillis)
            } else {
                null
            }
        }

    fun drainNativeDisplayFrameBytes(): ByteArray? =
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDisplayRegistry?.drainFrameBytes()
                    ?: nativeDaemonRuntime?.drainDisplayFrameBytes()
            } else {
                null
            }
        }

    // ── VmContext ───────────────────────────────────────────────────

    override suspend fun receiveEvent(): VmEvent = eventManager.receiveEvent()

    override fun tryReceiveEvent(): VmEvent? = eventManager.tryReceiveEvent()

    override fun deferEvent(event: VmEvent) = eventManager.deferEvent(event)

    override fun setState(state: VmState) = stateManager.setState(state)

    override fun setSleepUntil(tick: Long?) = stateManager.setSleepUntil(tick)

    override suspend fun schedulingPoint(processId: Int) = applySchedulingPoint(processId)

    override suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T = hostCallManager.awaitHostCall(callFactory)

    override fun resolvePath(path: String): String = pathResolver.resolve(path)

    override fun log(message: String) = logger.log(message)

    override suspend fun writeIpc(
        channel: Int,
        text: String,
    ) {
        val wroteNative =
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDeviceKernelHandle?.let { handle ->
                        NativeVmBindings.writeDeviceIpc(handle, channel, text)
                    } == true
                } else {
                    false
                }
            }
        if (wroteNative) {
            return
        }
        ipcRegistry.write(channel, text)
    }

    override suspend fun pollIpcOrEvent(channel: Int): VmPollResult {
        while (true) {
            val text = ipcRegistry.tryRead(channel)
            if (text.isNotEmpty()) {
                return VmPollResult(kind = "ipc", text = text)
            }
            val event = eventManager.tryReceiveEvent()
            if (event != null) {
                return VmPollResult(kind = "event", event = event)
            }

            val readSignal = ipcRegistry.readSignal(channel)
            stateManager.setState(VmState.WaitingEvent)
            val selected =
                try {
                    select<VmPollResult?> {
                        if (readSignal != null) {
                            readSignal.onReceiveCatching { null }
                        }
                        eventManager.receiveEventClause().invoke { result ->
                            eventManager.acceptSelectedEvent(result)?.let { selectedEvent ->
                                VmPollResult(kind = "event", event = selectedEvent)
                            }
                        }
                    }
                } finally {
                    if (!stateManager.isStopped) {
                        stateManager.setState(VmState.Running)
                    }
                }
            if (selected != null) {
                return selected
            }
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private suspend fun stopInternal(
        reason: VmStopReason = VmStopReason.REQUESTED,
        errorMessage: String? = null,
    ) {
        finishExecutionWindow()
        if (stateManager.isStopped) {
            LOGGER.debug { "DeviceID: $deviceId already stopped, ignoring stop request (reason: $reason, error: $errorMessage)" }
            return
        }

        LOGGER.debug { "DeviceID: $deviceId stopped with reason: $reason, error: $errorMessage" }

        if (!nativeDeviceKernelFreed) {
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    val nativeFrames =
                        nativeDisplayRegistry?.drainFrames()
                            ?: nativeDaemonRuntime?.drainDisplayFrames()
                    nativeFrames?.let(stoppedNativeDisplayFrames::addAll)
                }
            }
        }
        processManager.cancelAll()
        stateManager.stopVm(reason, errorMessage)
        runner?.cancel()
        runner = null
        daemonExecutor?.let { executor ->
            daemonExecutor = null
            if (executor == coroutineContext[Job]) {
                executor.cancel()
            } else {
                executor.cancelAndJoin()
            }
        }
        daemonWakeSignal.close()
        nativeDeviceKernelLock.write {
            if (!nativeDeviceKernelFreed) {
                nativeDeviceKernelHandle?.let(NativeVmBindings::freeDeviceKernel)
                nativeDeviceDaemonHandle?.let(nativeDaemonBindings::freeDeviceDaemon)
                nativeDeviceKernelFreed = true
            }
        }

        LOGGER.debug { "DeviceID: $deviceId stop lock request ended (reason: $reason, error: $errorMessage)" }
    }

    private fun bootNativeDaemon(daemon: NativeDeviceDaemonRuntime): Boolean {
        if (stateManager.state.isActive) return false
        stateManager.setState(VmState.Booting)
        val source =
            firmwareLoader.load(profile.bootScriptName)
                ?: run {
                    stateManager.setState(VmState.Crashed("Missing firmware script: ${profile.bootScriptName}"))
                    return false
                }
        val artifact =
            LanguageFrontend(runtimeRegistryProfile.baseRegistry, compilerMetricsCollector)
                .compileImage(
                    source.path,
                    source.source,
                    programLoader.sourceLoader(deviceId),
                )
        val image = artifact.image
        val errorMessage =
            artifact.bytecode.analysis.diagnostics
                .filter { it.severity == FrontendSeverity.ERROR }
                .joinToString { it.message }
        if (image == null || errorMessage.isNotEmpty()) {
            stateManager.setState(VmState.Crashed("Boot compilation failed: ${errorMessage.ifEmpty { "Compilation failed." }}"))
            return false
        }
        val imageBytes = CkVmImageAbi.encode(image)
        if (imageBytes.size.toLong() > profile.resources.storage.programRomBytes) {
            stateManager.setState(
                VmState.Crashed(
                    "Boot compilation failed: Program exceeds ROM limit: ${imageBytes.size} > ${profile.resources.storage.programRomBytes}",
                ),
            )
            return false
        }

        daemon.boot(
            image = imageBytes,
            programPath = source.path,
            argument = "",
            workingDirectory = "",
        )
        startNativeDaemonExecutor()
        stateManager.setState(VmState.Running)
        enqueueEvent(VmEvent("boot"))
        wakeNativeDaemonExecutor()
        return true
    }

    private fun startNativeDaemonExecutor() {
        if (nativeDaemonRuntime == null || daemonExecutor?.isActive == true) return
        daemonExecutor =
            scope.launch {
                for (ignored in daemonWakeSignal) {
                    var keepRunning = true
                    while (keepRunning && isActive) {
                        val summary = nativeDaemonRuntime.runReadyUntilBlocked()
                        runtimeMetricsCollector.recordSliceRequest(sent = !summary.idle, sleepGated = false)
                        keepRunning = summary.turns > 0 || summary.hostRequests > 0
                    }
                }
            }
    }

    private fun wakeNativeDaemonExecutor() {
        if (nativeDaemonRuntime != null) {
            daemonWakeSignal.trySend(Unit)
        }
    }

    private suspend fun handleNativeDaemonHostRequest(request: NativeDeviceDaemonHostRequest): ByteArray {
        if (request.kind == "hostCall" && request.moduleName == "system" && request.functionName == "log") {
            val message = (request.arguments.firstOrNull() as? VmValue.StringValue)?.value.orEmpty()
            logger.log(message)
            return byteArrayOf(0)
        }
        return byteArrayOf(1)
    }

    private suspend fun handleNativeDaemonCompileProgram(request: NativeDeviceDaemonHostRequest): NativeDaemonCompileResult {
        val path =
            request.path
                ?: return NativeDaemonCompileResult(image = null, exitCode = 1)
        val source =
            programLoader.load(deviceId, path)
                ?: return NativeDaemonCompileResult(image = null, exitCode = 1)
        val artifact =
            LanguageFrontend(runtimeRegistryProfile.baseRegistry, compilerMetricsCollector)
                .compileImage(
                    source.path,
                    source.source,
                    programLoader.sourceLoader(deviceId),
                )
        val image = artifact.image
        val errorMessage =
            artifact.bytecode.analysis.diagnostics
                .filter { it.severity == FrontendSeverity.ERROR }
                .joinToString { it.message }
        if (image == null || errorMessage.isNotEmpty()) {
            return NativeDaemonCompileResult(image = null, exitCode = 1)
        }
        val imageBytes = CkVmImageAbi.encode(image)
        if (imageBytes.size.toLong() > profile.resources.storage.programRomBytes) {
            return NativeDaemonCompileResult(image = null, exitCode = 1)
        }
        return NativeDaemonCompileResult(image = imageBytes, exitCode = 0)
    }

    private fun finishExecutionWindow() {
        val started = executionWindowStartedNanos ?: return
        executionWindowStartedNanos = null
        runtimeMetricsCollector.recordVmExecutionWindow(System.nanoTime() - started)
    }

    private suspend fun awaitSlicePermit(processId: Int) {
        finishExecutionWindow()
        stateManager.setState(
            when {
                stateManager.sleepUntilTick != null -> VmState.Sleeping
                stateManager.isBooting -> VmState.Booting
                else -> VmState.Running
            },
        )
        executionQuota.awaitPermit(processId)
        runtimeMetricsCollector.recordSlicePermitReceived()
        runtimeMetricsCollector.recordExecutionQuotaPermitConsumed()
        executionWindowStartedNanos = System.nanoTime()
        stateManager.updateSliceDeadlineNanos(profile.resources.cpu.wallTimeGuardNanosPerSlice)
        stateManager.setState(VmState.Running)
    }

    private suspend fun applySchedulingPoint(processId: Int) {
        coroutineContext.ensureActive()
        if (System.nanoTime() >= stateManager.sliceDeadlineNanos) {
            runtimeMetricsCollector.recordSchedulingPoint(waitedForSlice = true)
            awaitSlicePermit(processId)
        } else {
            runtimeMetricsCollector.recordSchedulingPoint(waitedForSlice = false)
            coroutineYield()
        }
    }

    private fun createRuntime(
        processId: Int,
        parentProcessId: Int,
        workingDirectory: String,
        argument: String,
    ): VmRuntime {
        val runtimePathResolver = VmPathResolver(workingDirectory)

        val systemApi =
            VmSystemApi(
                ctx = this,
                deviceId = deviceId,
                currentTickProvider = { stateManager.currentTick },
                labelProvider = labelProvider,
            )
        val filesystemApi = VmFileSystemApi(ctx = this, pathResolver = runtimePathResolver)
        val peripheralsApi = VmPeripheralRuntimeApi(peripheralRegistry)
        val processApi =
            VmProcessApi(
                processId = processId,
                initialArgument = argument,
                pathResolver = runtimePathResolver,
                filesystemApi = filesystemApi,
                processManager = processManager,
            )

        return VmRuntime(
            ctx = this,
            initialProfile = profile,
            processId = processId,
            parentProcessId = parentProcessId,
            runtimeRegistry = runtimeRegistryProfile.baseRegistry,
            systemApi = systemApi,
            displayApi = VmDisplayApi(displayRegistry),
            filesystemApi = filesystemApi,
            processApi = processApi,
            ipcApi = VmIpcApi(ipcRegistry),
            eventApi = VmEventApi(eventPayloadStore),
            peripheralsApi = peripheralsApi,
            metricsApi = RuntimeMetricsApi(),
            nativeDeviceKernelHandle = nativeDeviceKernelHandle ?: 0L,
            nativeWorkingDirectory = workingDirectory,
            processStateReporter = processManager,
        )
    }

    private fun createRuntimeRegistryProfile(): RuntimeApiRegistryProfile {
        val defaultRegistry = LanguageBuiltins.defaultRuntimeRegistry
        val baseModules =
            buildList {
                defaultRegistry.module("runtime")?.let(::add)
                defaultRegistry.module("system")?.let(::add)
                if (DeviceCapability.DISPLAY in profile.allowedCapabilities) {
                    defaultRegistry.module("display")?.let(::add)
                }
                if (DeviceCapability.FILESYSTEM in profile.allowedCapabilities) {
                    defaultRegistry.module("filesystem")?.let(::add)
                }
                if (DeviceCapability.EVENTS in profile.allowedCapabilities) {
                    defaultRegistry.module("events")?.let(::add)
                }
                if (DeviceCapability.IPC in profile.allowedCapabilities) {
                    defaultRegistry.module("ipc")?.let(::add)
                }
                if (DeviceCapability.SYSTEM in profile.allowedCapabilities) {
                    defaultRegistry.module("process")?.let(::add)
                    defaultRegistry.module("strings")?.let(::add)
                }
            }

        return RuntimeApiRegistryProfile(
            baseRegistry =
                BuiltinRegistry(
                    modules = baseModules,
                    globals = defaultRegistry.globals,
                    builtinTypes = defaultRegistry.builtinTypes,
                ),
        )
    }
}
