/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.input

/** Server-owner admission: eight immediate updates and at most one coalesced drain per tick. */
class KeyboardInputGate(
    private val mask: Long,
) {
    data class Admission(
        val accepted: Boolean,
        val ready: KeyboardInputUpdate?,
    )

    private var sequence = -1L
    private var tick = Long.MIN_VALUE
    private var immediate = 0
    private var drained = false
    private var pending: KeyboardInputUpdate? = null

    fun offer(
        update: KeyboardInputUpdate,
        now: Long,
    ): Admission {
        if (update.sequence < 0 || update.sequence <= sequence || now < tick ||
            (update.keys or update.pressed or update.released) and mask.inv() != 0L
        ) {
            return Admission(false, null)
        }
        enterTick(now)
        sequence = update.sequence
        val old = pending
        val merged =
            if (old == null) {
                update
            } else {
                update.copy(
                    pressed = old.pressed or update.pressed or (update.keys and old.keys.inv()),
                    released = old.released or update.released or (old.keys and update.keys.inv()),
                )
            }
        if (immediate < MAX_IMMEDIATE_PER_TICK) {
            immediate++
            pending = null
            return Admission(true, merged)
        }
        pending = merged
        return Admission(true, null)
    }

    fun drain(now: Long): KeyboardInputUpdate? {
        if (now < tick) return null
        enterTick(now)
        if (drained) return null
        drained = true
        return pending.also { pending = null }
    }

    fun reset() {
        sequence = -1
        tick = Long.MIN_VALUE
        immediate = 0
        drained = false
        pending = null
    }

    private fun enterTick(now: Long) {
        if (now != tick) {
            tick = now
            immediate = 0
            drained = false
        }
    }

    companion object {
        const val MAX_IMMEDIATE_PER_TICK = 8
    }
}
