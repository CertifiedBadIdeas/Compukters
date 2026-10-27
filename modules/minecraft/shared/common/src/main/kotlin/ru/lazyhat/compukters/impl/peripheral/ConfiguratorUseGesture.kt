/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

/** A hold selects a mode; a short click is the only gesture allowed to act on the world. */
class ConfiguratorUseGesture(
    private val holdNanos: Long = 250_000_000L,
) {
    private var pressedAt: Long? = null
    private var opened = false
    val active: Boolean get() = pressedAt != null

    fun press(now: Long) {
        if (active) return
        pressedAt = now
        opened = false
    }

    fun open(now: Long): Boolean {
        val start = pressedAt ?: return false
        if (opened || now - start < holdNanos) return false
        opened = true
        return true
    }

    fun release(now: Long): Boolean {
        val start = pressedAt ?: return false
        val tap = !opened && now - start in 0 until holdNanos
        cancel()
        return tap
    }

    fun cancel() {
        pressedAt = null
        opened = false
    }
}
