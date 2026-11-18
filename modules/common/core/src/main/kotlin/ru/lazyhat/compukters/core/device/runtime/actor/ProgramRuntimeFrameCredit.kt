/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

internal const val MAXIMUM_CONTINUATIONS_PER_FRAME = 16

/** A single computer's share of one server frame. Access is serialized by the owner. */
internal class ProgramRuntimeFrameCredit(
    val worldTick: Long,
    var remaining: Long,
    val deadlineNanos: Long,
    var inFlight: Boolean,
    var continuations: Int = 0,
) {
    private var retired = 0L

    /** Settle the retirements consumed by one turn. */
    fun finishTurn(
        delta: Long,
        allowance: Long,
    ): Long {
        check(inFlight) { "computer has no instruction reservation in flight" }
        check(delta in 0..allowance && delta <= remaining) { "actor exceeded its instruction reservation" }
        retired = Math.addExact(retired, delta)
        remaining -= delta
        inFlight = false
        return delta
    }

    /** Native fleet reports the cumulative retirements in this tick, including previous turns. */
    fun finishCumulative(
        total: Long,
        allowance: Long,
    ): Long {
        check(total >= retired) { "native retirement count moved backwards" }
        return finishTurn(total - retired, allowance)
    }
}
