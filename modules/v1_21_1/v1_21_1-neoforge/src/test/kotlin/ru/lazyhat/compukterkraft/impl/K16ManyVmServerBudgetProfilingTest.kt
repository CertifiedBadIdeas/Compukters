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
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingSnapshot
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16ManyVmServerBudgetProfilingTest {
    @Test
    fun printsK16ManyVmServerBudget() {
        val vmCount = intProperty("k16.profile.manyVmCount", 16)
        val idleTicks = intProperty("k16.profile.manyVmIdleTicks", 80)
        val bootTickLimit = intProperty("k16.profile.manyVmBootTickLimit", 120)
        val workspace = createTempDirectory("k16-many-vm-server-budget-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storageTemplate = K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader)
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val runtimes =
            List(vmCount) { index ->
                val storagePath = workspace.resolve("storage-$index.kv")
                storagePath.writeBytes(storageTemplate)
                val metrics = RecordingRuntimeMetricsCollector()
                val device =
                    K16RuntimeDevice(
                        deviceId = 10_000 + index,
                        properties = DeviceProperties(DeviceFamily.NORMAL, label = "many-vm-$index"),
                        endpointFactory = {
                            K16ComputerRuntimeFactory.createFromBiosFlash(
                                biosFlashPath = biosFlashPath,
                                storage0Path = storagePath,
                                maxSteps = profile.resources.cpu.maxStepsPerSlice,
                                maxTurnsPerTick = profile.resources.cpu.maxTurnsPerTick,
                            )
                        },
                        stateSink = {},
                        metricsCollector = metrics,
                    )
                ProfiledRuntime(device, metrics)
            }

        try {
            val bootStartedAt = System.nanoTime()
            runtimes.forEach { it.device.turnOn() }
            val splashBefore = aggregateSnapshots(runtimes)
            val splashNanos = timeTicks(runtimes, 1)
            val splashSyncNanos = timeSyncAll(runtimes)
            val splashAfter = aggregateSnapshots(runtimes)
            printManyVmDeltaLine(
                "k16ManyVmSplash",
                vmCount,
                ticks = 1,
                nanos = splashNanos,
                before = splashBefore,
                after = splashAfter,
                syncNanos = splashSyncNanos,
            )

            val splashWaitBefore = splashAfter
            val splashWaitNanos = timeTicks(runtimes, K16_BIOS_SPLASH_WAIT_PROFILE_TICKS)
            val splashWaitSyncNanos = timeSyncAll(runtimes)
            val splashWaitAfter = aggregateSnapshots(runtimes)
            printManyVmDeltaLine(
                "k16ManyVmSplashWait",
                vmCount,
                ticks = K16_BIOS_SPLASH_WAIT_PROFILE_TICKS,
                nanos = splashWaitNanos,
                before = splashWaitBefore,
                after = splashWaitAfter,
                syncNanos = splashWaitSyncNanos,
            )

            val bootAfterSplashStartedAt = System.nanoTime()
            val bootAfterSplashTicks = tickUntilAllShells(runtimes, bootTickLimit)
            val bootAfterSplashNanos = System.nanoTime() - bootAfterSplashStartedAt
            val bootAfterSplashSyncNanos = timeSyncAll(runtimes)
            val bootAfterSplashSnapshot = aggregateSnapshots(runtimes)
            printManyVmDeltaLine(
                "k16ManyVmBootAfterSplash",
                vmCount,
                ticks = bootAfterSplashTicks,
                nanos = bootAfterSplashNanos,
                before = splashWaitAfter,
                after = bootAfterSplashSnapshot,
                syncNanos = bootAfterSplashSyncNanos,
            )

            val bootNanos = System.nanoTime() - bootStartedAt
            val bootTicks = 1 + K16_BIOS_SPLASH_WAIT_PROFILE_TICKS + bootAfterSplashTicks
            printManyVmLine("k16ManyVmBoot", vmCount, bootTicks, bootNanos, bootAfterSplashSnapshot)

            val idleBefore = aggregateSnapshots(runtimes)
            val idleNanos = timeTicks(runtimes, idleTicks)
            val idleSyncNanos = timeSyncAll(runtimes)
            val idleAfter = aggregateSnapshots(runtimes)
            printManyVmDeltaLine(
                "k16ManyVmIdle",
                vmCount,
                ticks = idleTicks,
                nanos = idleNanos,
                before = idleBefore,
                after = idleAfter,
                syncNanos = idleSyncNanos,
            )

            val activeBefore = aggregateSnapshots(runtimes)
            val activeStartedAt = System.nanoTime()
            DeviceEvents.dispatch(runtimes.first().device, PasteInputEvent(ByteBuffer.wrap("ticks\n".encodeToByteArray())))
            val activeTicks = tickUntilTerminal(runtimes, limit = 80) { terminal ->
                val ticksIndex = terminal.indexOf("TICKS ")
                ticksIndex >= 0 && terminal.indexOf("K16> ", startIndex = ticksIndex) > ticksIndex
            }
            val activeNanos = System.nanoTime() - activeStartedAt
            val activeSyncNanos = timeSyncAll(runtimes)
            val activeAfter = aggregateSnapshots(runtimes)
            printManyVmDeltaLine(
                "k16ManyVmOneActive",
                vmCount,
                ticks = activeTicks,
                nanos = activeNanos,
                before = activeBefore,
                after = activeAfter,
                syncNanos = activeSyncNanos,
            )

            assertTrue(bootAfterSplashTicks < bootTickLimit, "many-VM profiling did not boot all VMs to shell after BIOS splash")
            assertTrue(activeTicks < 80, "many-VM profiling did not finish one active command")
        } finally {
            runtimes.forEach { it.device.close() }
        }
    }

    private fun tickUntilAllShells(
        runtimes: List<ProfiledRuntime>,
        limit: Int,
    ): Int {
        repeat(limit) { tick ->
            runtimes.forEach { it.device.serverTick() }
            if (runtimes.all { runtime -> runtime.device.snapshotRuntimeState()?.let(::terminalText)?.contains("K16> ") == true }) {
                return tick + 1
            }
            Thread.sleep(1)
        }
        return limit
    }

    private fun tickUntilTerminal(
        runtimes: List<ProfiledRuntime>,
        limit: Int,
        predicate: (String) -> Boolean,
    ): Int {
        repeat(limit) { tick ->
            runtimes.forEach { it.device.serverTick() }
            val terminal = runtimes.first().device.snapshotRuntimeState()?.let(::terminalText) ?: ""
            if (predicate(terminal)) {
                return tick + 1
            }
            Thread.sleep(1)
        }
        return limit
    }

    private fun timeTicks(
        runtimes: List<ProfiledRuntime>,
        ticks: Int,
    ): Long {
        val startedAt = System.nanoTime()
        repeat(ticks) {
            runtimes.forEach { it.device.serverTick() }
        }
        return System.nanoTime() - startedAt
    }

    private fun timeSyncAll(runtimes: List<ProfiledRuntime>): Long {
        val startedAt = System.nanoTime()
        runtimes.forEach { it.device.snapshotRuntimeState() }
        return System.nanoTime() - startedAt
    }

    private fun aggregateSnapshots(runtimes: List<ProfiledRuntime>): ManyVmSnapshot =
        runtimes
            .map { it.metrics.snapshot() }
            .fold(ManyVmSnapshot()) { acc, snapshot -> acc + snapshot }

    private fun printManyVmLine(
        label: String,
        vmCount: Int,
        ticks: Int,
        nanos: Long,
        snapshot: ManyVmSnapshot,
        syncNanos: Long? = null,
    ) {
        val syncSuffix = syncNanos?.let { ", sync=$it ns" } ?: ""
        println(
            "$label: vms=$vmCount, ticks=$ticks, wall=${nanos} ns$syncSuffix, " +
                "perVmTick=${perVmTick(nanos, vmCount, ticks)} ns, " +
                "slices=${snapshot.slices}, waits=${snapshot.waitSignals}, yields=${snapshot.yieldSignals}, " +
                "idleSkips=${snapshot.idleSkips}, storageReads=${snapshot.storageReads}, " +
                "storageBytes=${snapshot.storageBytes}, gpuFrames=${snapshot.gpuFrames}, gpuFrameBytes=${snapshot.gpuFrameBytes}",
        )
    }

    private fun printManyVmDeltaLine(
        label: String,
        vmCount: Int,
        ticks: Int,
        nanos: Long,
        before: ManyVmSnapshot,
        after: ManyVmSnapshot,
        syncNanos: Long? = null,
    ) {
        printManyVmLine(label, vmCount, ticks, nanos, after - before, syncNanos = syncNanos)
    }

    private fun perVmTick(
        nanos: Long,
        vmCount: Int,
        ticks: Int,
    ): Long = if (vmCount <= 0 || ticks <= 0) 0 else nanos / vmCount / ticks

    private fun intProperty(
        name: String,
        defaultValue: Int,
    ): Int = System.getProperty(name)?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
            .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }
}

