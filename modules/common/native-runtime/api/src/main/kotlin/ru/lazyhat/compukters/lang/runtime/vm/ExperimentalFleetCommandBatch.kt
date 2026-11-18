/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Source-matched commands_v1 capability; both production transports implement it. */
interface ExperimentalFleetCommandTransport : ExperimentalFleet {
    fun submitCommands(batch: ExperimentalFleetCommandBatch)
}

/** One caller-owned, reusable direct buffer. Access and submission must be serialized by its owner. */
class ExperimentalFleetCommandBatch {
    /** Transport-only borrowed memory; native calls must not retain it or its result region. */
    val transportBuffer: ByteBuffer = ByteBuffer.allocateDirect(INPUT_BYTES + MAXIMUM_COMMANDS).order(ByteOrder.LITTLE_ENDIAN)
    var size: Int = 0
        private set
    val inputLength: Int get() = 8 + size * RECORD_BYTES
    private var prepared = false

    init {
        clear()
    }

    fun clear() {
        size = 0
        prepared = false
        transportBuffer.putInt(0, VERSION).putInt(4, 0)
        for (index in 0 until MAXIMUM_COMMANDS) transportBuffer.put(INPUT_BYTES + index, NOT_ATTEMPTED.toByte())
    }

    fun grant(
        machine: Long,
        tick: Long,
        retirements: Int,
        deadlineNanos: Long,
    ) {
        require(tick >= 0 && retirements >= 0)
        append(machine, 0, retirements, tick, deadlineNanos)
    }

    fun grantFrame(machine: Long, tick: Long, retirements: Int, executionNanos: Long, authorizedUntilNanos: Long) {
        require(tick >= 0 && retirements >= 0 && executionNanos >= 0)
        append(machine, 4, retirements, tick, executionNanos)
        transportBuffer.putLong(8 + (size - 1) * RECORD_BYTES + 32, authorizedUntilNanos)
    }

    fun resolveCost(
        machine: Long,
        identity: VmHostRequestIdentity,
        units: Int,
    ) {
        require(units > 0)
        append(machine, 1, identity.taskId, identity.requestId, units.toLong())
    }

    fun redstoneInput(
        machine: Long,
        packet: Int,
    ) {
        require(packet >= 0 && packet and 0x3fffffff == packet)
        append(machine, 2, packet, 0, 0)
    }

    fun confirmRedstoneOutput(
        machine: Long,
        packed: Int,
    ) {
        require(packed >= 0 && packed and 0x3fffffff == packed)
        append(machine, 3, packed, 0, 0)
    }

    private fun append(
        machine: Long,
        kind: Int,
        argument: Int,
        value: Long,
        extra: Long,
    ) {
        require(machine >= 0)
        check(!prepared && size < MAXIMUM_COMMANDS)
        val offset = inputLength
        transportBuffer
            .putLong(offset, machine)
            .putInt(offset + 8, kind)
            .putInt(offset + 12, argument)
            .putLong(offset + 16, value)
            .putLong(offset + 24, extra)
            .putLong(offset + 32, 0)
        size++
        transportBuffer.putInt(4, size)
    }

    /** Convert deadlines immediately before the synchronous call, rather than extending them while encoding. */
    fun prepare(nowNanos: Long = System.nanoTime()) {
        check(!prepared && size > 0)
        for (index in 0 until size) {
            val offset = 8 + index * RECORD_BYTES
            if (transportBuffer.getInt(offset + 8) == 0) {
                val deadline = transportBuffer.getLong(offset + 24)
                val remaining = if (deadline == Long.MAX_VALUE) -1L else (deadline - nowNanos).coerceAtLeast(0)
                transportBuffer.putLong(offset + 24, remaining)
            } else if (transportBuffer.getInt(offset + 8) == 4) {
                val deadline = transportBuffer.getLong(offset + 32)
                transportBuffer.putLong(offset + 32, (deadline - nowNanos).coerceAtLeast(0))
            }
        }
        prepared = true
    }

    fun result(index: Int): Int {
        require(index in 0 until size)
        return transportBuffer.get(INPUT_BYTES + index).toInt() and 255
    }

    companion object {
        const val VERSION = 1
        const val MAXIMUM_COMMANDS = 16
        const val RECORD_BYTES = 40
        const val INPUT_BYTES = 8 + MAXIMUM_COMMANDS * RECORD_BYTES
        const val NOT_ATTEMPTED = 255
    }
}
