/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath

/** Experimental FFM lifecycle transport. The bridge must outlive this fleet. */
class ExperimentalFfmFleet private constructor(
    private val bridge: FfmBridge,
    private val arena: Arena,
    private var handle: Long,
    private val calls: Calls,
    override val coordinatedTurns: Boolean,
) : ExperimentalFleet {
    private val output = arena.allocate(1_048_576)
    private val eventInput = output.asByteBuffer()
    private val written = arena.allocate(ValueLayout.JAVA_LONG)
    private val result = arena.allocate(ValueLayout.JAVA_LONG)
    private val owned = mutableSetOf<Long>()

    @Synchronized
    override fun adopt(bridge: LowLevelVmBridge, session: Long): Long {
        require(bridge === this.bridge) { "fleet and session must share the same FFM bridge" }
        success("attach", calls.attach.invokeExact(active(), session, result) as Int)
        return result.get(ValueLayout.JAVA_LONG, 0).also { owned.add(it) }
    }

    @Synchronized
    override fun adoptWithTimers(bridge: LowLevelVmBridge, session: Long, tick: Long, timers: List<ExperimentalFleetTimer>): Long {
        require(bridge === this.bridge) { "fleet and session must share the same FFM bridge" }
        require(tick >= 0 && timers.size <= 256)
        val bytes = ByteBuffer.allocate(timers.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        timers.forEach { bytes.putInt(it.identity.taskId).putLong(it.identity.requestId).putLong(it.scope).putInt(it.duration).putLong(it.remaining) }
        Arena.ofConfined().use { temporary ->
            val packed = if (bytes.capacity() == 0) MemorySegment.NULL else temporary.allocate(bytes.capacity().toLong()).also {
                MemorySegment.copy(MemorySegment.ofArray(bytes.array()), 0, it, 0, bytes.capacity().toLong())
            }
            success("attach", calls.attachTimers.invokeExact(active(), session, tick, packed, bytes.capacity().toLong(), result) as Int)
        }
        return result.get(ValueLayout.JAVA_LONG, 0).also { owned.add(it) }
    }

    @Synchronized
    override fun recover(machine: Long): ExperimentalRestoredSession {
        require(machine in owned) { "unknown fleet session" }
        success("recover", calls.recover.invokeExact(active(), machine, result) as Int)
        owned.remove(machine)
        return ExperimentalRestoredSession(bridge, result.get(ValueLayout.JAVA_LONG, 0))
    }

    @Synchronized
    override fun grant(machine: Long, tick: Long, retirements: Int, deadlineNanos: Long) {
        require(machine >= 0 && tick >= 0 && retirements >= 0)
        val remaining = if (deadlineNanos == Long.MAX_VALUE) -1L else (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        success("grant", calls.grant.invokeExact(active(), machine, tick, retirements, remaining) as Int)
    }

    @Synchronized
    override fun reply(machine: Long, identity: VmHostRequestIdentity, response: HostResponse) {
        require(machine >= 0)
        val payload = when (response) {
            is HostResponse.Failure -> ExperimentalFleetResponseWire.failureDetail(response.detail)
            else -> ExperimentalFleetResponseWire.success(response)
        }
        withBytes(payload) { data ->
            val status = when (response) {
                is HostResponse.Failure -> calls.replyFailure.invokeExact(active(), machine, identity.taskId, identity.requestId,
                    ExperimentalFleetResponseWire.failureKind(response.kind), data, payload.size.toLong()) as Int
                else -> calls.reply.invokeExact(active(), machine, identity.taskId, identity.requestId, data, payload.size.toLong()) as Int
            }
            success("reply", status)
        }
    }

    @Synchronized
    override fun resolveCost(machine: Long, identity: VmHostRequestIdentity, units: Int) {
        require(machine >= 0 && units > 0)
        success("resolve cost", calls.resolveCost.invokeExact(active(), machine, identity.taskId, identity.requestId, units) as Int)
    }

    @Synchronized
    override fun completeCompilationArtifact(machine: Long, token: Long, artifact: ByteArray) = compilation(machine, token, 0, artifact)

    @Synchronized
    override fun completeCompilationFailure(machine: Long, token: Long, diagnostics: String) = compilation(machine, token, 1, diagnostics.encodeToByteArray())

    private fun compilation(machine: Long, token: Long, kind: Int, payload: ByteArray) {
        require(machine >= 0 && token > 0 && payload.size <= MAXIMUM_ARTIFACT_BYTES)
        withBytes(payload) { data ->
            success("compilation", calls.compilation.invokeExact(active(), machine, token, kind, data, payload.size.toLong()) as Int)
        }
    }

    private inline fun withBytes(bytes: ByteArray, block: (MemorySegment) -> Unit) {
        Arena.ofConfined().use { temporary ->
            val segment = if (bytes.isEmpty()) MemorySegment.NULL else temporary.allocate(bytes.size.toLong()).also {
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, it, 0, bytes.size.toLong())
            }
            block(segment)
        }
    }

    @Synchronized
    override fun terminalUpdate(machine: Long, request: Long, revision: Long) = observe(machine, request, 0, revision)

    @Synchronized
    override fun resourceSnapshot(machine: Long, request: Long) = observe(machine, request, 1, 0)

    @Synchronized
    override fun filesystemGeneration(machine: Long, request: Long) = observe(machine, request, 2, 0)

    private fun observe(machine: Long, request: Long, kind: Int, revision: Long) {
        require(machine >= 0 && request >= 0 && revision >= 0)
        success("observe", calls.observe.invokeExact(active(), machine, request, kind, revision) as Int)
    }

    @Synchronized
    override fun redstoneInput(machine: Long, packet: Int) = redstone(machine, 0, packet)

    @Synchronized
    override fun confirmRedstoneOutput(machine: Long, packed: Int) = redstone(machine, 1, packed)

    private fun redstone(machine: Long, kind: Int, packet: Int) {
        require(machine >= 0 && packet >= 0)
        success("redstone", calls.redstone.invokeExact(active(), machine, kind, packet) as Int)
    }

    @Synchronized
    override fun terminalKey(machine: Long, key: TerminalKey, action: TerminalKeyAction, modifiers: Set<TerminalModifier>, request: Long) {
        require(machine >= 0 && request >= -1)
        val bits = modifiers.fold(0) { bits, modifier -> bits or modifier.mask }
        success("terminal key", calls.terminalKey.invokeExact(active(), machine, key.wireCode, action.wireCode, bits, request) as Int)
    }

    @Synchronized
    override fun terminalText(machine: Long, text: String, request: Long) {
        require(machine >= 0 && request >= -1 && text.codePointCount(0, text.length) <= 4096 &&
            text.codePoints().allMatch { it !in 0xd800..0xdfff })
        val bytes = text.encodeToByteArray()
        withBytes(bytes) { data -> success("terminal text", calls.terminalText.invokeExact(active(), machine, data, bytes.size.toLong(), request) as Int) }
    }

    @Synchronized
    override fun canonicalLine(machine: Long, line: CharArray, request: Long) {
        require(machine >= 0 && request >= -1 && line.size <= 4096)
        Arena.ofConfined().use { temporary ->
            val data = if (line.isEmpty()) MemorySegment.NULL else temporary.allocate(ValueLayout.JAVA_CHAR, line.size.toLong()).also { segment ->
                line.forEachIndexed { index, char -> segment.setAtIndex(ValueLayout.JAVA_CHAR, index.toLong(), char) }
            }
            success("canonical line", calls.canonicalLine.invokeExact(active(), machine, data, line.size.toLong(), request) as Int)
        }
    }

    @Synchronized
    override fun fileStat(machine: Long, request: Long, path: VmVirtualPath) = filesystem(machine, request, 0, path, null, 0, 0, 0)

    @Synchronized
    override fun fileList(machine: Long, request: Long, path: VmVirtualPath, cursor: String?, maximum: Int) {
        require(cursor == null || (cursor.isNotEmpty() && cursor.encodeToByteArray().size <= 4096))
        require(maximum in 1..256)
        filesystem(machine, request, 1, path, cursor, 0, maximum, 0)
    }

    @Synchronized
    override fun fileRead(machine: Long, request: Long, path: VmVirtualPath, offset: Long, maximum: Int, generation: Long) {
        require(offset >= 0 && generation >= 0 && maximum in 1..32768)
        filesystem(machine, request, 2, path, null, offset, maximum, generation)
    }

    private fun filesystem(machine: Long, request: Long, kind: Int, path: VmVirtualPath, cursor: String?, offset: Long, maximum: Int, generation: Long) {
        require(machine >= 0 && request >= 0)
        val pathBytes = path.value.encodeToByteArray()
        val cursorBytes = cursor?.encodeToByteArray() ?: byteArrayOf()
        withBytes(pathBytes) { pathData -> withBytes(cursorBytes) { cursorData ->
            success("filesystem", calls.filesystem.invokeExact(active(), machine, request, kind, pathData, pathBytes.size.toLong(),
                cursorData, cursorBytes.size.toLong(), offset, maximum, generation) as Int)
        } }
    }

    @Synchronized
    override fun prepareDeployment(machine: Long, request: Long, artifact: ByteArray) {
        require(artifact.size <= MAXIMUM_ARTIFACT_BYTES)
        deployment(machine, request, 0, artifact, 0, 0)
    }

    @Synchronized
    override fun deploymentRevision(machine: Long, request: Long, path: VmVirtualPath) = deployment(machine, request, 1, path.value.encodeToByteArray(), 0, 0)

    @Synchronized
    override fun installDeployment(machine: Long, request: Long, path: VmVirtualPath, candidate: Long, expected: VmExecutableRevision) {
        val revision = when (expected) {
            VmExecutableRevision.Absent -> 0L
            is VmExecutableRevision.Present -> expected.generation.also { require(it > 0) }
        }
        deployment(machine, request, 2, path.value.encodeToByteArray(), candidate, revision)
    }

    @Synchronized
    override fun discardDeployment(machine: Long, request: Long, candidate: Long) = deployment(machine, request, 3, byteArrayOf(), candidate, 0)

    private fun deployment(machine: Long, request: Long, kind: Int, bytes: ByteArray, candidate: Long, revision: Long) {
        require(machine >= 0 && request >= 0 && candidate >= 0 && revision >= 0)
        withBytes(bytes) { data ->
            success("deployment", calls.deployment.invokeExact(active(), machine, request, kind, data, bytes.size.toLong(), candidate, revision) as Int)
        }
    }

    @Synchronized
    override fun detach(machine: Long, independent: Boolean) {
        require(machine >= 0)
        success("detach", if (independent) calls.requestLifecycle.invokeExact(active(), machine, 1, 0L) as Int
            else calls.detach.invokeExact(active(), machine) as Int)
    }

    @Synchronized
    override fun detachWithTimers(machine: Long, tick: Long, independent: Boolean) {
        require(machine >= 0 && tick >= 0)
        success("detach", if (independent) calls.requestLifecycle.invokeExact(active(), machine, 2, tick) as Int
            else calls.detachTimers.invokeExact(active(), machine, tick) as Int)
    }

    @Synchronized
    override fun detachForCheckpoint(machine: Long, tick: Long) {
        require(machine >= 0 && tick >= 0)
        success("checkpoint detach", calls.requestLifecycle.invokeExact(active(), machine, 3, tick) as Int)
    }

    @Synchronized
    override fun stop(machine: Long, independent: Boolean) {
        require(machine >= 0)
        success("stop", if (independent) calls.requestLifecycle.invokeExact(active(), machine, 0, 0L) as Int
            else calls.stop.invokeExact(active(), machine) as Int)
    }

    @Synchronized
    override fun eventGeneration(): Long = Arena.ofConfined().use { temporary ->
        val value = temporary.allocate(ValueLayout.JAVA_LONG)
        success("event generation", calls.generation.invokeExact(active(), value) as Int)
        value.get(ValueLayout.JAVA_LONG, 0)
    }

    override fun waitEvents(generation: Long, timeoutMillis: Int): Boolean {
        require(timeoutMillis in 0..60_000)
        val fleetHandle = synchronized(this) {
            val current = active()
            bridge.retainFleet()
            current
        }
        try {
            return Arena.ofConfined().use { temporary ->
                val value = temporary.allocate(ValueLayout.JAVA_INT)
                success("wait events", calls.waitEvents.invokeExact(fleetHandle, generation, timeoutMillis, value) as Int)
                value.get(ValueLayout.JAVA_INT, 0) != 0
            }
        } finally {
            bridge.releaseFleet()
        }
    }

    @Synchronized
    override fun poll(): ExperimentalFleetEvent? {
        written.set(ValueLayout.JAVA_LONG, 0, 0)
        success("poll", calls.poll.invokeExact(active(), output, output.byteSize(), written) as Int)
        return decodePolledEvent()
    }

    @Synchronized
    override fun pollLifecycle(machine: Long): ExperimentalFleetEvent? {
        written.set(ValueLayout.JAVA_LONG, 0, 0)
        success("poll lifecycle", calls.pollLifecycle.invokeExact(active(), machine, output, output.byteSize(), written) as Int)
        return decodePolledEvent()
    }

    private fun decodePolledEvent(): ExperimentalFleetEvent? {
        val length = written.get(ValueLayout.JAVA_LONG, 0)
        require(length in 0..output.byteSize()) { "invalid FFM fleet event length" }
        if (length == 0L) return null
        val event = ExperimentalFleetEventWire.decode(eventInput.clear().limit(length.toInt()))
        if (event is ExperimentalFleetEvent.Stopped) owned.remove(event.machine)
        return event
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        success("close", calls.close.invokeExact(handle) as Int)
        handle = 0
        try { arena.close() } finally { bridge.releaseFleet() }
    }

    private fun active(): Long = handle.takeIf { it != 0L } ?: error("fleet is closed")

    private class Calls(val attach: MethodHandle, val attachTimers: MethodHandle, val recover: MethodHandle,
        val grant: MethodHandle, val detach: MethodHandle, val detachTimers: MethodHandle,
        val stop: MethodHandle, val requestLifecycle: MethodHandle, val poll: MethodHandle, val pollLifecycle: MethodHandle, val close: MethodHandle,
        val generation: MethodHandle, val waitEvents: MethodHandle,
        val reply: MethodHandle, val replyFailure: MethodHandle, val resolveCost: MethodHandle, val compilation: MethodHandle,
        val observe: MethodHandle, val redstone: MethodHandle, val terminalKey: MethodHandle,
        val terminalText: MethodHandle, val canonicalLine: MethodHandle,
        val filesystem: MethodHandle, val deployment: MethodHandle)

    companion object {
        private const val MAXIMUM_ARTIFACT_BYTES = 16 * 1024 * 1024

        fun open(bridge: LowLevelVmBridge, workers: Int, mailboxCapacity: Int, eventCapacity: Int,
            guestBudget: Int, maintenanceBudget: Int, hostBudget: Int, advancesPerTick: Int,
            inspection: ExperimentalRequestInspection? = null, coordinatedTurns: Boolean = false): ExperimentalFfmFleet {
            require(bridge is FfmBridge) { "fleet requires an FFM bridge" }
            require(workers in 1..64 && mailboxCapacity in 1..65536 && eventCapacity in 1..65536 &&
                guestBudget > 0 && maintenanceBudget >= 0 && hostBudget > 0 && advancesPerTick > 0) { "invalid fleet configuration" }
            val lookup = bridge.retainFleet()
            val arena = try { Arena.ofShared() } catch (failure: Throwable) { bridge.releaseFleet(); throw failure }
            var createdHandle = 0L
            var closeCall: MethodHandle? = null
            try {
                val linker = Linker.nativeLinker()
                fun call(name: String, vararg arguments: java.lang.foreign.MemoryLayout): MethodHandle =
                    linker.downcallHandle(lookup.find("compukter_experimental_fleet_$name").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, *arguments))
                val l = ValueLayout.JAVA_LONG
                val i = ValueLayout.JAVA_INT
                val p = ValueLayout.ADDRESS
                val create = call("create", p, p)
                val createInspected = call("create_inspection", p, p, l, p, l, p, l, i, i, i, p)
                val calls = Calls(call("attach", l, l, p), call("attach_timers", l, l, l, p, l, p),
                    call("recover", l, l, p), call("grant", l, l, l, i, l), call("detach", l, l),
                    call("detach_timers", l, l, l), call("stop", l, l), call("request_lifecycle", l, l, i, l), call("poll", l, p, l, p), call("poll_lifecycle", l, l, p, l, p), call("close", l),
                    call("event_generation", l, p), call("wait_events", l, l, i, p),
                    call("reply", l, l, i, l, p, l), call("reply_failure", l, l, i, l, i, p, l),
                    call("resolve_cost", l, l, i, l, i), call("compilation", l, l, l, i, p, l),
                    call("observe", l, l, l, i, l), call("redstone", l, l, i, i), call("terminal_key", l, l, i, i, i, l),
                    call("terminal_text", l, l, p, l, l), call("canonical_line", l, l, p, l, l),
                    call("filesystem", l, l, l, i, p, l, p, l, l, i, l), call("deployment", l, l, l, i, p, l, l, l))
                closeCall = calls.close
                val config = arena.allocate(ValueLayout.JAVA_INT, 8)
                intArrayOf(workers, mailboxCapacity, eventCapacity, guestBudget, maintenanceBudget, hostBudget, advancesPerTick, if (coordinatedTurns) 1 else 0)
                    .forEachIndexed { index, value -> config.setAtIndex(ValueLayout.JAVA_INT, index.toLong(), value) }
                val result = arena.allocate(ValueLayout.JAVA_LONG)
                val status = if (inspection == null) {
                    create.invokeExact(config, result) as Int
                } else {
                    require(inspection.operation >= 0)
                    val capability = inspection.capability
                    val namespace = capability.namespace.encodeToByteArray()
                    val name = capability.name.encodeToByteArray()
                    val detail = ExperimentalFleetResponseWire.failureDetail(inspection.overBudgetDetail)
                    fun native(bytes: ByteArray) = arena.allocate(bytes.size.toLong().coerceAtLeast(1)).also {
                        if (bytes.isNotEmpty()) MemorySegment.copy(MemorySegment.ofArray(bytes), 0, it, 0, bytes.size.toLong())
                    }
                    createInspected.invokeExact(config, native(namespace), namespace.size.toLong(), native(name), name.size.toLong(),
                        native(detail), detail.size.toLong(), capability.abiMajor, capability.abiMinor, inspection.operation, result) as Int
                }
                success("create", status)
                createdHandle = result.get(ValueLayout.JAVA_LONG, 0)
                return ExperimentalFfmFleet(bridge, arena, createdHandle, calls, coordinatedTurns)
            } catch (failure: Throwable) {
                if (createdHandle != 0L) {
                    try { closeCall!!.invokeExact(createdHandle) as Int } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                }
                try { arena.close() } finally { bridge.releaseFleet() }
                throw failure
            }
        }

        private fun success(operation: String, status: Int) = requireFleetSuccess(operation, status)
    }
}
