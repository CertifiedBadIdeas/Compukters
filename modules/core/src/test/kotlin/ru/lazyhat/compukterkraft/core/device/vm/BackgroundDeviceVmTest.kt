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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeTestWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonBootSummary
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonHostRequest
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeDeviceDaemonTickSummary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundDeviceVmTest {
    private class StaticFirmwareLoader(
        private val source: String,
    ) : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource = LoadedFirmwareProgramSource(path, source)
    }

    private class RecordingNativeDaemonBindings : NativeDaemonBindings {
        val createdDaemons = mutableListOf<Triple<Int, Int, Int>>()
        val freedDaemons = mutableListOf<Long>()
        val bootedImages = mutableListOf<ByteArray>()
        val tickServerTicks = mutableListOf<Long>()
        val tickInstructions = mutableListOf<Long>()
        val tickWallNanos = mutableListOf<Long>()
        val completedRequestIds = mutableListOf<Long>()
        val enqueuedEvents = mutableListOf<Pair<String, List<Any?>>>()
        val attachedDisplays = mutableListOf<Triple<Int, Int, Int>>()
        val detachedDisplays = mutableListOf<Int>()
        val displayFramePayloads = ArrayDeque<ByteArray>()
        val displayWakeWaits = mutableListOf<Pair<Long, Long>>()
        var displayWakeSequence: Long = 0
        var displayWakeWaitResult: Long = 0
        var tickSummary: NativeDeviceDaemonTickSummary? = null

        override fun createDeviceDaemon(
            maxEventQueueSize: Int,
            maxBufferedBytesPerChannel: Int,
            instructionBudget: Int,
        ): Long {
            createdDaemons += Triple(maxEventQueueSize, maxBufferedBytesPerChannel, instructionBudget)
            return 77
        }

        override fun freeDeviceDaemon(daemonHandle: Long) {
            freedDaemons += daemonHandle
        }

        override fun bootDeviceDaemon(
            daemonHandle: Long,
            image: ByteArray,
            programPath: String,
            argument: String,
            workingDirectory: String,
        ): NativeDeviceDaemonBootSummary {
            bootedImages += image
            return NativeDeviceDaemonBootSummary(pid = 1, imageAttached = true)
        }

        override fun tickDeviceDaemon(
            daemonHandle: Long,
            instructions: Long,
            wallNanos: Long,
            serverTick: Long,
        ): NativeDeviceDaemonTickSummary {
            tickServerTicks += serverTick
            tickInstructions += instructions
            tickWallNanos += wallNanos
            return tickSummary
                ?: NativeDeviceDaemonTickSummary(
                    serverTick = serverTick,
                    turns = 0,
                    remainingInstructions = instructions,
                    idle = true,
                    halted = 0,
                    hostRequests = 0,
                )
        }

        override fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest> = emptyList()

        override fun completeDeviceDaemonHostRequest(
            daemonHandle: Long,
            requestId: Long,
            value: ByteArray,
        ): Boolean {
            completedRequestIds += requestId
            return true
        }

        override fun enqueueDeviceDaemonEvent(
            daemonHandle: Long,
            eventName: String,
            arguments: List<Any?>,
        ): Boolean {
            enqueuedEvents += eventName to arguments
            return true
        }

        override fun attachDeviceDaemonDisplay(
            daemonHandle: Long,
            displayId: Int,
            width: Int,
            height: Int,
        ) {
            attachedDisplays += Triple(displayId, width, height)
        }

        override fun detachDeviceDaemonDisplay(
            daemonHandle: Long,
            displayId: Int,
        ) {
            detachedDisplays += displayId
        }

        override fun drainDeviceDaemonDisplayFrames(daemonHandle: Long): ByteArray =
            displayFramePayloads.removeFirstOrNull()
                ?: ByteBuffer
                    .allocate(Int.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0)
                    .array()

        override fun deviceDaemonDisplayWakeSequence(daemonHandle: Long): Long = displayWakeSequence

        override fun waitForDeviceDaemonDisplayWake(
            daemonHandle: Long,
            observedWakeSequence: Long,
            timeoutMillis: Long,
        ): Long {
            displayWakeWaits += observedWakeSequence to timeoutMillis
            return displayWakeWaitResult
        }
    }

    private fun backgroundVmWithNativeDaemonBindings(
        workspace: DeviceWorkspace,
        daemonBindings: RecordingNativeDaemonBindings,
    ): BackgroundDeviceVm =
        BackgroundDeviceVm(
            deviceId = 1,
            profile = firmwareTestProfile(),
            dispatcher = Dispatchers.Default,
            labelProvider = { null },
            logger = DeviceVmLogger { },
            workspace = workspace,
            firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
            nativeDaemonBindings = daemonBindings,
        )

    private fun runVmTicks(
        vm: BackgroundDeviceVm,
        ticks: Int = 8,
    ) = runBlocking {
        repeat(ticks) { tick ->
            vm.requestSlice(tick.toLong())
            kotlinx.coroutines.delay(10)
        }
    }

    private fun runVmTicksWithHostCalls(
        vm: BackgroundDeviceVm,
        workspace: DeviceWorkspace,
        ticks: Int = 8,
    ) = runBlocking {
        val dispatcher = HostCallDispatcher(vm.deviceId, workspace)
        repeat(ticks) { tick ->
            vm.requestSlice(tick.toLong())
            repeat(8) {
                kotlinx.coroutines.delay(2)
                val calls = vm.drainHostCalls()
                if (calls.isNotEmpty()) {
                    vm.deliverHostResults(calls.map(dispatcher::dispatch))
                }
            }
        }
    }

    private fun firmwareTestProfile(): DeviceProfile = runtimeProfile()

    private fun nativeDisplayFramePayload(
        displayId: Int,
        sequence: Long,
        width: Int,
        height: Int,
        fullRefresh: Boolean,
    ): ByteArray =
        ByteBuffer
            .allocate(Int.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES + 1 + 1 + Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putInt(displayId)
            .putLong(sequence)
            .putInt(width)
            .putInt(height)
            .put(0)
            .put(if (fullRefresh) 1 else 0)
            .putInt(0)
            .array()

    @Test
    fun nativeDaemonRuntimeBootsCompiledBootImageAndTicksDaemon() =
        runBlocking {
            val bindings = RecordingNativeDaemonBindings()
            bindings.tickSummary =
                NativeDeviceDaemonTickSummary(
                    serverTick = 42,
                    turns = 2,
                    remainingInstructions = 12,
                    idle = false,
                    halted = 1,
                    hostRequests = 3,
                )
            val runtimeMetricsCollector = RecordingRuntimeMetricsCollector()
            val profile =
                firmwareTestProfile().copy(
                    resources =
                        firmwareTestProfile().resources.copy(
                            cpu =
                                DeviceCpuResources(
                                    instructionsPerSlice = 123,
                                    wallTimeGuardNanosPerSlice = 456,
                                ),
                        ),
                )
            val runtime =
                NativeDeviceDaemonRuntime(
                    daemonHandle = 7,
                    profile = profile,
                    bindings = bindings,
                    runtimeMetricsCollector = runtimeMetricsCollector,
                    hostBridge = { byteArrayOf(0) },
                )

            runtime.boot(
                image = byteArrayOf(1, 2, 3),
                programPath = "/rom/bios.ck",
                argument = "",
                workingDirectory = "",
            )
            runtime.requestSlice(serverTick = 42)

            assertEquals(listOf(42L), bindings.tickServerTicks)
            assertEquals(listOf(123L), bindings.tickInstructions)
            assertEquals(listOf(456L), bindings.tickWallNanos)
            assertTrue(bindings.bootedImages.isNotEmpty())
            runtimeMetricsCollector.snapshot().vm.run {
                assertEquals(1, nativeDaemonTicks)
                assertTrue(nativeDaemonActiveNanos >= 0)
                assertEquals(0, nativeDaemonIdleTicks)
                assertEquals(2, nativeDaemonTurns)
                assertEquals(1, nativeDaemonHaltedProcesses)
                assertEquals(3, nativeDaemonHostRequests)
            }
        }

    @Test
    fun bootUsesNativeDaemonWhenConfigured() {
        runtimeTestWorkspace("vm-native-daemon-boot") { workspace ->
            System.setProperty("ckl.vm.native.daemon", "true")
            try {
                val daemonBindings = RecordingNativeDaemonBindings()
                val vm = backgroundVmWithNativeDaemonBindings(workspace.host, daemonBindings)

                assertTrue(vm.boot())
                vm.requestSlice(serverTick = 1)

                assertTrue(daemonBindings.bootedImages.isNotEmpty())
                assertTrue(daemonBindings.tickServerTicks.isNotEmpty())
            } finally {
                System.clearProperty("ckl.vm.native.daemon")
            }
        }
    }

    @Test
    fun enqueueEventForwardsAcceptedEventsToNativeDaemon() {
        runtimeTestWorkspace("vm-native-daemon-event-ingress") { workspace ->
            System.setProperty("ckl.vm.native.daemon", "true")
            try {
                val daemonBindings = RecordingNativeDaemonBindings()
                val vm = backgroundVmWithNativeDaemonBindings(workspace.host, daemonBindings)

                assertTrue(vm.enqueueEvent(VmEvent("char", listOf("x"))))

                assertEquals(listOf("char" to listOf<Any?>("x")), daemonBindings.enqueuedEvents)
            } finally {
                System.clearProperty("ckl.vm.native.daemon")
            }
        }
    }

    @Test
    fun nativeDaemonDisplayFramesAreMirroredAndDrained() {
        runtimeTestWorkspace("vm-native-daemon-display") { workspace ->
            System.setProperty("ckl.vm.native.daemon", "true")
            try {
                val daemonBindings = RecordingNativeDaemonBindings()
                daemonBindings.displayFramePayloads +=
                    nativeDisplayFramePayload(
                        displayId = 9,
                        sequence = 1,
                        width = 16,
                        height = 12,
                        fullRefresh = true,
                    )
                val vm = backgroundVmWithNativeDaemonBindings(workspace.host, daemonBindings)

                val info = vm.attachDisplay(displayId = 9, width = 16, height = 12)
                val frames = vm.drainDisplayFrames()
                vm.detachDisplay(displayId = 9)

                assertEquals(9, info.displayId)
                assertEquals(listOf(Triple(9, 16, 12)), daemonBindings.attachedDisplays)
                assertEquals(listOf(9), daemonBindings.detachedDisplays)
                assertEquals(1, frames.size)
                assertEquals(9, frames.single().displayId)
                assertTrue(frames.single().fullRefresh)
            } finally {
                System.clearProperty("ckl.vm.native.daemon")
            }
        }
    }

    @Test
    fun nativeDaemonDisplayWakePumpIsSupportedAndDelegatesWakeCalls() {
        runtimeTestWorkspace("vm-native-daemon-display-wake") { workspace ->
            System.setProperty("ckl.vm.native.daemon", "true")
            try {
                val daemonBindings = RecordingNativeDaemonBindings()
                daemonBindings.displayWakeSequence = 7
                daemonBindings.displayWakeWaitResult = 9
                val vm = backgroundVmWithNativeDaemonBindings(workspace.host, daemonBindings)

                vm.attachDisplay(displayId = 4, width = 16, height = 12)

                assertTrue(vm.supportsNativeDisplayFramePump())
                assertEquals(7, vm.nativeDisplayWakeSequence())
                assertEquals(9, vm.waitForNativeDisplayWake(observedWakeSequence = 7, timeoutMillis = 25))
                assertEquals(listOf(7L to 25L), daemonBindings.displayWakeWaits)
            } finally {
                System.clearProperty("ckl.vm.native.daemon")
            }
        }
    }

    @Test
    fun recordsRuntimeSchedulingMetrics() {
        runtimeTestWorkspace("vm-runtime-profiling") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                var count: Int = 0;
                                while count < 3 {
                                    count = count + 1;
                                    sleep(1L);
                                }
                            }
                            """.trimIndent(),
                        ),
                    runtimeMetricsCollector = metrics,
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 12)

            val snapshot = metrics.snapshot()
            assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsSent > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsReceived > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindows > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindowNanos > 0, snapshot.summary())
        }
    }

    @Test
    fun requestSliceDoesNotGateSharedQuotaOnSleepState() {
        runtimeTestWorkspace("vm-runtime-sleep-shared-quota") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    runtimeMetricsCollector = metrics,
                )

            vm.setSleepUntil(10)
            vm.requestSlice(serverTick = 1)

            val snapshot = metrics.snapshot()
            assertEquals(1, snapshot.vm.sliceRequests)
            assertEquals(1, snapshot.vm.slicePermitsSent)
            assertEquals(0, snapshot.vm.sleepGatedSliceRequests)
            assertEquals(1, snapshot.vm.executionQuotaRefills)
            assertEquals(1, snapshot.vm.executionQuotaAcceptedRefills)
            assertEquals(0, snapshot.vm.executionQuotaUnavailableRefills)
        }
    }

    @Test
    fun requestSliceMirrorsExecutionQuotaToNativeKernelWhenConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-quota-refill") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val profile =
                firmwareTestProfile().copy(
                    resources =
                        firmwareTestProfile().resources.copy(
                            cpu =
                                DeviceCpuResources(
                                    instructionsPerSlice = 321,
                                    wallTimeGuardNanosPerSlice = 654,
                                ),
                        ),
                )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    runtimeMetricsCollector = metrics,
                )

            vm.requestSlice(serverTick = 42)

            val snapshot = metrics.snapshot()
            assertEquals(1, snapshot.vm.nativeExecutionQuotaRefills)
            assertEquals(321, snapshot.vm.nativeExecutionQuotaInstructions)
            assertEquals(654, snapshot.vm.nativeExecutionQuotaWallNanos)
            assertEquals(42, snapshot.vm.nativeExecutionQuotaLastServerTick)
        }
    }

    @Test
    fun requestSliceRecordsNativeSchedulerDryRunWhenConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-scheduler-dry-run") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val profile =
                firmwareTestProfile().copy(
                    resources =
                        firmwareTestProfile().resources.copy(
                            cpu =
                                DeviceCpuResources(
                                    instructionsPerSlice = 3,
                                    wallTimeGuardNanosPerSlice = 654,
                                ),
                        ),
                )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    runtimeMetricsCollector = metrics,
                )

            vm.requestSlice(serverTick = 42)

            val snapshot = metrics.snapshot()
            assertEquals(1, snapshot.vm.nativeSchedulerDryRuns)
            assertEquals(3, snapshot.vm.nativeSchedulerDryRunTurns)
            assertEquals(3, snapshot.vm.nativeSchedulerDryRunSelectedPids)
            assertEquals(0, snapshot.vm.nativeSchedulerDryRunRemainingInstructions)
            assertEquals(1, snapshot.vm.nativeSchedulerDryRunFirstSelectionMatches)
            assertEquals(0, snapshot.vm.nativeSchedulerDryRunFirstSelectionMismatches)
        }
    }

    @Test
    fun ownsDisplayRegistryFrames() {
        runtimeTestWorkspace("vm-display-registry") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val displayId = display::primary();
                                display::fillRect(displayId, 1, 2, 3, 4, 63488);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                )

            val info = vm.attachDisplay(displayId = 9, width = 40, height = 20)

            assertEquals(9, info.displayId)
            assertEquals(40, info.width)
            assertEquals(20, info.height)
            val initialFrame = assertNotNull(vm.drainDisplayFrames().singleOrNull())
            assertEquals(9, initialFrame.displayId)
            assertTrue(vm.boot())
            runVmTicks(vm)
            val vmFrame = assertNotNull(vm.drainDisplayFrames().lastOrNull())
            assertEquals(9, vmFrame.displayId)
            assertEquals(40, vmFrame.width)
            assertEquals(20, vmFrame.height)
        }
    }

    @Test
    fun nativeDisplayPathDrainsAttachFullRefreshWhenEnabled() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-display-attach") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                            }
                            """.trimIndent(),
                        ),
                    nativeDisplayEnabled = true,
                )

            val info = vm.attachDisplay(displayId = 4, width = 18, height = 18)
            val frames = vm.drainDisplayFrames()

            assertEquals(4, info.displayId)
            assertTrue(frames.any { it.displayId == 4 && it.fullRefresh })
        }
    }

    @Test
    fun nativeDisplayPathDrainsProgramFrameWhenEnabled() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-display-program-frame") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val displayId = display::primary();
                                display::fillRect(displayId, 0, 0, 2, 2, 2016);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                    nativeDisplayEnabled = true,
                )

            vm.attachDisplay(displayId = 4, width = 18, height = 18)
            assertEquals(1, vm.drainDisplayFrames().size)
            assertTrue(vm.boot())
            runVmTicks(vm)
            val frames = vm.drainDisplayFrames()

            assertTrue(frames.any { it.displayId == 4 && it.sequence >= 2L && !it.fullRefresh })
        }
    }

    @Test
    fun displayAttachQueuesVmEvent() {
        runtimeTestWorkspace("vm-display-attach-event") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                events::pull("display_attach");
                                val displayId = display::primary();
                                display::fillRect(displayId, 0, 0, 1, 1, 63488);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 2)
            vm.attachDisplay(displayId = 9, width = 8, height = 8)
            runVmTicks(vm, ticks = 4)

            val frames = vm.drainDisplayFrames()
            assertEquals(listOf(1L, 2L), frames.map { it.sequence })
        }
    }

    @Test
    fun parentCanSpawnChildAndExchangeIpcText() {
        runtimeTestWorkspace("firmware-spawn-ipc-child") { workspace ->
            val logs = mutableListOf<String>()
            workspace.writeProgram(
                1,
                "boot.ck",
                """
                pub fun main() {
                    val inputText: String = strings::beforeSpace(process::argument())
                    val rest1: String = strings::afterSpace(process::argument())
                    val outputText: String = strings::beforeSpace(rest1)
                    val input: Int = strings::toInt(inputText)
                    val output: Int = strings::toInt(outputText)
                    ipc::write(output, ipc::read(input) + "child-")
                }
                """.trimIndent(),
            )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val childInput: Int = ipc::open()
                                val childOutput: Int = ipc::open()
                                val pid: Int = process::spawn("boot.ck", childInput + " " + childOutput + " 0")
                                ipc::write(childInput, "parent-")
                                val text: String = ipc::read(childOutput)
                                val code: Int = process::wait(pid)
                                system::log(text + "code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm, ticks = 40)

            assertTrue(logs.any { it.contains("parent-child-code=0") }, "state=${vm.snapshot().state} logs=$logs")
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun nativeProcessWaitReturnsChildExitCodeWhenLibraryIsConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("firmware-native-process-wait") { workspace ->
            val logs = mutableListOf<String>()
            workspace.writeProgram(
                1,
                "child.ck",
                """
                pub fun main() {
                    system::log("child-running")
                }
                """.trimIndent(),
            )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val pid: Int = process::spawn("child.ck", "")
                                val code: Int = process::wait(pid)
                                system::log("child-code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 40)

            assertTrue(logs.any { it.contains("child-running") }, logs.toString())
            assertTrue(logs.any { it.contains("child-code=0") }, logs.toString())
        }
    }

    @Test
    fun processWorkingDirectoryIsProcessLocal() {
        runtimeTestWorkspace("process-cwd-isolation") { workspace ->
            val logs = mutableListOf<String>()
            workspace.root.resolve("1").resolve("sub").createDirectories()
            workspace.writeProgram(
                1,
                "child-a.ck",
                """
                pub fun main() {
                    process::changeDirectory("sub")
                    system::log("a=" + process::currentDirectory())
                }
                """.trimIndent(),
            )
            workspace.writeProgram(
                1,
                "child-b.ck",
                """
                pub fun main() {
                    system::log("b=" + process::currentDirectory())
                }
                """.trimIndent(),
            )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                process::run("child-a.ck", "")
                                system::log("parent=" + process::currentDirectory())
                                process::run("child-b.ck", "")
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicksWithHostCalls(vm, workspace.host, ticks = 64)

            val debugState = "state=${vm.snapshot()} logs=$logs"
            assertTrue(logs.any { it.contains("a=sub") }, debugState)
            assertTrue(logs.any { it.contains("parent=") }, debugState)
            assertTrue(logs.none { it.contains("parent=sub") }, debugState)
            assertTrue(logs.any { it.contains("b=") }, debugState)
            assertTrue(logs.none { it.contains("b=sub") }, debugState)
        }
    }

    @Test
    fun processRunWritesLaunchErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-launch") { workspace ->
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("missing.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Program not found: missing.ck") }, logs.toString())
        }
    }

    @Test
    fun processRunWritesCompilationErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-compile") { workspace ->
            workspace.writeProgram(1, "bad.ck", "pub fun main() { val x: Int = \"bad\"; }")
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("bad.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Compilation Error in bad.ck") }, logs.toString())
        }
    }

    @Test
    fun firmwareReportsMissingBootFileAndStaysActive() {
        runtimeTestWorkspace("firmware-missing-boot") { workspace ->
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(logs.any { it.contains("Program not found: boot.ck") }, logs.toString())
            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareReportsBootCompileErrorAndStaysActive() {
        runtimeTestWorkspace("firmware-invalid-boot") { workspace ->
            workspace.writeProgram(1, "boot.ck", "fun main() {}")
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(logs.any { it.contains("Compilation Error in boot.ck") }, logs.toString())
            assertTrue(logs.any { it.contains("pub fun main") }, logs.toString())
            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareCanUseAmbientFilesystemModuleAndStayAlive() {
        runtimeTestWorkspace("compukterkraft-background-vm-success") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = runtimeProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                if (false) { filesystem::list() }
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "tiny-rom",
                    displayName = "Tiny ROM",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    allowedCapabilities = setOf(DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 1, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
                )

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    kotlinx.coroutines.yield()
                    vm.boot()
                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("ROM limit") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bootRejectsAmbientModuleWhenVmRegistryDoesNotExposeIt() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "terminal-only",
                    displayName = "Terminal Only",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    allowedCapabilities = setOf(DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 4096, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { if (false) { filesystem::list() } }"),
                )

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    kotlinx.coroutines.yield()
                    vm.boot()
                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("Unknown namespace `filesystem`") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
