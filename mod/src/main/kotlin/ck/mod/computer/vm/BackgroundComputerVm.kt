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

package ck.mod.computer.vm

import ck.lang.frontend.FrontendSeverity
import ck.lang.runtime.BytecodeComputerProgram
import ck.lang.runtime.ComputerFileSystemApi
import ck.lang.runtime.ComputerPeripheralApi
import ck.lang.runtime.ComputerProcessApi
import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerProgram
import ck.lang.runtime.ComputerRedstoneApi
import ck.lang.runtime.ComputerRuntime
import ck.lang.runtime.ComputerSystemApi
import ck.lang.runtime.ComputerTerminalApi
import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.ComputerWorkspace
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.lang.runtime.HostCall
import ck.lang.runtime.HostResult
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmSnapshot
import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
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
import org.lwjgl.glfw.GLFW
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.yield as coroutineYield
import ck.mod.language.LanguageServices

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
    private val workspace: ComputerWorkspace,
    private val bundledScriptLoader: (String) -> String? = { null },
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

    private inner class RuntimeFacade(
        initialWorkingDirectory: String = "",
        initialArgument: String = "",
    ) : ComputerRuntime {
        private val deferredEvents = ArrayDeque<VmEvent>()
        var workingDirectory = normalizeWorkingDirectory(initialWorkingDirectory)
        var cursorX = 0
        var cursorY = 0

        override val profile: ComputerProfile
            get() = this@BackgroundComputerVm.profile

        override val system: ComputerSystemApi = SystemApi()
        override val terminal: ComputerTerminalApi = TerminalApi(this)
        override val filesystem: ComputerFileSystemApi = FileSystemApi(this)
        override val process: ComputerProcessApi = ProcessApi(this, initialArgument)
        override val redstone: ComputerRedstoneApi = object : ComputerRedstoneApi {}
        override val peripherals: ComputerPeripheralApi = object : ComputerPeripheralApi {}

        override suspend fun pullEvent(filter: String?): VmEvent {
            while (true) {
                state = VmState.WAITING_EVENT
                val event = receiveEvent()
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

        suspend fun receiveEvent(): VmEvent {
            val queued = deferredEvents.removeFirstOrNull()
            if (queued != null) {
                state = VmState.RUNNING
                applySchedulingPoint()
                return queued
            }
            val event = eventQueue.receive()
            queuedEvents.decrementAndGet()
            return event
        }

        fun deferEvent(event: VmEvent) {
            deferredEvents.addLast(event)
        }

        fun resolvePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == ".") return workingDirectory

            val segments = ArrayDeque<String>()
            val source =
                if (trimmed.startsWith('/')) {
                    trimmed.removePrefix("/")
                } else {
                    listOf(workingDirectory, trimmed).filter { it.isNotEmpty() }.joinToString("/")
                }
            source
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach { segment ->
                    when (segment) {
                        "." -> Unit
                        ".." -> segments.removeLastOrNull()
                        else -> segments.addLast(segment)
                    }
                }
            return segments.joinToString("/")
        }

        private fun normalizeWorkingDirectory(path: String): String = resolveWorkingDirectory(path)

        fun updateWorkingDirectory(path: String) {
            workingDirectory = normalizeWorkingDirectory(path)
        }

        fun advanceCursor(text: String) {
            cursorX = (cursorX + text.length).coerceAtLeast(0)
        }

        fun nextLine() {
            cursorX = 0
            cursorY = (cursorY + 1).coerceAtMost(profile.terminalHeight - 1)
        }

        fun resetCursor() {
            cursorX = 0
            cursorY = 0
        }

        fun currentWorkingDirectory(): String = workingDirectory
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

    private inner class TerminalApi(
        private val owner: RuntimeFacade,
    ) : ComputerTerminalApi {
        override suspend fun write(text: String) {
            awaitHostCall<Unit> { HostCall.TerminalWrite(it, text) }
            owner.advanceCursor(text)
        }

        override suspend fun printLine(text: String) {
            awaitHostCall<Unit> { HostCall.TerminalWrite(it, text, newLine = true) }
            owner.nextLine()
        }

        override suspend fun readLine(prompt: String): String {
            if (prompt.isNotEmpty()) {
                write(prompt)
            }

            val line = StringBuilder()
            val deferredEvents = ArrayDeque<VmEvent>()
            try {
                while (true) {
                    state = VmState.WAITING_EVENT
                    val event = owner.receiveEvent()
                    state = VmState.RUNNING
                    when (event.name) {
                        "char" -> {
                            decodeTypedText(event)?.let { chunk ->
                                line.append(chunk)
                                write(chunk)
                            }
                        }

                        "paste" -> {
                            decodePastedText(event)?.let { chunk ->
                                line.append(chunk)
                                write(chunk)
                            }
                        }

                        "key" -> {
                            val keyCode = (event.arguments.firstOrNull() as? Int) ?: continue
                            when (keyCode) {
                                GLFW.GLFW_KEY_ENTER,
                                GLFW.GLFW_KEY_KP_ENTER,
                                -> {
                                    printLine("")
                                    return line.toString()
                                }

                                GLFW.GLFW_KEY_BACKSPACE -> {
                                    if (line.isNotEmpty()) {
                                        line.deleteCharAt(line.lastIndex)
                                        owner.cursorX = (owner.cursorX - 1).coerceAtLeast(0)
                                        setCursor(owner.cursorX, owner.cursorY)
                                        write(" ")
                                        owner.cursorX = (owner.cursorX - 1).coerceAtLeast(0)
                                        setCursor(owner.cursorX, owner.cursorY)
                                    }
                                }
                            }
                        }

                        else -> deferredEvents.addLast(event)
                    }
                }
            } finally {
                while (deferredEvents.isNotEmpty()) {
                    owner.deferEvent(deferredEvents.removeFirst())
                }
            }
        }

        override suspend fun clear() {
            awaitHostCall<Unit> { HostCall.TerminalClear(it) }
            owner.resetCursor()
        }

        override suspend fun setCursor(
            x: Int,
            y: Int,
        ) {
            awaitHostCall<Unit> { HostCall.TerminalSetCursor(it, x, y) }
            owner.cursorX = x
            owner.cursorY = y
        }
    }

    private inner class FileSystemApi(
        private val owner: RuntimeFacade,
    ) : ComputerFileSystemApi {
        override suspend fun exists(path: String): Boolean = awaitHostCall { HostCall.FileExists(it, owner.resolvePath(path)) }

        override suspend fun isDirectory(path: String): Boolean = awaitHostCall { HostCall.FileIsDirectory(it, owner.resolvePath(path)) }

        override suspend fun readText(path: String): String? = awaitHostCall { HostCall.FileReadText(it, owner.resolvePath(path)) }

        override suspend fun writeText(
            path: String,
            text: String,
        ) {
            awaitHostCall<Unit> { HostCall.FileWriteText(it, owner.resolvePath(path), text) }
        }

        override suspend fun makeDirectory(path: String): Boolean =
            awaitHostCall { HostCall.FileMakeDirectory(it, owner.resolvePath(path)) }

        override suspend fun remove(path: String): Boolean = awaitHostCall { HostCall.FileRemove(it, owner.resolvePath(path)) }

        override suspend fun list(path: String): List<ComputerWorkspaceEntry> =
            awaitHostCall { HostCall.FileList(it, owner.resolvePath(path)) }
    }

    private inner class ProcessApi(
        private val owner: RuntimeFacade,
        override val argument: String,
    ) : ComputerProcessApi {
        override val workingDirectory: String
            get() = owner.currentWorkingDirectory()

        override suspend fun changeDirectory(path: String): Boolean {
            val resolved = owner.resolvePath(path)
            if (!owner.filesystem.isDirectory(resolved)) return false
            owner.updateWorkingDirectory(resolved)
            return true
        }

        override suspend fun run(
            path: String,
            argument: String,
        ): Int {
            val resolved = owner.resolvePath(path)
            val source = loadProgramSource(resolved) ?: return 1
            val artifact = LanguageServices.frontend.compile(resolved, source)
            val module = artifact.module
            if (module == null || artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR }) {
                val message = artifact.analysis.diagnostics.joinToString { it.message }
                owner.terminal.printLine("Compilation Error: $message")
                return 1
            }

            return try {
                BytecodeComputerProgram(module).run(RuntimeFacade(owner.currentWorkingDirectory(), argument))
                0
            } catch (failure: Throwable) {
                owner.terminal.printLine("Program error: ${failure.message ?: failure.javaClass.simpleName}")
                1
            }
        }

        private fun loadProgramSource(path: String): String? =
            workspace.readDocument(computerId, path)?.text
                ?: bundledScriptLoader(path)
                ?: bundledScriptLoader(path.removePrefix("/"))
                ?: run {
                    logger.log("VM[$computerId] missing program: $path")
                    null
                }
    }

    private fun resolveWorkingDirectory(path: String): String =
        path
            .trim()
            .trim('/')

    private fun decodeTypedText(event: VmEvent): String? {
        val bytes = event.arguments.firstOrNull() as? ByteArray ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun decodePastedText(event: VmEvent): String? {
        val buffer = event.arguments.firstOrNull() as? ByteBuffer ?: return null
        val copy = buffer.asReadOnlyBuffer()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }
}
