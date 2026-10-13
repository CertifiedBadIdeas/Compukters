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

package ru.lazyhat.compukters.impl.terminal

import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import kotlin.math.floor

/** Client presentation only. Cells and history remain authoritative replicated VM state. */
class TerminalViewport(
    private val source: () -> TerminalState,
) {
    val selection = TerminalSelection()
    private var firstRow: Long? = null
    private var dragging = false
    private var wheelRemainder = 0.0

    val live: Boolean
        get() = firstRow == null

    val rowsBehind: Long
        get() = liveRow(source()) - topRow

    val topRow: Long
        get() {
            synchronize()
            return firstRow ?: liveRow(source())
        }

    fun state(): TerminalState {
        val current = source()
        synchronize()
        val top = firstRow ?: return current
        return current.copy(
            cells =
                List(TerminalModel.CELL_COUNT) { index ->
                    checkNotNull(row(top + index / TerminalModel.WIDTH))[index % TerminalModel.WIDTH]
                },
            cursorVisible = false,
        )
    }

    fun reset() {
        firstRow = null
        wheelRemainder = 0.0
        release()
        selection.clear()
    }

    fun input() {
        wheelRemainder = 0.0
        firstRow = null
        selection.clear()
        release()
    }

    fun scroll(rows: Int) {
        val current = source()
        val next = (topRow - rows.toLong()).coerceIn(current.historyStart, liveRow(current))
        firstRow = next.takeUnless { it == liveRow(current) }
    }

    fun wheel(amount: Double) {
        if (!amount.isFinite()) return
        wheelRemainder += amount * 3
        val rows = if (wheelRemainder >= 0) floor(wheelRemainder) else -floor(-wheelRemainder)
        wheelRemainder -= rows
        scroll(rows.toInt())
    }

    fun press(
        geometry: TerminalGridGeometry,
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        if (button != 0 || !inside(geometry.bounds, x, y)) return false
        selection.start(point(geometry, x, y))
        dragging = true
        return true
    }

    fun drag(
        geometry: TerminalGridGeometry,
        x: Double,
        y: Double,
    ): Boolean {
        if (!dragging) return false
        selection.extend(point(geometry, x, y))
        return true
    }

    fun release(): Boolean {
        val handled = dragging
        dragging = false
        return handled
    }

    fun key(
        keyCode: Int,
        modifiers: Int,
        copy: (String) -> Unit,
    ): Boolean {
        synchronize()
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        val control = modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SUPER) != 0
        if (control && keyCode == GLFW.GLFW_KEY_C && (shift || selection.active)) {
            if (selection.active) copy(selection.copy(::row))
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && selection.active) {
            selection.clear()
            release()
            return true
        }
        if (shift) {
            when (keyCode) {
                GLFW.GLFW_KEY_PAGE_UP -> scroll(TerminalModel.HEIGHT - 1)
                GLFW.GLFW_KEY_PAGE_DOWN -> scroll(1 - TerminalModel.HEIGHT)
                GLFW.GLFW_KEY_HOME -> firstRow = source().historyStart.takeUnless { it == liveRow(source()) }
                GLFW.GLFW_KEY_END -> firstRow = null
                else -> return false
            }
            return true
        }
        return false
    }

    fun selectionBounds(geometry: TerminalGridGeometry): List<TerminalRect> {
        val top = topRow
        if (!selection.active) return emptyList()
        return buildList {
            for (y in 0 until TerminalModel.HEIGHT) {
                val columns = (0 until TerminalModel.WIDTH).filter { selection.contains(it, top + y) }
                if (columns.isNotEmpty()) {
                    val left = geometry.cell(columns.first(), y)
                    val right = geometry.cell(columns.last(), y)
                    add(TerminalRect(left.left, left.top, right.right, right.bottom))
                }
            }
        }
    }

    private fun row(id: Long): List<TerminalCell>? {
        val current = source()
        val offset = id - current.historyStart
        val historyRows = current.historyCells.size / TerminalModel.WIDTH
        if (offset !in 0 until (historyRows + TerminalModel.HEIGHT).toLong()) return null
        val cells = if (offset < historyRows) current.historyCells else current.cells
        val index = if (offset < historyRows) offset.toInt() else (offset - historyRows).toInt()
        return cells.subList(index * TerminalModel.WIDTH, (index + 1) * TerminalModel.WIDTH)
    }

    private fun synchronize() {
        val current = source()
        firstRow = firstRow?.coerceIn(current.historyStart, liveRow(current))?.takeUnless { it == liveRow(current) }
        selection.retain(current.historyStart, liveRow(current) + TerminalModel.HEIGHT)
    }

    private fun point(
        geometry: TerminalGridGeometry,
        x: Double,
        y: Double,
    ): TerminalSelection.Point =
        TerminalSelection.Point(
            floor((x - geometry.bounds.left) / TerminalFontProfile.cellWidth).toInt().coerceIn(0, TerminalModel.WIDTH - 1),
            topRow + floor((y - geometry.bounds.top) / TerminalFontProfile.cellHeight).toInt().coerceIn(0, TerminalModel.HEIGHT - 1),
        )

    companion object {
        fun inside(
            bounds: TerminalRect,
            x: Double,
            y: Double,
        ): Boolean = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom

        private fun liveRow(state: TerminalState): Long = state.historyStart + state.historyCells.size / TerminalModel.WIDTH
    }
}
