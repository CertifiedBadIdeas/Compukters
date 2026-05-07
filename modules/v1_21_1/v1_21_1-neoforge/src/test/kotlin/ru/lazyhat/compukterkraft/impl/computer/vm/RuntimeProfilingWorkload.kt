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

package ru.lazyhat.compukterkraft.impl.computer.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import ru.lazyhat.compukterkraft.core.device.vm.display.RecordingDisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.frontend.RecordingCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue

internal object RuntimeProfilingWorkload {
    data class ProfilingRun(
        val displayMetrics: RecordingDisplayMetricsCollector,
        val runtimeMetrics: RecordingRuntimeMetricsCollector,
        val compilerMetrics: RecordingCompilerMetricsCollector,
    )

    data class TickObservation(
        val maxQueuedEvents: Int,
        val finalQueuedEvents: Int,
        val maxPendingHostCalls: Int,
        val finalPendingHostCalls: Int,
    )

    data class HeldEnterProfilingRun(
        val profiling: ProfilingRun,
        val enterEventsQueued: Int,
        val settleTicks: Int,
        val maxQueuedEvents: Int,
        val finalQueuedEvents: Int,
        val maxPendingHostCalls: Int,
        val finalPendingHostCalls: Int,
        val displayFramesDrained: Int,
    ) {
        val summaryMetrics: HeldEnterWorkloadSummary
            get() =
                HeldEnterWorkloadSummary(
                    enterEventsQueued = enterEventsQueued,
                    settleTicks = settleTicks,
                    maxQueuedEvents = maxQueuedEvents,
                    finalQueuedEvents = finalQueuedEvents,
                    maxPendingHostCalls = maxPendingHostCalls,
                    finalPendingHostCalls = finalPendingHostCalls,
                    displayFramesDrained = displayFramesDrained,
                )

        fun summary(): String =
            buildString {
                appendLine("held-enter:")
                appendLine("  input: enterEventsQueued=$enterEventsQueued, settleTicks=$settleTicks")
                appendLine("  queues: maxQueuedEvents=$maxQueuedEvents, finalQueuedEvents=$finalQueuedEvents")
                appendLine("  host-calls: maxPending=$maxPendingHostCalls, finalPending=$finalPendingHostCalls")
                append("  display: framesDrained=$displayFramesDrained")
            }
    }

    private class ClasspathFirmwareLoader : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource {
            val source =
                RuntimeProfilingWorkload::class.java.classLoader
                    .getResourceAsStream("firmware/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("firmware/$path missing from classpath")
            return LoadedFirmwareProgramSource(path, source)
        }
    }

    fun <T> withVmRunner(
        runner: String,
        nativeLibrary: String? = null,
        block: () -> T,
    ): T {
        val oldRunner = System.getProperty(RUNNER_PROPERTY)
        val oldNativeLibrary = System.getProperty(NATIVE_LIBRARY_PROPERTY)
        try {
            System.setProperty(RUNNER_PROPERTY, runner)
            if (nativeLibrary.isNullOrBlank()) {
                System.clearProperty(NATIVE_LIBRARY_PROPERTY)
            } else {
                System.setProperty(NATIVE_LIBRARY_PROPERTY, nativeLibrary)
            }
            return block()
        } finally {
            setOrClear(RUNNER_PROPERTY, oldRunner)
            setOrClear(NATIVE_LIBRARY_PROPERTY, oldNativeLibrary)
        }
    }

