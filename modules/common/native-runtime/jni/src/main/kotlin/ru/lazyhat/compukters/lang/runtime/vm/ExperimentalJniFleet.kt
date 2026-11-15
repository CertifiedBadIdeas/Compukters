/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.file.Path

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

    @Synchronized
    fun grant(machine: Long, tick: Long, retirements: Int, deadlineNanos: Long = -1) =
        success("grant", JniExperimentalFleet.grant(active(), machine, tick, retirements, deadlineNanos))

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
        fun open(library: Path, workers: Int, mailboxCapacity: Int, eventCapacity: Int, guestBudget: Int, maintenanceBudget: Int, hostBudget: Int, advancesPerTick: Int): ExperimentalJniFleet {
            val bridge = JniBridge.open(library)
            val output = LongArray(1)
            val empty = byteArrayOf()
            success("create", JniExperimentalFleet.create(empty, byteArrayOf(1, 0), 0, intArrayOf(workers, mailboxCapacity, eventCapacity, guestBudget, maintenanceBudget, hostBudget, advancesPerTick), empty, empty, empty, 0, 0, 0, output))
            return ExperimentalJniFleet(bridge, output[0])
        }

        private fun success(operation: String, status: Int) {
            if (status != 0) throw VmBridgeException("experimental fleet $operation failed with status $status")
        }
    }
}
