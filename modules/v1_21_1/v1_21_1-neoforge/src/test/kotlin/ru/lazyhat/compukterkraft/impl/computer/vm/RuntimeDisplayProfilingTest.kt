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
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import ru.lazyhat.compukterkraft.core.device.vm.display.RecordingDisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.input.KeyCodes
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
            cpuBudgetNanosPerSlice = 5_000_000,
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
                    cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 5_000_000),
                    memory = DeviceMemoryResources(),
                    storage = DeviceStorageResources(programRomBytes = 128 * 1024, diskBytes = 1024 * 1024),
                    queues = DeviceQueueResources(eventQueueSlots = 64, hostCallQueueSlots = 64),
                ),
        )

    private fun runTicks(
        vm: BackgroundDeviceVm,
        dispatcher: HostCallDispatcher,
        ticks: Int,
    ): Long =
        runBlocking {
            val started = System.nanoTime()
            repeat(ticks) { tick ->
                vm.requestSlice(tick.toLong())
                val results = vm.drainHostCalls().map(dispatcher::dispatch)
                if (results.isNotEmpty()) {
                    vm.deliverHostResults(results)
                }
                kotlinx.coroutines.delay(10)
            }
            System.nanoTime() - started
        }

    @Test
    fun bundledTerminalWorkloadProducesProfilingMetrics() {
        val root = createTempDirectory("compukterkraft-display-profiling")
        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val metrics = RecordingDisplayMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                    displayMetricsCollector = metrics,
                )
            val dispatcher = HostCallDispatcher(deviceId = 1, workspace = workspace)

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val bootNanos = runTicks(vm, dispatcher, ticks = 80)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            val inputNanos = runTicks(vm, dispatcher, ticks = 20)

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            val outputNanos = runTicks(vm, dispatcher, ticks = 40)
            vm.drainDisplayFrames()

            val snapshot = metrics.snapshot()
            println(snapshot.summary())
            println("timing: bootNanos=$bootNanos, inputNanos=$inputNanos, outputNanos=$outputNanos")

            assertTrue(snapshot.operations.fillRectCalls > 0, snapshot.summary())
            assertTrue(snapshot.operations.presentCalls > 0, snapshot.summary())
            assertTrue(snapshot.frames.frameCount > 0, snapshot.summary())
            assertTrue(snapshot.frames.tileCount > 0, snapshot.summary())
            assertTrue(snapshot.frames.payloadBytes > 0, snapshot.summary())
            assertTrue(bootNanos > 0)
            assertTrue(inputNanos > 0)
            assertTrue(outputNanos > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