    fun runTerminalWorkload(
        delayMillis: Long,
        bootTicks: Int,
        inputTicks: Int,
        enterTicks: Int,
    ): ProfilingRun {
        val root = createTempDirectory("compukterkraft-display-profiling")
        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val displayMetrics = RecordingDisplayMetricsCollector()
            val runtimeMetrics = RecordingRuntimeMetricsCollector()
            val compilerMetrics = RecordingCompilerMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                    displayMetricsCollector = displayMetrics,
                    runtimeMetricsCollector = runtimeMetrics,
                    compilerMetricsCollector = compilerMetrics,
                )
            val dispatcher = HostCallDispatcher(deviceId = 1, workspace = workspace)

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            waitForBootCompile(compilerMetrics)
            runTicks(vm, dispatcher, runtimeMetrics, ticks = bootTicks, delayMillis = delayMillis)
            waitForRuntimeProgress(runtimeMetrics)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            runTicks(vm, dispatcher, runtimeMetrics, ticks = inputTicks, delayMillis = delayMillis)
            waitForRuntimeProgress(runtimeMetrics)

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runTicks(vm, dispatcher, runtimeMetrics, ticks = enterTicks, delayMillis = delayMillis)
            waitForRuntimeProgress(runtimeMetrics)
            val drainStarted = System.nanoTime()
            val frames = vm.drainDisplayFrames()
            runtimeMetrics.recordDisplayFrameDrain(frames.size, System.nanoTime() - drainStarted)

            return ProfilingRun(displayMetrics, runtimeMetrics, compilerMetrics)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun runHeldEnterWorkload(
        repeatEnterEvents: Int,
        settleTicks: Int,
    ): HeldEnterProfilingRun {
        val root = createTempDirectory("compukterkraft-held-enter-profiling")
        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val displayMetrics = RecordingDisplayMetricsCollector()
            val runtimeMetrics = RecordingRuntimeMetricsCollector()
            val compilerMetrics = RecordingCompilerMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                    displayMetricsCollector = displayMetrics,
                    runtimeMetricsCollector = runtimeMetrics,
                    compilerMetricsCollector = compilerMetrics,
                )
            val dispatcher = HostCallDispatcher(deviceId = 1, workspace = workspace)

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            waitForBootCompile(compilerMetrics)
            runTicks(vm, dispatcher, runtimeMetrics, ticks = 100, delayMillis = 10)
            waitForRuntimeProgress(runtimeMetrics)

