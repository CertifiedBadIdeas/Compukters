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
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeDisplayProfilingTest {
    private data class ProfilingRun(
        val displayMetrics: RecordingDisplayMetricsCollector,
        val runtimeMetrics: RecordingRuntimeMetricsCollector,
        val compilerMetrics: RecordingCompilerMetricsCollector,
    )

    private data class TickObservation(
        val maxQueuedEvents: Int,
        val finalQueuedEvents: Int,
        val maxPendingHostCalls: Int,
        val finalPendingHostCalls: Int,
    )

    private data class HeldEnterProfilingRun(
        val profiling: ProfilingRun,
        val enterEventsQueued: Int,
        val settleTicks: Int,
        val maxQueuedEvents: Int,
        val finalQueuedEvents: Int,
        val maxPendingHostCalls: Int,
        val finalPendingHostCalls: Int,
        val displayFramesDrained: Int,
    ) {
        fun summary(): String =
            "held-enter: enterEventsQueued=$enterEventsQueued, settleTicks=$settleTicks, " +
                "maxQueuedEvents=$maxQueuedEvents, finalQueuedEvents=$finalQueuedEvents, " +
                "maxPendingHostCalls=$maxPendingHostCalls, finalPendingHostCalls=$finalPendingHostCalls, " +
                "displayFramesDrained=$displayFramesDrained"
    }

    private class ClasspathFirmwareLoader : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource {
            val source =
                RuntimeDisplayProfilingTest::class.java.classLoader
                    .getResourceAsStream("firmware/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("firmware/$path missing from classpath")
            return LoadedFirmwareProgramSource(path, source)
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
                while (permitsSentAfter > permitsSentBefore && metrics.snapshot().vm.slicePermitsReceived < permitsSentAfter &&
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

    private fun runTerminalWorkload(
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

    private fun runHeldEnterWorkload(
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

    @Test
    fun bundledTerminalWorkloadProducesProfilingMetrics() {
        val run = runTerminalWorkload(delayMillis = 10, bootTicks = 80, inputTicks = 20, enterTicks = 40)
        val displaySnapshot = run.displayMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()
        println(displaySnapshot.summary())
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(displaySnapshot.operations.fillRectCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.copyRectCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.fillRectCalls < 1000, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.presentCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.frameCount > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.tileCount > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.payloadBytes > 0, displaySnapshot.summary())
        assertTrue(runtimeSnapshot.tick.serverTickCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.requestSliceCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostCallDrainCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostCallDispatchCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostResultDeliveryCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.displayFrameDrainCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.sliceRequests > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.slicePermitsReceived > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.executionWindowNanos > 0, runtimeSnapshot.summary())
        assertTrue(compilerSnapshot.compileCalls > 0, compilerSnapshot.summary())
        assertTrue(compilerSnapshot.compileNanos > 0, compilerSnapshot.summary())
    }

    @Test
    fun sustainedTerminalWorkloadProducesNoDelayProfilingMetrics() {
        val run = runTerminalWorkload(delayMillis = 0, bootTicks = 120, inputTicks = 40, enterTicks = 80)
        val displaySnapshot = run.displayMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()

        println(displaySnapshot.summary())
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(displaySnapshot.operations.blitMonoNanos >= 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frameBuild.buildCalls > 0, displaySnapshot.summary())
        assertTrue(
            runtimeSnapshot.vm.pauseSignals + runtimeSnapshot.vm.yieldSignals + runtimeSnapshot.vm.hostCallSignals > 0,
            runtimeSnapshot.summary(),
        )
        assertTrue(runtimeSnapshot.vm.averageExecutionWindowNanos >= 0, runtimeSnapshot.summary())
        assertTrue(compilerSnapshot.compileCalls > 0, compilerSnapshot.summary())
        assertTrue(compilerSnapshot.compileNanos > 0, compilerSnapshot.summary())
    }

    @Test
    fun heldEnterWorkloadProducesBacklogProfilingMetrics() {
        val run = runHeldEnterWorkload(repeatEnterEvents = 120, settleTicks = 220)
        val displaySnapshot = run.profiling.displayMetrics.snapshot()
        val runtimeSnapshot = run.profiling.runtimeMetrics.snapshot()
        val compilerSnapshot = run.profiling.compilerMetrics.snapshot()

        println(run.summary())
        println(displaySnapshot.summary())
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(run.enterEventsQueued == 120, run.summary())
        assertTrue(run.maxQueuedEvents > 0, run.summary())
        assertTrue(runtimeSnapshot.vm.hostCallSignals > 0, runtimeSnapshot.summary())
        assertTrue(
            runtimeSnapshot.hostCalls.any { it.moduleName == "events" && it.functionName == "tryPull" },
            runtimeSnapshot.summary(),
        )
        assertTrue(
            runtimeSnapshot.hostCalls.any { it.moduleName == "ipc" && it.functionName == "write" },
            runtimeSnapshot.summary(),
        )
        assertTrue(runtimeSnapshot.instructions.isNotEmpty(), runtimeSnapshot.summary())
        assertTrue(displaySnapshot.frames.frameCount > 0, displaySnapshot.summary())
    }
}
