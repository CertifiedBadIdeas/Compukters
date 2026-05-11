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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProcessApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmPollResult
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import kotlin.test.Test
import kotlin.test.assertEquals

class VmRuntimeProcessStateTest {
    @Test
    fun pullEventMarksProcessWaitingUntilMatchingEventArrives() =
        runBlocking {
            val table = registeredTable()
            val ctx = BlockingRuntimeContext()
            val runtime = runtime(ctx = ctx, table = table)

            val pulled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.pullEvent("key")
                }

            waitForState(table, VmProcessState.WaitingEvent("key"))

            ctx.nextEvent.complete(VmEvent("key", listOf("a")))

            assertEquals(VmEvent("key", listOf("a")), withTimeout(1_000) { pulled.await() })
            assertEquals(VmProcessState.Runnable, table.snapshot(ProcessId)?.state)
        }

    @Test
    fun sleepMarksProcessSleepingUntilTargetTickArrives() =
        runBlocking {
            val table = registeredTable()
            val ctx = BlockingRuntimeContext()
            val system = MutableTickSystemApi(currentTick = 10)
            val runtime = runtime(ctx = ctx, table = table, system = system)

            val sleeping =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.sleep(3)
                }

            waitForState(table, VmProcessState.Sleeping(13))

            system.currentTick = 13

            withTimeout(1_000) { sleeping.await() }
            assertEquals(VmProcessState.Runnable, table.snapshot(ProcessId)?.state)
        }

    @Test
    fun pollMarksProcessWaitingForIpcUntilWakeup() =
        runBlocking {
            val table = registeredTable()
            val ctx = BlockingRuntimeContext()
            val runtime = runtime(ctx = ctx, table = table)

            val polled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.poll(channelId = 9)
                }

            waitForState(table, VmProcessState.WaitingIpc(9))

            ctx.nextPoll.complete(VmPollResult(kind = "ipc", text = "ok"))

            assertEquals(VmPollResult(kind = "ipc", text = "ok"), withTimeout(1_000) { polled.await() })
            assertEquals(VmProcessState.Runnable, table.snapshot(ProcessId)?.state)
        }

    @Test
    fun yieldPassesProcessIdToSchedulingPoint() =
        runBlocking {
            val table = registeredTable()
            val ctx = BlockingRuntimeContext()
            val runtime = runtime(ctx = ctx, table = table)

            runtime.yield()

            assertEquals(listOf(ProcessId), ctx.schedulingProcessIds)
        }

    private fun registeredTable(): VmProcessTable =
        VmProcessTable().also {
            it.registerProcess(
                pid = ProcessId,
                parentPid = 1,
                programPath = "test.ck",
                argument = "",
                workingDirectory = "",
            )
        }

    private fun runtime(
        ctx: BlockingRuntimeContext,
        table: VmProcessTable,
        system: MutableTickSystemApi = MutableTickSystemApi(),
    ): VmRuntime =
        VmRuntime(
            ctx = ctx,
            initialProfile = runtimeProfile(),
            processId = ProcessId,
            parentProcessId = 1,
            runtimeRegistry = BuiltinRegistry(modules = emptyList(), globals = emptyList(), builtinTypes = emptyList()),
            systemApi = system,
            filesystemApi = NoopFileSystemApi,
            processApi = NoopProcessApi,
            processStateReporter = table,
        )

    private suspend fun waitForState(
        table: VmProcessTable,
        state: VmProcessState,
    ) {
        withTimeout(1_000) {
            while (table.snapshot(ProcessId)?.state != state) {
                yield()
            }
        }
    }

    private class BlockingRuntimeContext : VmContext {
        val nextEvent = CompletableDeferred<VmEvent>()
        val nextPoll = CompletableDeferred<VmPollResult>()
        val schedulingProcessIds = mutableListOf<Int>()

        override suspend fun receiveEvent(): VmEvent = nextEvent.await()

        override fun tryReceiveEvent(): VmEvent? = null

        override fun deferEvent(event: VmEvent) = Unit

        override fun setState(state: VmState) = Unit

        override fun setSleepUntil(tick: Long?) = Unit

        override suspend fun schedulingPoint(processId: Int) {
            schedulingProcessIds += processId
            yield()
        }

        override fun resolvePath(path: String): String = path

        override fun enqueueEvent(event: VmEvent): Boolean = true

        override fun stop(reason: VmStopReason) = Unit

        override fun log(message: String) = Unit

        override suspend fun writeIpc(
            channel: Int,
            text: String,
        ) = Unit

        override suspend fun pollIpcOrEvent(channel: Int): VmPollResult = nextPoll.await()
    }

    private class MutableTickSystemApi(
        override var currentTick: Long = 0,
    ) : DeviceSystemApi {
        override val deviceId: Int = 1
        override val label: String? = null

        override fun queueEvent(
            name: String,
            arguments: List<Any?>,
        ) = Unit

        override fun shutdown() = Unit

        override fun reboot() = Unit

        override fun log(message: String) = Unit
    }

    private object NoopFileSystemApi : DeviceFileSystemApi {
        override suspend fun exists(path: String): Boolean = false

        override suspend fun isDirectory(path: String): Boolean = false

        override suspend fun readText(path: String): String? = null

        override suspend fun writeText(
            path: String,
            text: String,
        ) = Unit

        override suspend fun makeDirectory(path: String): Boolean = false

        override suspend fun remove(path: String): Boolean = false

        override suspend fun list(path: String) = emptyList<DeviceWorkspaceEntry>()
    }

    private object NoopProcessApi : DeviceProcessApi {
        override val workingDirectory: String = ""
        override val argument: String = ""

        override suspend fun changeDirectory(path: String): Boolean = false

        override suspend fun spawn(
            path: String,
            argument: String,
        ): Int = -1

        override suspend fun wait(pid: Int): Int = 1
    }

    private companion object {
        const val ProcessId = 17
    }
}
