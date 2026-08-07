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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.runtime.ports.NoopDisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.runtime.ports.ServerThreadDispatcher
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerSignal
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRetainedDisplayPayload
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface RuntimeDeviceSerialEndpoint {
    fun pushSerialInput(bytes: ByteArray)

    fun serialOutputSnapshot(): ByteArray

    fun clearSerialOutput()
}

class K16RuntimeDevice(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val endpointFactory: () -> K16ComputerEndpoint,
    private val stateSink: DeviceStateSink,
    serverThreadDispatcher: ServerThreadDispatcher,
    private val displayNetwork: DisplayNetworkBridge = NoopDisplayNetworkBridge,
    private val metricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
) : RuntimeDevice,
    RuntimeDeviceSerialEndpoint,
    RuntimeDeviceSnapshotPersistence,
    RuntimeDeviceFailureState {
    override val family: DeviceFamily = properties.family

    private var endpoint: K16EndpointWorker? = null
    private val retainedDisplaySessions = RetainedDisplaySessionTracker()
    private var labelBacking: String? = properties.label
    private var terminalControlReached = false
    private var runtimeFailureMessageBacking: String? = null
    private val retainedDisplayPublicationPump =
        ServerThreadPublicationPump(
            serverThreadDispatcher,
            ::sendReadyRetainedDisplayPublication,
        )

    override var label: String?
        get() = labelBacking
        set(value) {
            labelBacking = value
        }

    override val isOn: Boolean
        get() = endpoint != null

    override val runtimeFailureMessage: String?
        get() = runtimeFailureMessageBacking

    override fun turnOn() {
        if (endpoint != null) return
        val worker =
            try {
                K16EndpointWorker(
                    deviceId,
                    endpointFactory,
                    metricsCollector,
                    ::offerReadyRetainedDisplayPublication,
                ).also { it.start() }
            } catch (error: Throwable) {
                recordRuntimeFailure(error, "start")
                stateSink.onPowerStateChanged(false)
                return
            }
        try {
            attachRetainedDisplayViewers(worker)
        } catch (error: Throwable) {
            runCatching { worker.close() }
                .onFailure { closeFailure -> error.addSuppressed(closeFailure) }
            recordRuntimeFailure(error, "attach retained display viewers during start")
            stateSink.onPowerStateChanged(false)
            return
        }
        endpoint = worker
        runtimeFailureMessageBacking = null
        terminalControlReached = false
        stateSink.onPowerStateChanged(true)
    }

    override fun shutdown() {
        val current = endpoint ?: return
        endpoint = null
        terminalControlReached = false
        for (session in retainedDisplaySessions.sessionsSnapshot()) {
            runCatching { current.detachRetainedDisplayViewer(session.viewerToken) }
                .onFailure { recordRuntimeFailure(it, "detach retained display viewer during shutdown") }
        }
        current.close()
        stateSink.onPowerStateChanged(false)
    }

    override fun reboot() {
        shutdown()
        turnOn()
    }

    override fun serverTick() {
        val current = endpoint ?: return
        pruneRetainedDisplayViewers(current)
        current.requestTick()
        terminalControlReached = current.terminalControlReached
    }

    override fun close() {
        shutdown()
        retainedDisplaySessions.clear()
    }

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        when (event) {
            "turn_on" -> {
                turnOn()
            }

            "shutdown", "terminate" -> {
                shutdown()
            }

            "reboot" -> {
                reboot()
            }

            "char" -> {
                pushKeyboardChar(argumentBytes(arguments.firstOrNull())?.firstOrNull() ?: return)
            }

            "paste" -> {
                pushKeyboardPasteBytes(argumentBytes(arguments.firstOrNull()) ?: return)
            }

            "key" -> {
                pushKeyboardKeyDown(
                    key = argumentInt(arguments.getOrNull(0)) ?: return,
                    repeat = argumentBoolean(arguments.getOrNull(1)) ?: false,
                    modifiers = 0,
                )
            }

            "key_up" -> {
                pushKeyboardKeyUp(
                    key = argumentInt(arguments.getOrNull(0)) ?: return,
                    modifiers = 0,
                )
            }
        }
    }

    override fun pushSerialInput(bytes: ByteArray) {
        endpoint?.pushInput(bytes)
    }

    override fun serialOutputSnapshot(): ByteArray = endpoint?.outputSnapshot() ?: ByteArray(0)

    override fun clearSerialOutput() {
        endpoint?.clearOutput()
    }

    private fun pushKeyboardKeyDown(
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    ) {
        endpoint?.pushKeyboardKeyDown(key, repeat, modifiers)
    }

    private fun pushKeyboardKeyUp(
        key: Int,
        modifiers: Int,
    ) {
        endpoint?.pushKeyboardKeyUp(key, modifiers)
    }

    private fun pushKeyboardChar(value: Byte) {
        endpoint?.pushKeyboardChar(value)
    }

    private fun pushKeyboardPasteBytes(bytes: ByteArray) {
        endpoint?.pushKeyboardPasteBytes(bytes)
    }

    override fun snapshotRuntimeState(): ByteArray? {
        val current = endpoint ?: return null
        return try {
            current.machineSnapshot()
        } catch (error: Throwable) {
            recordRuntimeFailure(error, "snapshot")
            null
        }
    }

    private fun recordRuntimeFailure(
        error: Throwable,
        action: String,
    ) {
        val cause = (error as? CompletionException)?.cause ?: error
        runtimeFailureMessageBacking = cause.message ?: cause::class.java.name
        LOGGER.error(cause) {
            "K16RuntimeDevice $deviceId failed to $action: $runtimeFailureMessageBacking"
        }
    }

    override fun attachRetainedDisplayViewer(playerUuid: UUID): Boolean {
        if (retainedDisplaySessions.authorize(playerUuid) != null) return true
        val session = retainedDisplaySessions.attach(playerUuid)
        val current = endpoint ?: return true
        return try {
            current.attachRetainedDisplayViewer(session.viewerToken, deviceId)
            true
        } catch (error: Throwable) {
            retainedDisplaySessions.detach(playerUuid)
            runCatching { current.detachRetainedDisplayViewer(session.viewerToken) }
            recordRuntimeFailure(error, "attach retained display viewer")
            false
        }
    }

    override fun acceptRetainedDisplayServerbound(
        playerUuid: UUID,
        payload: ByteArray,
    ): Boolean {
        val current = endpoint ?: return false
        val viewerToken = retainedDisplaySessions.authorize(playerUuid) ?: return false
        return try {
            val outcome = current.acceptRetainedDisplayServerbound(viewerToken, payload)
            if (outcome > 0) {
                true
            } else {
                false
            }
        } catch (error: Throwable) {
            recordRuntimeFailure(error, "accept retained display message")
            false
        }
    }

    override fun detachRetainedDisplayViewer(playerUuid: UUID): Boolean {
        val viewerToken = retainedDisplaySessions.detach(playerUuid) ?: return false
        val current = endpoint ?: return true
        return try {
            current.detachRetainedDisplayViewer(viewerToken)
        } catch (error: Throwable) {
            recordRuntimeFailure(error, "detach retained display viewer")
            false
        }
    }

    private fun argumentBytes(value: Any?): ByteArray? =
        when (value) {
            is ByteArray -> {
                value.copyOf()
            }

            is ByteBuffer -> {
                val duplicate = value.asReadOnlyBuffer()
                ByteArray(duplicate.remaining()).also(duplicate::get)
            }

            is String -> {
                value.encodeToByteArray()
            }

            else -> {
                null
            }
        }

    private fun argumentInt(value: Any?): Int? = value as? Int

    private fun argumentBoolean(value: Any?): Boolean? = value as? Boolean

    private fun offerReadyRetainedDisplayPublication(
        worker: K16EndpointWorker,
        publication: NativeRetainedDisplayPayload,
    ) {
        runCatching {
            retainedDisplayPublicationPump.offer(ReadyRetainedDisplayPublication(worker, publication))
        }.onFailure { error ->
            recordRuntimeFailure(error, "schedule retained display publication")
        }
    }

    private fun sendReadyRetainedDisplayPublication(ready: ReadyRetainedDisplayPublication) {
        if (endpoint !== ready.worker) return
        val publication = ready.publication
        val session = retainedDisplaySessions.sessionForToken(publication.viewerToken) ?: return
        if (!displayNetwork.isRetainedDisplayViewerAuthorized(session.playerUuid, deviceId)) return
        displayNetwork.sendRetainedDisplayPayload(session.playerUuid, deviceId, publication.payload)
        metricsCollector.recordK16RetainedPayloadSent(publication.payload.size)
    }

    private fun attachRetainedDisplayViewers(current: K16EndpointWorker) {
        for (session in retainedDisplaySessions.sessionsSnapshot()) {
            if (!displayNetwork.isRetainedDisplayViewerAuthorized(session.playerUuid, deviceId)) {
                retainedDisplaySessions.detach(session.playerUuid)
                continue
            }
            current.attachRetainedDisplayViewer(session.viewerToken, deviceId)
        }
    }

    private fun pruneRetainedDisplayViewers(current: K16EndpointWorker) {
        for (session in retainedDisplaySessions.sessionsSnapshot()) {
            if (!displayNetwork.isRetainedDisplayViewerAuthorized(session.playerUuid, deviceId)) {
                val viewerToken = retainedDisplaySessions.detach(session.playerUuid) ?: continue
                runCatching { current.detachRetainedDisplayViewer(viewerToken) }
                    .onFailure { recordRuntimeFailure(it, "prune retained display viewer") }
            }
        }
    }

    private data class ReadyRetainedDisplayPublication(
        val worker: K16EndpointWorker,
        val publication: NativeRetainedDisplayPayload,
    )

    private class K16EndpointWorker(
        private val deviceId: Int,
        private val endpointFactory: () -> K16ComputerEndpoint,
        private val metricsCollector: RuntimeMetricsCollector,
        private val onRetainedDisplayPayloadReady: (K16EndpointWorker, NativeRetainedDisplayPayload) -> Unit,
    ) : AutoCloseable {
        private val commands = LinkedBlockingQueue<Command>()
        private val startup = CompletableFuture<Unit>()
        private val closed = AtomicBoolean(false)
        private val tickRequested = AtomicBoolean(false)
        private val pendingGameTicks = AtomicLong()
        private val workerThread =
            Thread(::runWorker, "compukterkraft-k16-$deviceId").apply {
                isDaemon = true
            }

        @Volatile
        private var outputCache: ByteArray = ByteArray(0)

        @Volatile
        var terminalControlReached: Boolean = false
            private set

        fun start() {
            workerThread.start()
            try {
                startup.join()
            } catch (error: CompletionException) {
                throw error.cause ?: error
            }
        }

        fun requestTick() {
            if (!closed.get() && !terminalControlReached) {
                pendingGameTicks.incrementAndGet()
                if (tickRequested.compareAndSet(false, true)) {
                    commands.offer(Command.Tick)
                }
            }
        }

        fun pushInput(bytes: ByteArray) {
            if (!closed.get() && bytes.isNotEmpty()) {
                commands.offer(Command.PushInput(bytes.copyOf()))
            }
        }

        fun pushKeyboardKeyDown(
            key: Int,
            repeat: Boolean,
            modifiers: Int,
        ) {
            if (!closed.get()) {
                commands.offer(Command.PushKeyboardKeyDown(key, repeat, modifiers))
            }
        }

        fun pushKeyboardKeyUp(
            key: Int,
            modifiers: Int,
        ) {
            if (!closed.get()) {
                commands.offer(Command.PushKeyboardKeyUp(key, modifiers))
            }
        }

        fun pushKeyboardChar(value: Byte) {
            if (!closed.get()) {
                commands.offer(Command.PushKeyboardChar(value))
            }
        }

        fun pushKeyboardPasteBytes(bytes: ByteArray) {
            if (!closed.get() && bytes.isNotEmpty()) {
                commands.offer(Command.PushKeyboardPasteBytes(bytes.copyOf()))
            }
        }

        fun outputSnapshot(): ByteArray = outputCache.copyOf()

        fun clearOutput() {
            outputCache = ByteArray(0)
            if (!closed.get()) {
                commands.offer(Command.ClearOutput)
            }
        }

        fun machineSnapshot(): ByteArray {
            check(!closed.get()) { "K16 endpoint worker is closed" }
            val response = CompletableFuture<ByteArray>()
            commands.offer(Command.MachineSnapshot(response))
            return response.join()
        }

        fun attachRetainedDisplayViewer(
            viewerToken: Long,
            computerId: Int,
        ): Long {
            check(!closed.get()) { "K16 endpoint worker is closed" }
            val response = CompletableFuture<Long>()
            commands.offer(Command.AttachRetainedDisplayViewer(viewerToken, computerId, response))
            return response.join()
        }

        fun detachRetainedDisplayViewer(viewerToken: Long): Boolean {
            check(!closed.get()) { "K16 endpoint worker is closed" }
            val response = CompletableFuture<Boolean>()
            commands.offer(Command.DetachRetainedDisplayViewer(viewerToken, response))
            return response.join()
        }

        fun acceptRetainedDisplayServerbound(
            viewerToken: Long,
            payload: ByteArray,
        ): Int {
            check(!closed.get()) { "K16 endpoint worker is closed" }
            val response = CompletableFuture<Int>()
            commands.offer(Command.AcceptRetainedDisplayServerbound(viewerToken, payload.copyOf(), response))
            return response.join()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                commands.offer(Command.Close)
                workerThread.join()
            }
        }

        private fun runWorker() {
            var endpoint: K16ComputerEndpoint? = null
            var retainedViewerTokens: MutableSet<Long>? = null
            try {
                endpoint = endpointFactory()
                refreshCaches(endpoint)
                startup.complete(Unit)
            } catch (error: Throwable) {
                startup.completeExceptionally(error)
                endpoint?.close()
                return
            }
            try {
                var waitingForEvent = false
                while (true) {
                    when (val command = commands.take()) {
                        Command.Tick -> {
                            tickRequested.set(false)
                            if (!terminalControlReached) {
                                val gameTicks = pendingGameTicks.getAndSet(0)
                                if (gameTicks > 0) {
                                    endpoint.advanceGameTicks(gameTicks)
                                }
                                if (gameTicks > 0 || !waitingForEvent) {
                                    if (waitingForEvent && gameTicks > 0) {
                                        metricsCollector.recordK16WaitTimerWakeup()
                                    }
                                    waitingForEvent = runEndpointSlice(endpoint)
                                } else {
                                    metricsCollector.recordK16WaitIdleSkip()
                                }
                            }
                            if (!retainedViewerTokens.isNullOrEmpty()) {
                                captureRetainedDisplayPayloads(endpoint)
                            }
                        }

                        is Command.PushInput -> {
                            recordTextInput(command.bytes.size) {
                                endpoint.pushInput(command.bytes)
                            }
                            if (waitingForEvent) {
                                metricsCollector.recordK16WaitInputWakeup()
                                waitingForEvent = runEndpointSlice(endpoint)
                            }
                        }

                        is Command.PushKeyboardKeyDown -> {
                            endpoint.pushKeyboardKeyDown(command.key, command.repeat, command.modifiers)
                            if (waitingForEvent) {
                                metricsCollector.recordK16WaitInputWakeup()
                                waitingForEvent = runEndpointSlice(endpoint)
                            }
                        }

                        is Command.PushKeyboardKeyUp -> {
                            endpoint.pushKeyboardKeyUp(command.key, command.modifiers)
                            if (waitingForEvent) {
                                metricsCollector.recordK16WaitInputWakeup()
                                waitingForEvent = runEndpointSlice(endpoint)
                            }
                        }

                        is Command.PushKeyboardChar -> {
                            recordTextInput(byteCount = 1) {
                                endpoint.pushKeyboardChar(command.value)
                            }
                            if (waitingForEvent) {
                                metricsCollector.recordK16WaitInputWakeup()
                                waitingForEvent = runEndpointSlice(endpoint)
                            }
                        }

                        is Command.PushKeyboardPasteBytes -> {
                            recordTextInput(command.bytes.size) {
                                endpoint.pushKeyboardPasteBytes(command.bytes)
                            }
                            if (waitingForEvent) {
                                metricsCollector.recordK16WaitInputWakeup()
                                waitingForEvent = runEndpointSlice(endpoint)
                            }
                        }

                        Command.ClearOutput -> {
                            endpoint.clearOutput()
                            refreshCaches(endpoint)
                        }

                        is Command.MachineSnapshot -> {
                            try {
                                command.response.complete(endpoint.machineSnapshot())
                            } catch (error: Throwable) {
                                command.response.completeExceptionally(error)
                            }
                        }

                        is Command.AttachRetainedDisplayViewer -> {
                            complete(command.response) {
                                endpoint.attachRetainedDisplayViewer(command.viewerToken, command.computerId).also {
                                    val tokens = retainedViewerTokens ?: linkedSetOf<Long>().also { retainedViewerTokens = it }
                                    tokens += command.viewerToken
                                    captureRetainedDisplayPayloads(endpoint)
                                }
                            }
                        }

                        is Command.DetachRetainedDisplayViewer -> {
                            complete(command.response) {
                                endpoint.detachRetainedDisplayViewer(command.viewerToken).also {
                                    retainedViewerTokens?.remove(command.viewerToken)
                                    if (retainedViewerTokens?.isEmpty() == true) retainedViewerTokens = null
                                }
                            }
                        }

                        is Command.AcceptRetainedDisplayServerbound -> {
                            complete(command.response) {
                                val outcome =
                                    endpoint.acceptRetainedDisplayServerbound(command.viewerToken, command.payload)
                                if (outcome == 3) {
                                    endpoint.attachRetainedDisplayViewer(command.viewerToken, deviceId)
                                }
                                captureRetainedDisplayPayloads(endpoint)
                                outcome
                            }
                        }

                        Command.Close -> {
                            break
                        }
                    }
                }
            } finally {
                endpoint.close()
            }
        }

        private fun runEndpointSlice(endpoint: K16ComputerEndpoint): Boolean {
            val startedAt = System.nanoTime()
            val result = endpoint.tickUntilSignal()
            metricsCollector.recordK16RunSlice(
                result.signal.toRuntimeSignal(),
                System.nanoTime() - startedAt,
                yieldSignals = result.yieldSignals,
            )
            terminalControlReached =
                result.control.isTerminal() || result.signal == NativeK16ComputerSignal.Halt
            refreshCaches(endpoint)
            val waitingForEvent = !terminalControlReached && result.signal == NativeK16ComputerSignal.Wait
            if (waitingForEvent) {
                metricsCollector.recordK16WaitEnter()
            }
            return waitingForEvent
        }

        private fun captureRetainedDisplayPayloads(endpoint: K16ComputerEndpoint) {
            for (payload in endpoint.drainRetainedDisplayPayloads()) {
                onRetainedDisplayPayloadReady(this, payload)
            }
        }

        private inline fun recordTextInput(
            byteCount: Int,
            push: () -> Unit,
        ) {
            val startedAt = System.nanoTime()
            push()
            metricsCollector.recordK16TextInput(byteCount, System.nanoTime() - startedAt)
        }

        private inline fun <T> complete(
            response: CompletableFuture<T>,
            operation: () -> T,
        ) {
            try {
                response.complete(operation())
            } catch (error: Throwable) {
                response.completeExceptionally(error)
            }
        }

        private fun refreshCaches(endpoint: K16ComputerEndpoint) {
            val startedAt = System.nanoTime()
            val outputBytes = endpoint.outputSnapshot()
            outputCache = outputBytes
            metricsCollector.recordK16OutputRefresh(
                serialOutputBytes = outputBytes.size,
                nanos = System.nanoTime() - startedAt,
            )
            if (metricsCollector.recordsK16StatsSnapshots) {
                metricsCollector.recordK16StatsSnapshot(endpoint.statsSnapshot())
            }
        }

        private sealed interface Command {
            data object Tick : Command

            data class PushInput(
                val bytes: ByteArray,
            ) : Command

            data class PushKeyboardKeyDown(
                val key: Int,
                val repeat: Boolean,
                val modifiers: Int,
            ) : Command

            data class PushKeyboardKeyUp(
                val key: Int,
                val modifiers: Int,
            ) : Command

            data class PushKeyboardChar(
                val value: Byte,
            ) : Command

            data class PushKeyboardPasteBytes(
                val bytes: ByteArray,
            ) : Command

            data object ClearOutput : Command

            data class MachineSnapshot(
                val response: CompletableFuture<ByteArray>,
            ) : Command

            data class AttachRetainedDisplayViewer(
                val viewerToken: Long,
                val computerId: Int,
                val response: CompletableFuture<Long>,
            ) : Command

            data class DetachRetainedDisplayViewer(
                val viewerToken: Long,
                val response: CompletableFuture<Boolean>,
            ) : Command

            data class AcceptRetainedDisplayServerbound(
                val viewerToken: Long,
                val payload: ByteArray,
                val response: CompletableFuture<Int>,
            ) : Command

            data object Close : Command
        }
    }

    companion object {
        const val STATUS_RESET: Int = NativeK16ComputerControl.STATUS_RESET
        const val STATUS_BOOTING: Int = NativeK16ComputerControl.STATUS_BOOTING
        const val STATUS_READY: Int = NativeK16ComputerControl.STATUS_READY
        const val STATUS_HALTED: Int = NativeK16ComputerControl.STATUS_HALTED
        const val STATUS_PANIC: Int = NativeK16ComputerControl.STATUS_PANIC
    }
}

private fun NativeK16ComputerSignal.toRuntimeSignal(): K16RuntimeSignal =
    when (this) {
        NativeK16ComputerSignal.Halt -> K16RuntimeSignal.HALT
        NativeK16ComputerSignal.Wait -> K16RuntimeSignal.WAIT
        NativeK16ComputerSignal.Yield -> K16RuntimeSignal.YIELD
        NativeK16ComputerSignal.Pause -> K16RuntimeSignal.PAUSE
    }
