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

package ru.lazyhat.compukters.minecraft.computer

class TerminalTranscript(
    private val maximumCodeUnits: Int = DEFAULT_MAXIMUM_CODE_UNITS,
) {
    init {
        require(maximumCodeUnits >= 0) { "maximum transcript code units must not be negative" }
    }

    private val content = CharArray(maximumCodeUnits)
    private var start = 0
    private var size = 0
    private var revision = 0L

    fun append(text: String) {
        if (text.isEmpty() || maximumCodeUnits == 0) return
        val nextSize = minOf(maximumCodeUnits, size + text.length)
        val combinedStart = size + text.length - nextSize
        val changed =
            size != nextSize ||
                (0 until nextSize).any { index ->
                    currentAt(index) != combinedAt(text, combinedStart + index)
                }
        if (text.length >= maximumCodeUnits) {
            text.toCharArray(text.length - maximumCodeUnits, text.length).copyInto(content)
            start = 0
            size = maximumCodeUnits
        } else {
            text.forEach(::appendCodeUnit)
        }
        if (changed) revision++
    }

    fun clear() {
        if (size == 0) return
        start = 0
        size = 0
        revision++
    }

    fun snapshot(): Snapshot = Snapshot(CharArray(size, ::currentAt).concatToString(), revision)

    private fun combinedAt(
        appended: String,
        index: Int,
    ): Char = if (index < size) currentAt(index) else appended[index - size]

    private fun currentAt(index: Int): Char = content[(start + index) % maximumCodeUnits]

    private fun appendCodeUnit(codeUnit: Char) {
        if (size < maximumCodeUnits) {
            content[(start + size) % maximumCodeUnits] = codeUnit
            size++
        } else {
            content[start] = codeUnit
            start = (start + 1) % maximumCodeUnits
        }
    }

    data class Snapshot(
        val text: String,
        val revision: Long,
    )

    companion object {
        const val DEFAULT_MAXIMUM_CODE_UNITS = 32 * 1024
    }
}
