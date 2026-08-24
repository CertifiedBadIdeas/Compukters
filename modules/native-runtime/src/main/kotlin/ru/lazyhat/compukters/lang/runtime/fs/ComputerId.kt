/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.fs

import java.nio.ByteBuffer
import java.nio.ByteOrder

@ConsistentCopyVisibility
data class ComputerId private constructor(
    val highBits: Long,
    val lowBits: Long,
) {
    fun toByteArray(): ByteArray =
        ByteBuffer
            .allocate(BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(highBits)
            .putLong(lowBits)
            .array()

    companion object {
        private const val BYTES = 16

        fun of(bytes: ByteArray): ComputerId {
            require(bytes.size == BYTES) { "computer identity must contain exactly 16 bytes" }
            require(bytes.any { it.toInt() != 0 }) { "computer identity must not be zero" }
            val buffer = ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.BIG_ENDIAN)
            return ComputerId(buffer.long, buffer.long)
        }

        fun fromLongs(
            highBits: Long,
            lowBits: Long,
        ): ComputerId {
            require(highBits != 0L || lowBits != 0L) { "computer identity must not be zero" }
            return ComputerId(highBits, lowBits)
        }
    }
}
