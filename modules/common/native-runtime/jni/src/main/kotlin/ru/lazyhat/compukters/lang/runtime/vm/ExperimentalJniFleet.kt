/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in transport. Call creation and close outside the server thread. */
class ExperimentalJniFleet private constructor(
    private val bridge: JniBridge,
    @Volatile private var handle: Long,
    override val coordinatedTurns: Boolean,
) : ExperimentalFleet {
    private val output = ByteArray(1_048_576)
    private val written = LongArray(1)
    private val sessionBridges = mutableMapOf<Long, LowLevelVmBridge>()

    @Synchronized
    override fun adopt(bridge: LowLevelVmBridge, session: Long): Long {
        require(bridge is JniBridge) { "fleet and session must use JNI" }
        val machine = LongArray(1)
        success("attach", JniExperimentalFleet.attachSession(active(), session, machine))
        sessionBridges[machine[0]] = bridge
        return machine[0]
    }

    @Synchronized
    override fun adoptWithTimers(bridge: LowLevelVmBridge, session: Long, tick: Long, timers: List<ExperimentalFleetTimer>): Long {
        require(bridge is JniBridge) { "fleet and session must use JNI" }
        require(tick >= 0 && timers.size <= 256)
        val packed = ByteBuffer.allocate(timers.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        timers.forEach {
            packed.putInt(it.identity.taskId).putLong(it.identity.requestId).putLong(it.scope)
                .putInt(it.duration).putLong(it.remaining)
        }
        val machine = LongArray(1)
        success("attach", JniExperimentalFleet.attachSessionWithTimers(active(), session, tick, packed.array(), machine))
        sessionBridges[machine[0]] = bridge
        return machine[0]
    }

    @Synchronized
    override fun recover(machine: Long): ExperimentalRestoredSession {
        val originalBridge = requireNotNull(sessionBridges[machine]) { "unknown fleet session" }
        val session = LongArray(1)
        success("restore", JniExperimentalFleet.restoreSession(active(), machine, session))
        sessionBridges.remove(machine)
        return ExperimentalRestoredSession(originalBridge, session[0])
    }

    /** [deadlineNanos] uses the same System.nanoTime clock as the server scheduler. */
    @Synchronized
    override fun grant(machine: Long, tick: Long, retirements: Int, deadlineNanos: Long) {
        val remainingNanos = if (deadlineNanos == Long.MAX_VALUE) -1 else (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        success("grant", JniExperimentalFleet.grant(active(), machine, tick, retirements, remainingNanos))
    }

    @Synchronized
    override fun reply(machine: Long, identity: VmHostRequestIdentity, response: HostResponse) {
        val status = when (response) {
            is HostResponse.Failure -> JniExperimentalFleet.replyFailure(active(), machine, identity.taskId, identity.requestId, ExperimentalFleetResponseWire.failureKind(response.kind), ExperimentalFleetResponseWire.failureDetail(response.detail))
            else -> JniExperimentalFleet.replyWire(active(), machine, identity.taskId, identity.requestId, ExperimentalFleetResponseWire.success(response))
        }
        success("reply", status)
    }

    @Synchronized
    override fun resolveCost(machine: Long, identity: VmHostRequestIdentity, units: Int) =
        success("resolve cost", JniExperimentalFleet.resolveCost(active(), machine, identity.taskId, identity.requestId, units))

    @Synchronized
    override fun completeCompilationArtifact(machine: Long, token: Long, artifact: ByteArray) =
        success("compilation completion", JniExperimentalFleet.completeCompilation(active(), machine, token, 0, artifact))

    @Synchronized
    override fun completeCompilationFailure(machine: Long, token: Long, diagnostics: String) =
        success("compilation completion", JniExperimentalFleet.completeCompilation(active(), machine, token, 1, diagnostics.encodeToByteArray()))

    @Synchronized
    override fun terminalKey(machine: Long, key: TerminalKey, action: TerminalKeyAction, modifiers: Set<TerminalModifier>, request: Long) =
        success("terminal key", JniExperimentalFleet.terminalKey(active(), machine, key.wireCode, action.wireCode, modifiers.fold(0) { bits, modifier -> bits or modifier.mask }, request))

    @Synchronized
    override fun terminalUpdate(machine: Long, request: Long, revision: Long) =
        success("terminal update", JniExperimentalFleet.terminalUpdate(active(), machine, request, revision))

    @Synchronized
    override fun resourceSnapshot(machine: Long, request: Long) =
        success("resource snapshot", JniExperimentalFleet.resourceSnapshot(active(), machine, request))

    @Synchronized
    override fun filesystemGeneration(machine: Long, request: Long) =
        success("filesystem generation", JniExperimentalFleet.filesystemGeneration(active(), machine, request))

    /** Results are delivered as Deployment events; prepared candidates remain owned by this machine. */
    @Synchronized
    override fun prepareDeployment(machine: Long, request: Long, artifact: ByteArray) =
        success("prepare deployment", JniExperimentalFleet.deployment(active(), machine, request, 0, artifact, 0, 0))

    @Synchronized
    override fun deploymentRevision(machine: Long, request: Long, path: VmVirtualPath) =
        success("deployment revision", JniExperimentalFleet.deployment(active(), machine, request, 1, path.value.encodeToByteArray(), 0, 0))

    @Synchronized
    override fun installDeployment(machine: Long, request: Long, path: VmVirtualPath, candidate: Long, expected: VmExecutableRevision) {
        val revision = when (expected) {
            VmExecutableRevision.Absent -> 0L
            is VmExecutableRevision.Present -> expected.generation.also { require(it > 0) }
        }
        success("install deployment", JniExperimentalFleet.deployment(active(), machine, request, 2, path.value.encodeToByteArray(), candidate, revision))
    }

    @Synchronized
    override fun discardDeployment(machine: Long, request: Long, candidate: Long) =
        success("discard deployment", JniExperimentalFleet.deployment(active(), machine, request, 3, byteArrayOf(), candidate, 0))

    @Synchronized
    override fun fileStat(machine: Long, request: Long, path: VmVirtualPath) =
        success("file stat", JniExperimentalFleet.filesystemQuery(active(), machine, request, 0, path.value.encodeToByteArray(), byteArrayOf(), 0, 0, 0))

    @Synchronized
    override fun fileList(machine: Long, request: Long, path: VmVirtualPath, cursor: String?, maximum: Int) {
        require(cursor == null || cursor.isNotEmpty()) { "filesystem cursor is empty" }
        require(maximum in 1..256) { "filesystem list page must contain 1..256 entries" }
        success("file list", JniExperimentalFleet.filesystemQuery(active(), machine, request, 1, path.value.encodeToByteArray(), cursor?.encodeToByteArray() ?: byteArrayOf(), 0, maximum, 0))
    }

    @Synchronized
    override fun fileRead(machine: Long, request: Long, path: VmVirtualPath, offset: Long, maximum: Int, generation: Long) {
        require(offset >= 0 && generation >= 0 && maximum in 1..32768) { "invalid filesystem read range" }
        success("file read", JniExperimentalFleet.filesystemQuery(active(), machine, request, 2, path.value.encodeToByteArray(), byteArrayOf(), offset, maximum, generation))
    }

    @Synchronized
    override fun redstoneInput(machine: Long, packet: Int) =
        success("redstone input", JniExperimentalFleet.redstoneInput(active(), machine, packet))

    @Synchronized
    override fun confirmRedstoneOutput(machine: Long, packed: Int) =
        success("redstone output", JniExperimentalFleet.confirmRedstoneOutput(active(), machine, packed))

    @Synchronized
    override fun terminalText(machine: Long, text: String, request: Long) =
        success("terminal text", JniExperimentalFleet.terminalText(active(), machine, text.codePoints().toArray(), request))

    @Synchronized
    override fun canonicalLine(machine: Long, line: CharArray, request: Long) =
        success("canonical line", JniExperimentalFleet.canonicalLine(active(), machine, line, request))

    @Synchronized
    override fun stop(machine: Long, independent: Boolean) = success("stop",
        if (independent) JniExperimentalFleet.requestLifecycle(active(), machine, 0, 0)
        else JniExperimentalFleet.stop(active(), machine))

    @Synchronized
    override fun detach(machine: Long, independent: Boolean) = success("detach",
        if (independent) JniExperimentalFleet.requestLifecycle(active(), machine, 1, 0)
        else JniExperimentalFleet.detach(active(), machine))

    @Synchronized
    override fun detachWithTimers(machine: Long, tick: Long, independent: Boolean) =
        success("detach", if (independent) JniExperimentalFleet.requestLifecycle(active(), machine, 2, tick)
        else JniExperimentalFleet.detachWithTimers(active(), machine, tick))

    @Synchronized
    override fun detachForCheckpoint(machine: Long, tick: Long) {
        require(machine >= 0 && tick >= 0)
        success("checkpoint detach", JniExperimentalFleet.requestLifecycle(active(), machine, 3, tick))
    }

    @Synchronized
    override fun eventGeneration(): Long {
        val value = LongArray(1)
        success("event generation", JniExperimentalFleet.eventGeneration(active(), value))
        return value[0]
    }

    override fun waitEvents(generation: Long, timeoutMillis: Int): Boolean {
        require(timeoutMillis in 0..60_000)
        val value = LongArray(1)
        success("wait events", JniExperimentalFleet.waitEvents(active(), generation, timeoutMillis, value))
        return value[0] != 0L
    }

    @Synchronized
    override fun poll(): ExperimentalFleetEvent? {
        success("poll", JniExperimentalFleet.poll(active(), output, written))
        return decodePolledEvent()
    }

    @Synchronized
    override fun pollLifecycle(machine: Long): ExperimentalFleetEvent? {
        success("poll lifecycle", JniExperimentalFleet.pollLifecycle(active(), machine, output, written))
        return decodePolledEvent()
    }

    private fun decodePolledEvent(): ExperimentalFleetEvent? {
        require(written[0] in 0..output.size.toLong()) { "invalid native fleet event length" }
        if (written[0] == 0L) return null
        val event = ExperimentalFleetEventWire.decode(output.copyOf(written[0].toInt()))
        if (event is ExperimentalFleetEvent.Stopped) sessionBridges.remove(event.machine)
        return event
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            success("close", JniExperimentalFleet.close(handle))
            handle = 0
        }
    }

    private fun active(): Long = handle.takeIf { it != 0L } ?: error("fleet is closed")

    companion object {
        /** Coordinated turns require a new grant after each Quiescent event, including after replies. */
        fun open(
            library: Path,
            workers: Int,
            mailboxCapacity: Int,
            eventCapacity: Int,
            guestBudget: Int,
            maintenanceBudget: Int,
            hostBudget: Int,
            advancesPerTick: Int,
            inspection: ExperimentalRequestInspection? = null,
            coordinatedTurns: Boolean = false,
        ): ExperimentalJniFleet {
            return openWithBridge(JniBridge.open(library), ExperimentalFleetConfig(workers, mailboxCapacity, eventCapacity,
                guestBudget, maintenanceBudget, hostBudget, advancesPerTick, inspection, coordinatedTurns))
        }

        internal fun openWithBridge(bridge: JniBridge, config: ExperimentalFleetConfig): ExperimentalJniFleet {
            val output = LongArray(1)
            val empty = byteArrayOf()
            val capability = config.inspection?.capability
            val detail = config.inspection?.let { ExperimentalFleetResponseWire.failureDetail(it.overBudgetDetail) } ?: empty
            success(
                "create",
                JniExperimentalFleet.create(
                    empty, byteArrayOf(1, 0), 0,
                    intArrayOf(config.workers, config.mailboxCapacity, config.eventCapacity, config.guestBudget, config.maintenanceBudget, config.hostBudget, config.advancesPerTick, if (config.coordinatedTurns) 1 else 0),
                    capability?.namespace?.encodeToByteArray() ?: empty,
                    capability?.name?.encodeToByteArray() ?: empty,
                    detail, capability?.abiMajor ?: 0, capability?.abiMinor ?: 0, config.inspection?.operation ?: 0, output,
                ),
            )
            return ExperimentalJniFleet(bridge, output[0], config.coordinatedTurns)
        }

        private fun success(operation: String, status: Int) = requireFleetSuccess(operation, status)
    }
}
