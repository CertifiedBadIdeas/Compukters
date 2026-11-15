/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Events from the opt-in native fleet transport. Machine identities are local to a fleet. */
sealed interface ExperimentalFleetEvent {
    data class Outcome(val machine: Long, val tick: Long, val outcome: VmOutcome) : ExperimentalFleetEvent
    data class Inspection(val machine: Long, val tick: Long, val request: VmHostRequest) : ExperimentalFleetEvent
    data class Stopped(val machine: Long, val pending: List<VmHostRequestIdentity>) : ExperimentalFleetEvent
    data class ScopeStopped(val machine: Long, val scope: Long, val pending: List<VmHostRequestIdentity>) : ExperimentalFleetEvent
    data class Detached(val machine: Long) : ExperimentalFleetEvent
    data class DetachRejected(val machine: Long) : ExperimentalFleetEvent
    data class Error(val machine: Long?, val message: String) : ExperimentalFleetEvent
}

/** Checks lengths and identities before an event is handed to the world-side dispatcher. */
object ExperimentalFleetEventWire {
    fun decode(bytes: ByteArray): ExperimentalFleetEvent {
        require(bytes.isNotEmpty() && bytes.size <= 1_048_576) { "invalid fleet event size" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun long(): Long = input.long.also { require(it >= 0) { "invalid fleet identity or tick" } }
        fun identities(): List<VmHostRequestIdentity> {
            val count = input.int
            require(count >= 0 && count <= input.remaining() / 12 && input.remaining() == count * 12) { "invalid fleet cancellation length" }
            val items = List(count) { VmHostRequestIdentity(input.int, input.long) }
            require(items.toSet().size == items.size) { "duplicate fleet cancellation identity" }
            return items
        }
        return when (input.get().toInt() and 0xff) {
            0, 1 -> {
                val tag = bytes[0].toInt()
                val machine = long()
                val tick = long()
                val outcome = VmOutcomeWire.decode(ByteArray(input.remaining()).also(input::get))
                if (tag == 0) ExperimentalFleetEvent.Outcome(machine, tick, outcome)
                else {
                    require(outcome is VmOutcome.HostRequestBatch && outcome.requests.size == 1) { "invalid fleet inspection" }
                    ExperimentalFleetEvent.Inspection(machine, tick, outcome.requests.single())
                }
            }
            2 -> ExperimentalFleetEvent.Stopped(long(), identities())
            4 -> ExperimentalFleetEvent.ScopeStopped(long(), long(), identities())
            6, 7 -> {
                val machine = long()
                require(!input.hasRemaining()) { "trailing fleet detach bytes" }
                if (bytes[0].toInt() == 6) ExperimentalFleetEvent.Detached(machine) else ExperimentalFleetEvent.DetachRejected(machine)
            }
            3, 5 -> {
                val machine = if (bytes[0].toInt() == 3) long() else null
                val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                ExperimentalFleetEvent.Error(machine, decoder.decode(input).toString())
            }
            else -> throw IllegalArgumentException("invalid fleet event tag")
        }
    }
}

/** The transfer operations exchange opaque handles; the native machine has exactly one owner. */
interface ExperimentalFleetSessionOwner {
    fun adopt(bridge: LowLevelVmBridge, session: Long): Long
    fun recover(machine: Long): ExperimentalRestoredSession
}

data class ExperimentalRestoredSession(val bridge: LowLevelVmBridge, val handle: Long)

/** Uses the same checked host-response encoding as ordinary VM sessions. */
object ExperimentalFleetResponseWire {
    fun success(response: ru.lazyhat.compukters.lang.runtime.capability.HostResponse): ByteArray =
        ru.lazyhat.compukters.lang.runtime.capability.HostResponseWire.encode(response)

    fun failureKind(kind: HostFailureKind): Int = kind.wireCode

    fun failureDetail(detail: String): ByteArray = encodeHostFailureDetail(detail)
}
