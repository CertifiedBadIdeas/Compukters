/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.input

/** One immutable wire sample; edges can include a complete tap whose final held bit is clear. */
data class KeyboardInputUpdate(
    val sequence: Long,
    val keys: Long,
    val pressed: Long,
    val released: Long,
)

/** Client-owner state. Normal transitions send immediately; bursts retain one bounded pending sample. */
class KeyboardInputSender(
    private val mask: Long,
    private val publish: (KeyboardInputUpdate) -> Unit,
) {
    private val physical = mutableSetOf<Int>()
    private var held = 0L
    private var pressed = 0L
    private var released = 0L
    private var sequence = 0L
    private var sent = 0
    private var ticksWithoutSend = 0

    init {
        require(mask != 0L)
    }

    fun keyDown(code: Int) {
        val bit = bit(code)
        if (bit == 0L || !physical.add(code)) return
        update(held or bit)
    }

    fun keyUp(code: Int) {
        val bit = bit(code)
        if (bit == 0L || !physical.remove(code)) return
        // Left and right Shift/Ctrl/Alt share one Guest key but retain separate physical state.
        if (physical.none { bit(it) == bit }) update(held and bit.inv())
    }

    fun tick(down: Set<Int>) {
        sent = 0
        ticksWithoutSend++
        physical.clear()
        var sampled = 0L
        for (code in down) {
            val bit = bit(code)
            if (bit != 0L) {
                physical.add(code)
                sampled = sampled or bit
            }
        }
        update(sampled)
        if (pressed != 0L || released != 0L || ticksWithoutSend >= HEARTBEAT_TICKS) flush()
    }

    private fun bit(code: Int): Long {
        val index = KeyboardKeys.index(code)
        return if (index >= 0) (1L shl index) and mask else 0L
    }

    private fun update(keys: Long) {
        pressed = pressed or (keys and held.inv())
        released = released or (held and keys.inv())
        held = keys
        if (pressed != 0L || released != 0L) flush()
    }

    private fun flush() {
        if (sent >= MAX_UPDATES_PER_TICK) return
        publish(KeyboardInputUpdate(sequence++, held, pressed, released))
        sent++
        ticksWithoutSend = 0
        pressed = 0
        released = 0
    }

    companion object {
        const val MAX_UPDATES_PER_TICK = 8
        const val HEARTBEAT_TICKS = 2
    }
}
