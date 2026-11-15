/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import java.nio.file.Path

data class ExperimentalRequestInspection(
    val capability: CapabilityIdentity,
    val operation: Int,
    val overBudgetDetail: String,
)

/** Opt-in transport. Call creation and close outside the server thread. */
class ExperimentalJniFleet private constructor(
    private val bridge: JniBridge,
    private var handle: Long,
) : ExperimentalFleetSessionOwner, AutoCloseable {
    private val output = ByteArray(1_048_576)
    private val written = LongArray(1)

    @Synchronized
    override fun adopt(bridge: LowLevelVmBridge, session: Long): Long {
        require(bridge is JniBridge) { "fleet and session must use JNI" }
        val machine = LongArray(1)
        success("attach", JniExperimentalFleet.attachSession(active(), session, machine))
        return machine[0]
    }

    @Synchronized
    override fun recover(machine: Long): ExperimentalRestoredSession {
        val session = LongArray(1)
        success("restore", JniExperimentalFleet.restoreSession(active(), machine, session))
        return ExperimentalRestoredSession(bridge, session[0])
    }

    /** [deadlineNanos] uses the same System.nanoTime clock as the server scheduler. */
    @Synchronized
    fun grant(machine: Long, tick: Long, retirements: Int, deadlineNanos: Long = Long.MAX_VALUE) {
        val remainingNanos = if (deadlineNanos == Long.MAX_VALUE) -1 else (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        success("grant", JniExperimentalFleet.grant(active(), machine, tick, retirements, remainingNanos))
    }

    @Synchronized
    fun reply(machine: Long, identity: VmHostRequestIdentity, response: HostResponse) {
        val status = when (response) {
            is HostResponse.Failure -> JniExperimentalFleet.replyFailure(active(), machine, identity.taskId, identity.requestId, ExperimentalFleetResponseWire.failureKind(response.kind), ExperimentalFleetResponseWire.failureDetail(response.detail))
            else -> JniExperimentalFleet.replyWire(active(), machine, identity.taskId, identity.requestId, ExperimentalFleetResponseWire.success(response))
        }
        success("reply", status)
    }

    @Synchronized
    fun resolveCost(machine: Long, identity: VmHostRequestIdentity, units: Int) =
        success("resolve cost", JniExperimentalFleet.resolveCost(active(), machine, identity.taskId, identity.requestId, units))

    @Synchronized
    fun completeCompilationArtifact(machine: Long, token: Long, artifact: ByteArray) =
        success("compilation completion", JniExperimentalFleet.completeCompilation(active(), machine, token, 0, artifact))

    @Synchronized
    fun completeCompilationFailure(machine: Long, token: Long, diagnostics: String) =
        success("compilation completion", JniExperimentalFleet.completeCompilation(active(), machine, token, 1, diagnostics.encodeToByteArray()))

    @Synchronized
    fun terminalKey(machine: Long, key: TerminalKey, action: TerminalKeyAction, modifiers: Set<TerminalModifier>) =
        success("terminal key", JniExperimentalFleet.terminalKey(active(), machine, key.wireCode, action.wireCode, modifiers.fold(0) { bits, modifier -> bits or modifier.mask }))

    @Synchronized
    fun terminalUpdate(machine: Long, request: Long, revision: Long) =
        success("terminal update", JniExperimentalFleet.terminalUpdate(active(), machine, request, revision))

    @Synchronized
    fun resourceSnapshot(machine: Long, request: Long) =
        success("resource snapshot", JniExperimentalFleet.resourceSnapshot(active(), machine, request))

    @Synchronized
    fun filesystemGeneration(machine: Long, request: Long) =
        success("filesystem generation", JniExperimentalFleet.filesystemGeneration(active(), machine, request))

    @Synchronized
    fun redstoneInput(machine: Long, packet: Int) =
        success("redstone input", JniExperimentalFleet.redstoneInput(active(), machine, packet))

    @Synchronized
    fun confirmRedstoneOutput(machine: Long, packed: Int) =
        success("redstone output", JniExperimentalFleet.confirmRedstoneOutput(active(), machine, packed))

    @Synchronized
    fun terminalText(machine: Long, text: String) =
        success("terminal text", JniExperimentalFleet.terminalText(active(), machine, text.codePoints().toArray()))

    @Synchronized
    fun canonicalLine(machine: Long, line: CharArray) =
        success("canonical line", JniExperimentalFleet.canonicalLine(active(), machine, line))

    @Synchronized
    fun stop(machine: Long) = success("stop", JniExperimentalFleet.stop(active(), machine))

    @Synchronized
    fun detach(machine: Long) = success("detach", JniExperimentalFleet.detach(active(), machine))

    @Synchronized
    fun poll(): ExperimentalFleetEvent? {
        success("poll", JniExperimentalFleet.poll(active(), output, written))
        require(written[0] in 0..output.size.toLong()) { "invalid native fleet event length" }
        return if (written[0] == 0L) null else ExperimentalFleetEventWire.decode(output.copyOf(written[0].toInt()))
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
            val bridge = JniBridge.open(library)
            val output = LongArray(1)
            val empty = byteArrayOf()
            val capability = inspection?.capability
            val detail = inspection?.let { ExperimentalFleetResponseWire.failureDetail(it.overBudgetDetail) } ?: empty
            success(
                "create",
                JniExperimentalFleet.create(
                    empty, byteArrayOf(1, 0), 0,
                    intArrayOf(workers, mailboxCapacity, eventCapacity, guestBudget, maintenanceBudget, hostBudget, advancesPerTick, if (coordinatedTurns) 1 else 0),
                    capability?.namespace?.encodeToByteArray() ?: empty,
                    capability?.name?.encodeToByteArray() ?: empty,
                    detail, capability?.abiMajor ?: 0, capability?.abiMinor ?: 0, inspection?.operation ?: 0, output,
                ),
            )
            return ExperimentalJniFleet(bridge, output[0])
        }

        private fun success(operation: String, status: Int) {
            if (status != 0) throw VmBridgeException("experimental fleet $operation failed with status $status")
        }
    }
}
