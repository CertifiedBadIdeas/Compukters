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

package ru.lazyhat.compukterkraft.computer.vm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lazyhat.ck.lang.runtime.ComputerFileSystemApi
import ru.lazyhat.ck.lang.runtime.ComputerPeripheralApi
import ru.lazyhat.ck.lang.runtime.ComputerProfile
import ru.lazyhat.ck.lang.runtime.ComputerProgram
import ru.lazyhat.ck.lang.runtime.ComputerRedstoneApi
import ru.lazyhat.ck.lang.runtime.ComputerRuntime
import ru.lazyhat.ck.lang.runtime.ComputerSystemApi
import ru.lazyhat.ck.lang.runtime.ComputerTerminalApi
import ru.lazyhat.ck.lang.runtime.ComputerVmHandle
import ru.lazyhat.ck.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.ck.lang.runtime.HostCall
import ru.lazyhat.ck.lang.runtime.HostResult
import ru.lazyhat.ck.lang.runtime.VmEvent
import ru.lazyhat.ck.lang.runtime.VmSnapshot
import ru.lazyhat.ck.lang.runtime.VmState
import ru.lazyhat.ck.lang.runtime.VmStopReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.yield as coroutineYield

fun interface ComputerVmLogger {
    fun log(message: String)
}

interface ComputerVmCallbacks {
    fun currentLabel(): String?

    fun onVmStop(reason: VmStopReason)

    fun onVmRebootRequested()
}

