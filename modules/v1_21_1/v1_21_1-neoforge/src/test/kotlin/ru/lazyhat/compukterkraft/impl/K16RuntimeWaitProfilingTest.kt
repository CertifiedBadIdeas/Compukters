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

package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16RuntimeWaitProfilingTest {
    @Test
    fun printsK16WaitRuntimeSummary() {
        val workspace = createTempDirectory("k16-runtime-wait-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 225,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "profiling"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
                serverThreadDispatcher = directServerThreadDispatcher,
                metricsCollector = metrics,
            )

        try {
            device.turnOn()
            tickUntilFirstWait(device, metrics)
            tickAndSync(device)
            waitUntil("K16 wait profiling should record a timer wakeup") {
                metrics.snapshot().vm.k16WaitTimerWakeups > 0
            }
            DeviceEvents.dispatch(device, KeyInputEvent.Character('x'.code.toByte()))
            sync(device)
            waitUntil("K16 wait profiling should record an input wakeup") {
                metrics.snapshot().vm.k16WaitInputWakeups > 0
            }

            val snapshot = metrics.snapshot()
            val summary = snapshot.summary()
            println(summary)

            assertTrue(snapshot.vm.k16RunSlices > 0)
            assertTrue(snapshot.vm.k16OutputRefreshes > 0)
            assertTrue(snapshot.vm.k16WaitEntries > 0)
            assertTrue(snapshot.k16.ram.loads > 0)
            assertTrue(snapshot.k16.mmio.loads > 0)
            assertTrue(snapshot.k16.devices.isNotEmpty())
            assertTrue(summary.contains("k16Execution: slices="))
            assertTrue(summary.contains("k16Output: refreshes="))
            assertTrue(summary.contains("k16Bus: ramLoads="))
            assertTrue(summary.contains("k16Devices: mapped="))
            assertTrue(summary.contains("k16Storage0: readCommands="))
            assertTrue(summary.contains("mediaReadBlocks="))
            assertTrue(summary.contains("k16Wait: entries="))
            assertTrue(summary.contains("timerWakeups="))
            assertTrue(summary.contains("inputWakeups="))
            assertTrue(summary.contains("idleSkips="))
        } finally {
            device.close()
        }
    }

    private fun tickUntilFirstWait(
        device: K16RuntimeDevice,
        metrics: RecordingRuntimeMetricsCollector,
    ) {
        repeat(80) {
            tickAndSync(device)
            if (metrics.snapshot().vm.k16WaitEntries > 0) return
        }
        error("K16 wait profiling workload did not reach WAIT in 80 server ticks")
    }

    private fun tickAndSync(device: K16RuntimeDevice) {
        device.serverTick()
        sync(device)
    }

    private fun sync(device: K16RuntimeDevice) {
        requireNotNull(device.snapshotRuntimeState()) {
            "K16 runtime should expose a snapshot while profiling"
        }
    }

    private fun waitUntil(
        message: String,
        predicate: () -> Boolean,
    ) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(10)
        }
        error(message)
    }
}