private data class ProfiledRuntime(
    val device: K16RuntimeDevice,
    val metrics: RecordingRuntimeMetricsCollector,
)

private data class ManyVmSnapshot(
    val slices: Long = 0,
    val waitSignals: Long = 0,
    val yieldSignals: Long = 0,
    val idleSkips: Long = 0,
    val storageReads: Long = 0,
    val storageBytes: Long = 0,
    val gpuFrames: Long = 0,
    val gpuFrameBytes: Long = 0,
) {
    operator fun plus(snapshot: RuntimeProfilingSnapshot): ManyVmSnapshot =
        copy(
            slices = slices + snapshot.vm.k16RunSlices,
            waitSignals = waitSignals + snapshot.vm.k16RunWaitSignals,
            yieldSignals = yieldSignals + snapshot.vm.k16RunYieldSignals,
            idleSkips = idleSkips + snapshot.vm.k16WaitIdleSkips,
            storageReads = storageReads + snapshot.k16.storage0.readCommands,
            storageBytes = storageBytes + snapshot.k16.storage0.bytesRead,
            gpuFrames = gpuFrames + snapshot.k16.gpu.frames,
            gpuFrameBytes = gpuFrameBytes + snapshot.k16.gpu.framePayloadBytes,
        )

    operator fun minus(other: ManyVmSnapshot): ManyVmSnapshot =
        copy(
            slices = slices - other.slices,
            waitSignals = waitSignals - other.waitSignals,
            yieldSignals = yieldSignals - other.yieldSignals,
            idleSkips = idleSkips - other.idleSkips,
            storageReads = storageReads - other.storageReads,
            storageBytes = storageBytes - other.storageBytes,
            gpuFrames = gpuFrames - other.gpuFrames,
            gpuFrameBytes = gpuFrameBytes - other.gpuFrameBytes,
        )
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 22
private const val K16_BIOS_SPLASH_TICKS = 20
private const val K16_BIOS_SPLASH_WAIT_PROFILE_TICKS = K16_BIOS_SPLASH_TICKS - 1