class BackgroundComputerVm(
    override val computerId: Int,
    override val profile: ComputerProfile,
    dispatcher: CoroutineDispatcher,
    private val callbacks: ComputerVmCallbacks,
    private val logger: ComputerVmLogger,
) : ComputerVmHandle {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val eventQueue =
        Channel<VmEvent>(
            capacity = profile.maxEventQueueSize,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val slicePermits = Channel<Unit>(capacity = 1)
    private val hostCalls = ConcurrentLinkedQueue<HostCall>()
    private val hostResponses = ConcurrentHashMap<Long, CompletableDeferred<HostResult>>()
    private val nextHostCallId = AtomicLong()
    private val queuedEvents = AtomicInteger()
    private val stateLock = Mutex()

    @Volatile
    private var state: VmState = VmState.COLD

    @Volatile
    private var currentTick: Long = 0

    @Volatile
    private var stopReason: VmStopReason? = null

    @Volatile
    private var sleepUntilTick: Long? = null

    @Volatile
    private var errorMessage: String? = null

    @Volatile
    private var sliceDeadlineNanos: Long = 0

    private var runner: Job? = null
    private val runtime = RuntimeFacade()

    override fun start(program: ComputerProgram): Boolean {
        if (runner?.isActive == true) return false

        state = VmState.BOOTING
        stopReason = null
        errorMessage = null
        sleepUntilTick = null

        runner =
            scope.launch {
                try {
                    awaitSlicePermit()
                    logger.log("VM[$computerId] boot program started")
                    program.run(runtime)
                    stopInternal(VmStopReason.REQUESTED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    errorMessage = failure.message ?: failure.javaClass.simpleName
                    stopInternal(VmStopReason.CRASHED)
                }
            }

        return true
    }

    override fun stop(reason: VmStopReason) {
        scope.launch {
            stopInternal(reason)
        }
    }

    override fun enqueueEvent(event: VmEvent): Boolean =
        eventQueue.trySend(event).isSuccess.also { accepted ->
            if (accepted) {
                queuedEvents.incrementAndGet()
            }
        }

    override fun requestSlice(serverTick: Long) {
        currentTick = serverTick
        val wakeTick = sleepUntilTick
        if (wakeTick != null && serverTick < wakeTick) return
        slicePermits.trySend(Unit)
    }

    override fun drainHostCalls(): List<HostCall> =
        buildList {
            while (true) {
                val call = hostCalls.poll() ?: break
                add(call)
            }
        }

    override fun deliverHostResults(results: List<HostResult>) {
        for (result in results) {
            hostResponses.remove(result.id)?.complete(result)
        }
    }

    override fun snapshot(): VmSnapshot =
        VmSnapshot(
            computerId = computerId,
            profile = profile,
            state = state,
            currentTick = currentTick,
            queuedEvents = queuedEvents.get(),
            pendingHostCalls = hostCalls.size,
            stopReason = stopReason,
            errorMessage = errorMessage,
        )

    private suspend fun stopInternal(reason: VmStopReason) {
        stateLock.withLock {
            if (state == VmState.STOPPED || state == VmState.CRASHED) return

            stopReason = reason
            state = if (reason == VmStopReason.CRASHED) VmState.CRASHED else VmState.STOPPED
            runner?.cancel()
            runner = null
            sleepUntilTick = null

            if (reason == VmStopReason.REBOOT) {
                callbacks.onVmRebootRequested()
            } else {
                callbacks.onVmStop(reason)
            }
        }
    }

    private suspend fun awaitSlicePermit() {
        state =
            when {
                sleepUntilTick != null -> VmState.SLEEPING
                state == VmState.BOOTING -> VmState.BOOTING
                else -> VmState.RUNNING
            }
        slicePermits.receive()
        sliceDeadlineNanos = System.nanoTime() + profile.cpuBudgetNanosPerSlice
        state = VmState.RUNNING
    }

    private suspend fun applySchedulingPoint() {
        coroutineContext.ensureActive()
        if (System.nanoTime() >= sliceDeadlineNanos) {
            awaitSlicePermit()
        } else {
            coroutineYield()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T {
        val callId = nextHostCallId.incrementAndGet()
        val deferred = CompletableDeferred<HostResult>()
        hostResponses[callId] = deferred
        hostCalls.add(callFactory(callId))
        return when (val result = deferred.await()) {
            is HostResult.Success -> result.value as T
            is HostResult.Failure -> error(result.message)
        }
    }

    private inner class RuntimeFacade : ComputerRuntime {
        override val profile: ComputerProfile
            get() = this@BackgroundComputerVm.profile

        override val system: ComputerSystemApi = SystemApi()
        override val terminal: ComputerTerminalApi = TerminalApi()
        override val filesystem: ComputerFileSystemApi = FileSystemApi()
        override val redstone: ComputerRedstoneApi = object : ComputerRedstoneApi {}
        override val peripherals: ComputerPeripheralApi = object : ComputerPeripheralApi {}

        override suspend fun pullEvent(filter: String?): VmEvent {
            while (true) {
                state = VmState.WAITING_EVENT
                val event = eventQueue.receive()
                queuedEvents.decrementAndGet()
                if (filter == null || event.name == filter) {
                    state = VmState.RUNNING
                    applySchedulingPoint()
                    return event
                }
            }
        }

        override suspend fun sleep(ticks: Long) {
            val targetTick = currentTick + ticks.coerceAtLeast(1)
            sleepUntilTick = targetTick
            while (currentTick < targetTick) {
                awaitSlicePermit()
            }
            sleepUntilTick = null
        }

        override suspend fun yield() {
            applySchedulingPoint()
        }
    }

    private inner class SystemApi : ComputerSystemApi {
        override val computerId: Int
            get() = this@BackgroundComputerVm.computerId

        override val label: String?
            get() = callbacks.currentLabel()

        override val currentTick: Long
            get() = this@BackgroundComputerVm.currentTick

        override fun queueEvent(
            name: String,
            arguments: List<Any?>,
        ) {
            enqueueEvent(VmEvent(name, arguments))
        }

        override fun shutdown() {
            stop(VmStopReason.REQUESTED)
        }

        override fun reboot() {
            stop(VmStopReason.REBOOT)
        }

        override fun log(message: String) {
            logger.log("VM[$computerId] $message")
        }
    }

    private inner class TerminalApi : ComputerTerminalApi {
        override suspend fun write(text: String) {
            awaitHostCall<Unit> { HostCall.TerminalWrite(it, text) }
        }

        override suspend fun printLine(text: String) {
            awaitHostCall<Unit> { HostCall.TerminalWrite(it, text, newLine = true) }
        }

        override suspend fun clear() {
            awaitHostCall<Unit> { HostCall.TerminalClear(it) }
        }

        override suspend fun setCursor(
            x: Int,
            y: Int,
        ) {
            awaitHostCall<Unit> { HostCall.TerminalSetCursor(it, x, y) }
        }
    }

    private inner class FileSystemApi : ComputerFileSystemApi {
        override suspend fun exists(path: String): Boolean = awaitHostCall { HostCall.FileExists(it, path) }

        override suspend fun readText(path: String): String? = awaitHostCall { HostCall.FileReadText(it, path) }

        override suspend fun writeText(
            path: String,
            text: String,
        ) {
            awaitHostCall<Unit> { HostCall.FileWriteText(it, path, text) }
        }

        override suspend fun list(path: String): List<ComputerWorkspaceEntry> = awaitHostCall { HostCall.FileList(it, path) }
    }
}
