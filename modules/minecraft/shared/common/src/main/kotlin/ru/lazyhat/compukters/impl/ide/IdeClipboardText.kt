/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.IdeClientLimits

internal object IdeClipboardText {
    fun bounded(
        value: String,
        limits: IdeClientLimits,
    ): String {
        val result = StringBuilder(minOf(value.length, limits.clipboardCodeUnits))
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val first = value[offset]
            val validPair =
                Character.isHighSurrogate(first) && offset + 1 < value.length && Character.isLowSurrogate(value[offset + 1])
            val codePoint =
                when {
                    validPair -> Character.toCodePoint(first, value[offset + 1])
                    Character.isSurrogate(first) -> 0xfffd
                    else -> first.code
                }
            val inputUnits = if (validPair) 2 else 1
            val outputUnits = Character.charCount(codePoint)
            val outputBytes = utf8Bytes(codePoint)
            if (result.length + outputUnits > limits.clipboardCodeUnits || bytes + outputBytes > limits.clipboardUtf8Bytes) break
            result.appendCodePoint(codePoint)
            bytes += outputBytes
            offset += inputUnits
        }
        return result.toString()
    }

    private fun utf8Bytes(codePoint: Int): Int =
        when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
}
