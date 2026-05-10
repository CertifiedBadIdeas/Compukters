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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeTestWorkspace
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.DeviceEventApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIpcApi
import ru.lazyhat.compukterkraft.lang.runtime.DevicePeripheralApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProcessApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRedstoneApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.DeviceSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceEventApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceIpcApi
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmPollResult
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmProcessManagerTest {
    @Test
    fun vmProcessManagerConstructorDoesNotExposeNativeProcessBridgeOrStrictParityFlag() {
        val constructorParameterTypes =
            VmProcessManager::class.java.declaredConstructors
                .flatMap { it.parameterTypes.toList() }

        assertFalse(constructorParameterTypes.any { it.simpleName == "NativeProcessBridge" })
        assertFalse(constructorParameterTypes.any { it == Boolean::class.javaPrimitiveType })
    }

    private class StubVmContext : VmContext {
        val logs = mutableListOf<String>()

        override suspend fun receiveEvent(): VmEvent = error("not used")

        override fun tryReceiveEvent(): VmEvent? = null

        override fun deferEvent(event: VmEvent) = Unit

        override fun setState(state: VmState) = Unit

        override fun setSleepUntil(tick: Long?) = Unit

        override suspend fun schedulingPoint(processId: Int) = Unit

        override suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T =
            error("not used")

        override fun resolvePath(path: String): String = path

        override fun enqueueEvent(event: VmEvent): Boolean = true

        override fun stop(reason: VmStopReason) = Unit

        override fun log(message: String) {
            logs += message
        }

        override suspend fun writeIpc(
            channel: Int,
            text: String,
        ) = error("not used")

        override suspend fun pollIpcOrEvent(channel: Int): VmPollResult =
            error("not used")
    }

    private class BlockingEventRuntime(
        private val release: CompletableDeferred<VmEvent>,
        override val profile: DeviceProfile = runtimeProfile(),
    ) : DeviceRuntime {
        override val system: DeviceSystemApi =
            object : DeviceSystemApi {
                override val deviceId: Int = 1
                override val label: String? = null
                override val currentTick: Long = 0L

                override fun queueEvent(
                    name: String,
                    arguments: List<Any?>,
                ) = Unit

                override fun shutdown() = Unit

                override fun reboot() = Unit

                override fun log(message: String) = Unit
            }
        override val filesystem: DeviceFileSystemApi =
            object : DeviceFileSystemApi {
                override suspend fun exists(path: String): Boolean = false

                override suspend fun isDirectory(path: String): Boolean = false

                override suspend fun readText(path: String): String? = null

                override suspend fun writeText(
                    path: String,
                    text: String,
                ) = Unit

                override suspend fun makeDirectory(path: String): Boolean = false

                override suspend fun remove(path: String): Boolean = false

                override suspend fun list(path: String): List<DeviceWorkspaceEntry> = emptyList()
            }
        override val process: DeviceProcessApi =
            object : DeviceProcessApi {
                override val workingDirectory: String = ""
                override val argument: String = ""

                override suspend fun changeDirectory(path: String): Boolean = false

                override suspend fun spawn(
                    path: String,
                    argument: String,
                ): Int = 0

                override suspend fun wait(pid: Int): Int = 0
            }
        override val ipc: DeviceIpcApi = NoopDeviceIpcApi
        override val events: DeviceEventApi = NoopDeviceEventApi
        override val redstone: DeviceRedstoneApi = object : DeviceRedstoneApi {}
        override val peripherals: DevicePeripheralApi = object : DevicePeripheralApi {}

        override suspend fun pullEvent(filter: String?): VmEvent = release.await()

        override suspend fun sleep(ticks: Long) = Unit

        override suspend fun yield() = Unit
    }

    @Test
    fun spawnMissingProgramReportsErrorAndExitCode() {
        runtimeTestWorkspace("vm-process-manager-missing-program") { workspace ->
            val ctx = StubVmContext()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> error("runtimeCreator should not run for a missing program") },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                )

            try {
                val pid = manager.spawn("missing.ck", "", "")
                val code = runBlocking { withTimeout(5_000) { manager.wait(pid) } }

                assertEquals(2, pid)
                assertEquals(1, code)
                assertTrue(ctx.logs.any { it.contains("Program not found: missing.ck") }, ctx.logs.toString())
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }

    @Test
    fun spawnRecordsChildLifecycleInProcessTable() {
        runtimeTestWorkspace("vm-process-manager-process-table") { workspace ->
            val ctx = StubVmContext()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> error("runtimeCreator should not run for a missing program") },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                )

            try {
                val pid = manager.spawn("missing.ck", "arg", "bin", parentPid = 41)
                val code = runBlocking { withTimeout(5_000) { manager.wait(pid) } }

                val record = manager.processSnapshot(pid)
                assertEquals(1, code)
                assertEquals(pid, record?.pid)
                assertEquals(41, record?.parentPid)
                assertEquals("missing.ck", record?.programPath)
                assertEquals("arg", record?.argument)
                assertEquals("bin", record?.workingDirectory)
                assertEquals(VmProcessState.Exited(1), record?.state)
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }

    @Test
    fun waitMarksParentAsWaitingProcessUntilChildExits() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-process-manager-parent-wait-state") { workspace ->
            workspace.writeProgram(
                1,
                "child.ck",
                """
                pub fun main() {
                    events::pull("release")
                }
                """.trimIndent(),
            )
            val ctx = StubVmContext()
            val release = CompletableDeferred<VmEvent>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> BlockingEventRuntime(release) },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                )

            try {
                val pid = manager.spawn("child.ck", "", "", parentPid = 1)
                runBlocking {
                    val waiter = async { manager.wait(pid, waiterPid = 1) }
                    withTimeout(5_000) {
                        while (manager.processSnapshot(1)?.state != VmProcessState.WaitingProcess(pid)) {
                            delay(10)
                        }
                    }

                    assertEquals(VmProcessState.WaitingProcess(pid), manager.processSnapshot(1)?.state)
                    release.complete(VmEvent("release"))
                    assertEquals(0, withTimeout(5_000) { waiter.await() })
                }

                assertEquals(VmProcessState.Runnable, manager.processSnapshot(1)?.state)
                assertEquals(VmProcessState.Exited(0), manager.processSnapshot(pid)?.state)
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }

    @Test
    fun childCompletionWakesParentProcessWaiter() {
        runtimeTestWorkspace("vm-process-manager-process-waiter-wakeup") { workspace ->
            workspace.writeProgram(
                1,
                "child.ck",
                """
                pub fun main() {
                }
                """.trimIndent(),
            )
            val ctx = StubVmContext()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> BlockingEventRuntime(CompletableDeferred(VmEvent("unused"))) },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                )

            try {
                val pid = manager.spawn("child.ck", "", "", parentPid = 1)
                manager.markWaitingProcess(pid = 1, targetPid = pid)

                runBlocking { withTimeout(5_000) { manager.wait(pid) } }

                assertEquals(VmProcessState.Runnable, manager.processSnapshot(1)?.state)
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }

    @Test
    fun schedulerTickWakesSleepingRootAndSelectsRunnableProcess() {
        runtimeTestWorkspace("vm-process-manager-scheduler-tick") { workspace ->
            val ctx = StubVmContext()
            val metrics = RecordingRuntimeMetricsCollector()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> error("runtimeCreator should not run") },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                    runtimeMetricsCollector = metrics,
                )

            try {
                manager.markSleeping(pid = 1, untilTick = 5)

                assertEquals(VmProcessSchedulerTick(currentTick = 4, wokenPids = emptyList(), selectedPid = null), manager.schedulerTick(4))
                assertEquals(VmProcessSchedulerTick(currentTick = 5, wokenPids = listOf(1), selectedPid = 1), manager.schedulerTick(5))
                assertEquals(VmProcessState.Runnable, manager.processSnapshot(1)?.state)
                assertEquals(2, metrics.snapshot().vm.processSchedulerTicks)
                assertEquals(1, metrics.snapshot().vm.processSchedulerSelectedTicks)
                assertEquals(1, metrics.snapshot().vm.processSchedulerIdleTicks)
                assertEquals(1, metrics.snapshot().vm.processSchedulerWokenProcesses)
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }

    @Test
    fun crashedChildKeepsCrashedProcessState() {
        runtimeTestWorkspace("vm-process-manager-crashed-state") { workspace ->
            workspace.writeProgram(
                1,
                "crash.ck",
                """
                pub fun main() {
                }
                """.trimIndent(),
            )
            val ctx = StubVmContext()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                VmProcessManager(
                    scope = scope,
                    ctx = ctx,
                    deviceId = 1,
                    programLoader = WorkspaceProgramLoader(workspace.host),
                    profile = runtimeProfile(),
                    runtimeCreator = { _, _, _, _ -> error("runtime exploded") },
                    compilerMetricsCollector = NoOpCompilerMetricsCollector,
                )

            try {
                val pid = manager.spawn("crash.ck", "", "", parentPid = 1)
                val code = runBlocking { withTimeout(5_000) { manager.wait(pid) } }

                assertEquals(1, code)
                assertEquals(VmProcessState.Crashed("Program error in crash.ck: runtime exploded"), manager.processSnapshot(pid)?.state)
            } finally {
                runBlocking { manager.cancelAll() }
                scope.cancel()
            }
        }
    }
}
