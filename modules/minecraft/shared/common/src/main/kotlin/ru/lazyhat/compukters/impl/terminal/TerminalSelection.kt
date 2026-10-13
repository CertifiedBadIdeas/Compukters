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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell

/** A rectangular selection in terminal rows, independent of viewport scrolling. */
class TerminalSelection {
    data class Point(
        val column: Int,
        val row: Long,
    )

    private var anchor: Point? = null
    private var end: Point? = null

    val active: Boolean
        get() = anchor != null

    fun start(point: Point) {
        require(point.column in 0 until TerminalModel.WIDTH && point.row >= 0)
        anchor = point
        end = point
    }

    fun extend(point: Point) {
        if (!active) return
        require(point.column in 0 until TerminalModel.WIDTH && point.row >= 0)
        end = point
    }

    fun clear() {
        anchor = null
        end = null
    }

    fun retain(
        firstRow: Long,
        endRow: Long,
    ) {
        val first = anchor ?: return
        val last = end ?: return
        if (minOf(first.row, last.row) < firstRow || maxOf(first.row, last.row) >= endRow) clear()
    }

    fun contains(
        column: Int,
        row: Long,
    ): Boolean {
        val first = anchor ?: return false
        val last = end ?: return false
        return column in minOf(first.column, last.column)..maxOf(first.column, last.column) &&
            row in minOf(first.row, last.row)..maxOf(first.row, last.row)
    }

    fun copy(rowCells: (Long) -> List<TerminalCell>?): String {
        val first = anchor ?: return ""
        val last = end ?: return ""
        val left = minOf(first.column, last.column)
        val right = maxOf(first.column, last.column)
        return buildString {
            for (row in minOf(first.row, last.row)..maxOf(first.row, last.row)) {
                val cells = rowCells(row) ?: continue
                if (isNotEmpty()) append('\n')
                for (column in left..right) appendCodePoint(cells[column].codePoint)
            }
        }
    }
}
