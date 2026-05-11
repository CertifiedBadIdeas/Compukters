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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.device.runtime.ClasspathFirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.core.device.vm.display.NoOpDisplayMetricsCollector
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.CompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonHostRequest
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

fun interface DeviceVmLogger {
    fun log(message: String)
}

private data class RuntimeApiRegistryProfile(
    val baseRegistry: BuiltinRegistry,
)

/**
 * The main VM host for a single runtime device instance.
 *
 * Runs a compiled program on a background coroutine [dispatcher]. Visible output is owned by
 * programs through display APIs.
 *
 * ## Thread model
 * - **VM coroutine:** drives daemon-local runtime APIs and host bridges.
 * - **Server tick thread:** calls [requestSlice], [drainDisplayFrames], and [snapshot].
 *   These are the cross-thread entry points.
 *
 * ## Lifecycle
 * Created by `DeviceManager`, started with [boot], stopped with [stop]. On reboot, the old VM
 * is stopped and a new one is created.
 */
class BackgroundDeviceVm(
    override val deviceId: Int,
    override val profile: DeviceProfile,
    dispatcher: CoroutineDispatcher,
    labelProvider: () -> String?,
    private val logger: DeviceVmLogger,
    workspace: DeviceWorkspace,
    private val firmwareLoader: FirmwareProgramLoader = ClasspathFirmwareProgramLoader(),
    private val displayMetricsCollector: DisplayMetricsCollector = NoOpDisplayMetricsCollector,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
    private val nativeFilesystemRoot: Path? = null,
    private val nativeDaemonBindings: NativeDaemonBindings = NativeVmDaemonBindings,
) : DeviceVmHandle {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateManager = VmStateManager()
    private val programLoader = WorkspaceProgramLoader(workspace)
    private val effectiveNativeFilesystemRoot: Path? =
        nativeFilesystemRoot ?: (workspace as? DeviceWorkspaceHost)?.computerRoot(deviceId)
    private val nativeDeviceDaemonHandle: Long =
        nativeDaemonBindings
            .createDeviceDaemon(
                maxEventQueueSize = profile.resources.queues.eventQueueSlots,
                maxBufferedBytesPerChannel = profile.resources.queues.ipcChannelBytes,
                instructionBudget = profile.resources.cpu.instructionsPerSlice,
                deviceId = deviceId,
                profileName = profile.displayName,
            )
            .also { handle ->
                effectiveNativeFilesystemRoot?.let { root ->
                    nativeDaemonBindings.attachDeviceDaemonFilesystem(
                        daemonHandle = handle,
                        rootPath = root.toAbsolutePath().normalize().toString(),
                        quotaBytes = profile.resources.storage.diskBytes,
                    )
                }
            }
    private val nativeDaemonRuntime: NativeDeviceDaemonRuntime =
        NativeDeviceDaemonRuntime(
            daemonHandle = nativeDeviceDaemonHandle,
            profile = profile,
            bindings = nativeDaemonBindings,
            runtimeMetricsCollector = runtimeMetricsCollector,
            hostBridge = ::handleNativeDaemonHostRequest,
            compileBridge = ::handleNativeDaemonCompileProgram,
        )
    private val displayRegistry = DisplayRegistry()
    private val runtimeRegistryProfile = createRuntimeRegistryProfile()
    private val stoppedNativeDisplayFrames = mutableListOf<DisplayFrameDelta>()
    private val daemonWakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private var nativeDeviceKernelFreed: Boolean = false
    private var daemonExecutor: Job? = null
    private val nativeDeviceKernelLock = ReentrantReadWriteLock()

    /**
     * Observe terminal VM states (stopped, crashed).
     * Derived from [VmStateManager.stateFlow] — emits only terminal transitions.
     */
    val terminalStates: SharedFlow<VmState> =
        stateManager.stateFlow
            .filter { it.isTerminal }
            .shareIn(scope, SharingStarted.Eagerly)

    // ── DeviceVmHandle ────────────────────────────────────────────

    override fun boot(): Boolean = bootNativeDaemon(nativeDaemonRuntime)

    override fun stop(reason: VmStopReason) {
        scope.launch {
            LOGGER.debug { "DeviceID: $deviceId stop requested, reason: $reason" }
            stopInternal(reason)
        }
    }

    override fun enqueueEvent(event: VmEvent): Boolean =
        nativeDeviceKernelLock.read {
            if (nativeDeviceKernelFreed) {
                false
            } else {
                nativeDaemonRuntime.enqueueEvent(event).also { accepted ->
                    if (accepted) {
                        wakeNativeDaemonExecutor()
                    }
                }
            }
        }

    override fun requestSlice(serverTick: Long) {
        stateManager.updateCurrentTick(serverTick)
        nativeDaemonRuntime.refillQuota(serverTick)
        wakeNativeDaemonExecutor()
    }

    override fun snapshot(): VmSnapshot =
        VmSnapshot(
            deviceId = deviceId,
            profile = profile,
            state = stateManager.state,
            currentTick = stateManager.currentTick,
            queuedEvents = 0,
            pendingHostCalls = 0,
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
                    nativeDaemonRuntime.attachDisplay(displayId, width, height, pixelFormat)
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
                    nativeDaemonRuntime.attachDisplay(displayId, width, height, pixelFormat)
                }
            }
            enqueueEvent(VmEvent("display_resize", listOf(displayId, width, height)))
        }

    override fun detachDisplay(displayId: Int) {
        displayRegistry.detach(displayId)
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDaemonRuntime.detachDisplay(displayId)
            }
        }
        enqueueEvent(VmEvent("display_detach", listOf(displayId)))
    }

    override fun drainDisplayFrames(): List<DisplayFrameDelta> {
        val nativeFrames =
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    nativeDaemonRuntime.drainDisplayFrames()
                } else {
                    emptyList()
                }
            }
        displayMetricsCollector.recordFrameDrain(nativeFrames)
        return buildList {
            addAll(stoppedNativeDisplayFrames)
            stoppedNativeDisplayFrames.clear()
            addAll(nativeFrames)
        }
    }

    fun supportsNativeDisplayFramePump(): Boolean =
        nativeDeviceKernelLock.read {
            !nativeDeviceKernelFreed
        }

    fun nativeDisplayWakeSequence(): Long? =
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDaemonRuntime.displayWakeSequence()
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
                nativeDaemonRuntime.waitForDisplayWake(observedWakeSequence, timeoutMillis)
            } else {
                null
            }
        }

    fun drainNativeDisplayFrameBytes(): ByteArray? =
        nativeDeviceKernelLock.read {
            if (!nativeDeviceKernelFreed) {
                nativeDaemonRuntime.drainDisplayFrameBytes()
            } else {
                null
            }
        }

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

        if (!nativeDeviceKernelFreed) {
            nativeDeviceKernelLock.read {
                if (!nativeDeviceKernelFreed) {
                    stoppedNativeDisplayFrames.addAll(nativeDaemonRuntime.drainDisplayFrames())
                }
            }
        }
        stateManager.stopVm(reason, errorMessage)
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
                nativeDaemonBindings.freeDeviceDaemon(nativeDeviceDaemonHandle)
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
        if (daemonExecutor?.isActive == true) return
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
        daemonWakeSignal.trySend(Unit)
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
