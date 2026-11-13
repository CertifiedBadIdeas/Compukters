/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

/** Buffers are leased exclusively and shared by sessions using the same bridge. */
internal class JniAdvanceScratchPool(
    private val maximumBytes: Int,
    private val maximumRetainedBuffers: Int = 8,
) {
    private val idle = ArrayDeque<JniAdvanceScratch>()

    init {
        require(maximumBytes > 0)
        require(maximumRetainedBuffers > 0)
    }

    fun <T> withScratch(block: (JniAdvanceScratch) -> T): T {
        val scratch = synchronized(idle) { idle.removeLastOrNull() } ?: JniAdvanceScratch(maximumBytes)
        try {
            scratch.written[0] = 0
            scratch.retired[0] = 0
            return block(scratch)
        } finally {
            synchronized(idle) {
                if (idle.size < maximumRetainedBuffers) idle.addLast(scratch)
            }
        }
    }
}

internal class JniAdvanceScratch(
    maximumBytes: Int,
) {
    val output = ByteArray(maximumBytes)
    val written = LongArray(1)
    val retired = LongArray(1)
}
