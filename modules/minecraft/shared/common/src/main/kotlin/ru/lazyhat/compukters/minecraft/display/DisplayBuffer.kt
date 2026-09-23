/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import java.nio.charset.StandardCharsets

internal class DisplayBuffer {
    private val cells = IntArray(WIDTH * HEIGHT) { ' '.code }
    private var writer: Writer? = null

    var revision: Long = 0
        private set

    fun writeAt(
        owner: Any,
        stillConnected: () -> Boolean,
        x: Int,
        y: Int,
        text: String,
    ): DisplayWriteResult {
        val codePoints = validateWrite(x, y, text) ?: return DisplayWriteResult.INVALID
        expireWriter()
        if (writer != null && writer?.owner !== owner) return DisplayWriteResult.BUSY
        if (!stillConnected()) return DisplayWriteResult.DISCONNECTED
        if (writer == null) writer = Writer(owner, stillConnected)
        var changed = false
        codePoints.forEachIndexed { offset, codePoint ->
            val cell = y * WIDTH + x + offset
            if (cells[cell] != codePoint) {
                cells[cell] = codePoint
                changed = true
            }
        }
        if (changed) revision++
        return DisplayWriteResult.SUCCESS
    }

    fun clear(
        owner: Any,
        stillConnected: () -> Boolean,
    ): DisplayWriteResult {
        expireWriter()
        if (writer != null && writer?.owner !== owner) return DisplayWriteResult.BUSY
        if (!stillConnected()) return DisplayWriteResult.DISCONNECTED
        if (writer == null) writer = Writer(owner, stillConnected)
        clearCells()
        return DisplayWriteResult.SUCCESS
    }

    fun release(owner: Any) {
        if (writer?.owner === owner) {
            writer = null
            clearCells()
        }
    }

    fun tick() {
        expireWriter()
    }

    fun rows(): List<String> =
        List(HEIGHT) { y ->
            buildString {
                repeat(WIDTH) { x -> appendCodePoint(cells[y * WIDTH + x]) }
            }
        }

    fun applySnapshot(rows: List<String>): Boolean {
        if (rows.size != HEIGHT) return false
        val decoded =
            rows.map { row ->
                if (row.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_WRITE_BYTES) return false
                row.codePoints().toArray().takeIf { codePoints ->
                    codePoints.size == WIDTH && codePoints.none { it in 0xD800..0xDFFF || Character.isISOControl(it) }
                } ?: return false
            }
        decoded.forEachIndexed { y, row -> row.forEachIndexed { x, codePoint -> cells[y * WIDTH + x] = codePoint } }
        revision++
        return true
    }

    private fun expireWriter() {
        val current = writer ?: return
        if (!current.stillConnected()) release(current.owner)
    }

    private fun clearCells() {
        if (cells.any { it != ' '.code }) {
            cells.fill(' '.code)
            revision++
        }
    }

    private fun validateWrite(
        x: Int,
        y: Int,
        text: String,
    ): IntArray? {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return null
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_WRITE_BYTES) return null
        val codePoints = text.codePoints().toArray()
        if (codePoints.size > WIDTH - x) return null
        if (codePoints.any { it in 0xD800..0xDFFF || Character.isISOControl(it) }) return null
        return codePoints
    }

    private class Writer(
        val owner: Any,
        val stillConnected: () -> Boolean,
    )

    companion object {
        const val WIDTH = 20
        const val HEIGHT = 10
        const val MAXIMUM_WRITE_BYTES = 256
    }
}

internal enum class DisplayWriteResult {
    SUCCESS,
    BUSY,
    DISCONNECTED,
    INVALID,
}