            var acceptedEnterEvents = 0
            var maxQueuedEvents = vm.snapshot().queuedEvents
            var maxPendingHostCalls = vm.snapshot().pendingHostCalls
            repeat(repeatEnterEvents) {
                if (vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, true)))) {
                    acceptedEnterEvents += 1
                }
                val inputObservation = runTicks(vm, dispatcher, runtimeMetrics, ticks = 1, delayMillis = 0)
                maxQueuedEvents =
                    maxOf(maxQueuedEvents, inputObservation.maxQueuedEvents, inputObservation.finalQueuedEvents)
                maxPendingHostCalls =
                    maxOf(
                        maxPendingHostCalls,
                        inputObservation.maxPendingHostCalls,
                        inputObservation.finalPendingHostCalls,
                    )
            }
            val observation = runTicks(vm, dispatcher, runtimeMetrics, ticks = settleTicks, delayMillis = 0)
            val drainStarted = System.nanoTime()
            val frames = vm.drainDisplayFrames()
            runtimeMetrics.recordDisplayFrameDrain(frames.size, System.nanoTime() - drainStarted)

            return HeldEnterProfilingRun(
                profiling = ProfilingRun(displayMetrics, runtimeMetrics, compilerMetrics),
                enterEventsQueued = acceptedEnterEvents,
                settleTicks = settleTicks,
                maxQueuedEvents = maxOf(maxQueuedEvents, observation.maxQueuedEvents),
                finalQueuedEvents = observation.finalQueuedEvents,
                maxPendingHostCalls = maxOf(maxPendingHostCalls, observation.maxPendingHostCalls),
                finalPendingHostCalls = observation.finalPendingHostCalls,
                displayFramesDrained = frames.size,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun profile(): DeviceProfile =
        DeviceProfile(
            id = "display-profiling-test",
            displayName = "Display Profiling Test",
            cpuBudgetNanosPerSlice = 500_000,
            maxEventQueueSize = 64,
            allowedCapabilities =
                setOf(
                    DeviceCapability.DISPLAY,
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.IPC,
                ),
            resources =
                DeviceResources(
                    cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 500_000),
                    memory = DeviceMemoryResources(),
                    storage = DeviceStorageResources(programRomBytes = 128 * 1024, diskBytes = 1024 * 1024),
                    queues = DeviceQueueResources(eventQueueSlots = 64, hostCallQueueSlots = 64),
                ),
        )

    private fun runTicks(
        vm: BackgroundDeviceVm,
        dispatcher: HostCallDispatcher,
        metrics: RecordingRuntimeMetricsCollector,
        ticks: Int,
        delayMillis: Long = 10,
    ): TickObservation =
        runBlocking(Dispatchers.Default) {
            var maxQueuedEvents = vm.snapshot().queuedEvents
            var maxPendingHostCalls = vm.snapshot().pendingHostCalls
            repeat(ticks) { tick ->
                val tickStarted = System.nanoTime()
                val requestStarted = System.nanoTime()
                val permitsSentBefore = metrics.snapshot().vm.slicePermitsSent
                vm.requestSlice(tick.toLong())
                val permitsSentAfter = metrics.snapshot().vm.slicePermitsSent
                metrics.recordRequestSlice(System.nanoTime() - requestStarted)

                val drainStarted = System.nanoTime()
                val calls = vm.drainHostCalls()
                metrics.recordHostCallDrain(calls.size, System.nanoTime() - drainStarted)

                val dispatchStarted = System.nanoTime()
                val results = calls.map(dispatcher::dispatch)
                metrics.recordHostCallDispatch(calls.size, System.nanoTime() - dispatchStarted)

                val deliverStarted = System.nanoTime()
                if (results.isNotEmpty()) {
                    vm.deliverHostResults(results)
                }
                metrics.recordHostResultDelivery(results.size, System.nanoTime() - deliverStarted)
                metrics.recordServerTick(System.nanoTime() - tickStarted)

                if (delayMillis > 0) {
                    kotlinx.coroutines.delay(delayMillis)
                } else {
                    var yields = 0
                    while (permitsSentAfter > permitsSentBefore &&
                        metrics.snapshot().vm.slicePermitsReceived < permitsSentAfter &&
                        yields < 32
                    ) {
                        kotlinx.coroutines.yield()
                        yields += 1
                    }
                }
                val snapshot = vm.snapshot()
                maxQueuedEvents = maxOf(maxQueuedEvents, snapshot.queuedEvents)
                maxPendingHostCalls = maxOf(maxPendingHostCalls, snapshot.pendingHostCalls)
            }
            val finalSnapshot = vm.snapshot()
            TickObservation(
                maxQueuedEvents = maxQueuedEvents,
                finalQueuedEvents = finalSnapshot.queuedEvents,
                maxPendingHostCalls = maxPendingHostCalls,
                finalPendingHostCalls = finalSnapshot.pendingHostCalls,
            )
        }

    private fun waitForBootCompile(metrics: RecordingCompilerMetricsCollector) =
        runBlocking(Dispatchers.Default) {
            repeat(1_000) {
                if (metrics.snapshot().compileCalls > 0) return@runBlocking
                kotlinx.coroutines.delay(1)
            }
        }

    private fun waitForRuntimeProgress(metrics: RecordingRuntimeMetricsCollector) =
        runBlocking(Dispatchers.Default) {
            repeat(1_000) {
                val vm = metrics.snapshot().vm
                if (vm.executionWindows > 0 && vm.pauseSignals + vm.yieldSignals + vm.hostCallSignals > 0) return@runBlocking
                kotlinx.coroutines.delay(1)
            }
        }

    private fun setOrClear(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
    }

    private const val RUNNER_PROPERTY = "ckl.vm.runner"
    private const val NATIVE_LIBRARY_PROPERTY = "ckl.vm.native.library"
}
