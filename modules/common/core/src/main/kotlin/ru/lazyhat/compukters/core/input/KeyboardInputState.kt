/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Transient physical input. Edges coalesce until the owning program reads its next snapshot. */
class KeyboardInputState(
    val mask: Long,
) {
    init {
        require(mask != 0L)
    }

    var connected: Boolean = false
        private set
    private var held = 0L
    private var pressed = 0L
    private var released = 0L
    private var lastSequence = -1L
    private var heartbeat = 0L

    fun connect(tick: Long) {
        connected = true
        held = 0L
        pressed = 0L
        released = 0L
        lastSequence = -1L
        heartbeat = tick
    }

    fun update(
        sequence: Long,
        keys: Long,
        tick: Long,
    ): Boolean {
        if (!connected || sequence < 0 || sequence <= lastSequence || tick < heartbeat || keys and mask.inv() != 0L) return false
        pressed = pressed or (keys and held.inv())
        released = released or (held and keys.inv())
        held = keys
        lastSequence = sequence
        heartbeat = tick
        return true
    }

    fun disconnect() {
        released = released or held
        pressed = 0L
        held = 0L
        connected = false
    }

    fun expire(tick: Long) {
        if (connected && tick - heartbeat !in 0..5) disconnect()
    }

    fun poll(tick: Long): KeyboardFrame {
        expire(tick)
        return KeyboardFrame(tick, connected, held, pressed, released).also {
            pressed = 0L
            released = 0L
        }
    }
}

data class KeyboardFrame(
    val tick: Long,
    val connected: Boolean,
    val held: Long,
    val pressed: Long,
    val released: Long,
) {
    /** Version 1: version, connected, then tick/held/pressed/released as little-endian I64. */
    fun encode(): ByteArray =
        ByteBuffer
            .allocate(34)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(1)
            .put(if (connected) 1.toByte() else 0.toByte())
            .putLong(tick)
            .putLong(held)
            .putLong(pressed)
            .putLong(released)
            .array()
}
