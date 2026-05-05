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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.device.runtime.ClasspathFirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.core.device.vm.api.ComputerStdioBroadcaster
import ru.lazyhat.compukterkraft.core.device.vm.api.ScreenBufferVtSink
import ru.lazyhat.compukterkraft.core.device.vm.api.VmFileSystemApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmEventApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmIpcApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmPeripheralRegistry
import ru.lazyhat.compukterkraft.core.device.vm.api.VmPeripheralRuntimeApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmProcessApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmSystemApi
import ru.lazyhat.compukterkraft.core.device.vm.api.VmTerminalApi
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.HostResult
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser
import kotlin.coroutines.coroutineContext
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
 * Runs a compiled program on a background coroutine [dispatcher]. Owns a [ScreenBuffer]
 * that the VM coroutine writes to directly (no HostCall roundtrip for terminal I/O).
 *
 * ## Thread model
 * - **VM coroutine:** calls `runtime.terminal.write()`, `runtime.filesystem.*()`, etc.
 *   Terminal writes go directly to [screenBuffer]. Filesystem ops go through [HostCallManager].
 * - **Server tick thread:** calls [requestSlice], [drainHostCalls], [deliverHostResults],
 *   [readScreenSnapshot], and [snapshot]. These are the cross-thread entry points.
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
) : DeviceVmHandle,
    VmContext {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val slicePermits = Channel<Unit>(capacity = 1)
    private val stateManager = VmStateManager()
    private val eventManager = EventManager(profile.resources.queues.eventQueueSlots)
    private val eventPayloadStore = EventPayloadStore(profile.resources.queues.eventQueueSlots)
    private val ipcRegistry = IpcChannelRegistry(profile.resources.queues.ipcChannelBytes)
    private val hostCallManager = HostCallManager(profile.resources.queues.hostCallQueueSlots)
    private val programLoader = WorkspaceProgramLoader(workspace)
    private val pathResolver = VmPathResolver()
    private val processManager =
        VmProcessManager(
            scope = scope,
            ctx = this,
            deviceId = deviceId,
            programLoader = programLoader,
            profile = profile,
            runtimeCreator = { wd, arg -> createRuntime(wd, arg) },
        )
    private val screenBuffer = ScreenBuffer(profile.terminalWidth, profile.terminalHeight, profile.colorTerminal)
    private val displayRegistry = DisplayRegistry()
    val stdioBroadcaster = ComputerStdioBroadcaster()
    private val screenBufferFeeder: VtParser = VtParser(ScreenBufferVtSink(screenBuffer))
    private val screenBufferConsumer =
        ComputerStdioBroadcaster.Consumer { bytes ->
            // Keep the server-side ScreenBuffer synced with the VM's byte stream.
            // Used by the (legacy) snapshot path that still feeds Workbench + backward-compat clients
            // until Epic 4 moves the buffer entirely to the client side.
            screenBufferFeeder.feed(String(bytes, Charsets.UTF_8))
        }
    private val peripheralRegistry = VmPeripheralRegistry()
    private val runtimeRegistryProfile = createRuntimeRegistryProfile()

    /**
     * Observe terminal VM states (stopped, crashed).
     * Derived from [VmStateManager.stateFlow] — emits only terminal transitions.
     */
    val terminalStates: SharedFlow<VmState> =
        stateManager.stateFlow
            .filter { it.isTerminal }
            .shareIn(scope, SharingStarted.Eagerly)

    private var runner: Job? = null
    private val runtime: VmRuntime =
        run {
            stdioBroadcaster.addConsumer(screenBufferConsumer)
            createRuntime("", "")
        }

    // ── DeviceVmHandle ────────────────────────────────────────────

    override fun boot(): Boolean {
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
                        )

                    val program =
                        compiled.program
                            ?: run {
                                stopInternal(errorMessage = "Boot compilation failed: ${compiled.errorMessage.orEmpty()}")
                                return@launch
                            }

                    awaitSlicePermit()
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
            deviceId = deviceId,
            profile = profile,
            state = stateManager.state,
            currentTick = stateManager.currentTick,
            queuedEvents = eventManager.queuedCount(),
            pendingHostCalls = hostCallManager.pendingCallsCount(),
        )

    override fun readScreenSnapshot(): ScreenBufferSnapshot? = screenBuffer.snapshot()

    override fun forceScreenSnapshot(): ScreenBufferSnapshot = screenBuffer.forceSnapshot()

    override fun attachDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat,
    ): DisplayInfo =
        displayRegistry.attach(displayId, width, height, pixelFormat).also {
            enqueueEvent(VmEvent("display_attach", listOf(displayId, width, height)))
        }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat,
    ): DisplayInfo =
        displayRegistry.resize(displayId, width, height, pixelFormat).also {
            enqueueEvent(VmEvent("display_resize", listOf(displayId, width, height)))
        }

    override fun detachDisplay(displayId: Int) {
        displayRegistry.detach(displayId)
        enqueueEvent(VmEvent("display_detach", listOf(displayId)))
    }

    override fun drainDisplayFrames(): List<DisplayFrameDelta> = displayRegistry.drainFrames()

    // ── VmContext ───────────────────────────────────────────────────

    override suspend fun receiveEvent(): VmEvent = eventManager.receiveEvent()

    override fun tryReceiveEvent(): VmEvent? = eventManager.tryReceiveEvent()

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
        if (stateManager.isStopped) {
            LOGGER.debug { "DeviceID: $deviceId already stopped, ignoring stop request (reason: $reason, error: $errorMessage)" }
            return
        }

        LOGGER.debug { "DeviceID: $deviceId stopped with reason: $reason, error: $errorMessage" }

        stateManager.stopVm(reason, errorMessage)
        processManager.cancelAll()
        runner?.cancel()
        runner = null

        LOGGER.debug { "DeviceID: $deviceId stop lock request ended (reason: $reason, error: $errorMessage)" }
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
        stateManager.updateSliceDeadlineNanos(profile.resources.cpu.wallTimeGuardNanosPerSlice)
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
                deviceId = deviceId,
                currentTickProvider = { stateManager.currentTick },
                labelProvider = labelProvider,
            )
        val stdioApi = stdioBroadcaster
        val terminalApi = VmTerminalApi(stdio = stdioApi, cursorProvider = stdioBroadcaster::cursor, ctx = this)
        val filesystemApi = VmFileSystemApi(ctx = this)
        val peripheralsApi = VmPeripheralRuntimeApi(peripheralRegistry)
        val processApi =
            VmProcessApi(
                ctx = this,
                initialArgument = argument,
                deviceId = deviceId,
                pathResolver = pathResolver,
                filesystemApi = filesystemApi,
                processManager = processManager,
                terminal = terminalApi,
            )

        return VmRuntime(
            ctx = this,
            initialProfile = profile,
            runtimeRegistry = runtimeRegistryProfile.baseRegistry,
            systemApi = systemApi,
            terminalApi = terminalApi,
            displayApi = VmDisplayApi(displayRegistry),
            stdioApi = stdioApi,
            filesystemApi = filesystemApi,
            processApi = processApi,
            ipcApi = VmIpcApi(ipcRegistry),
            eventApi = VmEventApi(eventPayloadStore),
            peripheralsApi = peripheralsApi,
        )
    }

    private fun createRuntimeRegistryProfile(): RuntimeApiRegistryProfile {
        val defaultRegistry = LanguageBuiltins.defaultRuntimeRegistry
        val baseModules =
            buildList {
                defaultRegistry.module("terminal")?.let(::add)
                defaultRegistry.module("stdout")?.let(::add)
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
