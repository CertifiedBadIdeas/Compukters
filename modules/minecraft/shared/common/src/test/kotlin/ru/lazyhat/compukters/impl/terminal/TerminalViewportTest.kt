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
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalViewportTest {
    @Test
    fun `history navigation remains anchored while output scrolls and resumes live on input`() {
        val replica = TerminalReplica(state())
        replica.viewport.scroll(2)
        assertEquals(0, replica.viewport.topRow)
        assertFalse(replica.viewport.state().cursorVisible)
        assertEquals(
            'A'.code,
            replica.viewport
                .state()
                .cells[0]
                .codePoint,
        )
        assertTrue(
            replica.apply(
                TerminalUpdate.Delta(
                    1,
                    2,
                    listOf(
                        TerminalChange.HistoryAppend(2, row('C')),
                        TerminalChange.Scroll(1, cell(' ')),
                    ),
                ),
            ),
        )
        assertEquals(0, replica.viewport.topRow)
        assertEquals(3, replica.viewport.rowsBehind)
        replica.viewport.input()
        assertTrue(replica.viewport.live)
        assertEquals(3, replica.viewport.topRow)
    }

    @Test
    fun `drag copy is local and Ctrl C without selection is passed to the program`() {
        val replica = TerminalReplica(state())
        val view = replica.viewport
        val geometry = TerminalGridGeometry(20, 30)
        assertTrue(view.press(geometry, 20.0, 30.0, 0))
        assertTrue(view.drag(geometry, 20.0 + TerminalFontProfile.cellWidth, 30.0))
        assertTrue(view.release())
        var copied = ""
        assertTrue(view.key(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL) { copied = it })
        assertEquals("CC", copied)
        assertTrue(view.key(GLFW.GLFW_KEY_ESCAPE, 0) {})
        assertFalse(view.key(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL) {})
        assertFalse(view.key(GLFW.GLFW_KEY_PAGE_UP, 0) {})
        assertTrue(view.key(GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_MOD_SHIFT) {})
        assertEquals(0, view.topRow)
    }

    @Test
    fun `invalid history rejects delta atomically and eviction clamps browsing and clears expired selection`() {
        val replica = TerminalReplica(state())
        val before = replica.state
        assertFalse(
            replica.apply(
                TerminalUpdate.Delta(
                    1,
                    2,
                    listOf(
                        TerminalChange.Patch(0, listOf(cell('X'))),
                        TerminalChange.HistoryAppend(999, row('Y')),
                    ),
                ),
            ),
        )
        assertEquals(before, replica.state)
        replica.viewport.scroll(2)
        replica.viewport.selection.start(TerminalSelection.Point(0, 0))
        assertTrue(replica.replace(state().copy(revision = 3, historyStart = 10)))
        assertEquals(10, replica.viewport.topRow)
        assertFalse(replica.viewport.selection.active)
    }

    @Test
    fun `complete snapshots retain history and append evicts oldest complete rows`() {
        val replica = TerminalReplica(state().copy(historyCells = List(512 * 51) { cell('A') }))
        assertTrue(replica.apply(TerminalUpdate.Delta(1, 2, listOf(TerminalChange.HistoryAppend(512, row('Z'))))))
        assertEquals(1, replica.state.historyStart)
        assertEquals(512 * 51, replica.state.historyCells.size)
        assertEquals(
            'Z'.code,
            replica.state.historyCells
                .last()
                .codePoint,
        )
    }

    private fun state() =
        TerminalState(
            1,
            51,
            19,
            List(51 * 19) { cell('C') },
            TerminalPosition(0, 0),
            true,
            historyCells = row('A') + row('B'),
        )

    private fun cell(value: Char) = TerminalCell(value.code, 15, 0)

    private fun row(value: Char) = List(51) { cell(value) }
}
