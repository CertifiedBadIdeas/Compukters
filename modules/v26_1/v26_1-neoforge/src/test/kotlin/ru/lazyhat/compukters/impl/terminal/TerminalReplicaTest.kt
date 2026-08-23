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

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.core.BlockPos
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalReplicaTest {
    @Test
    fun `revision or machine mismatch requests resync without mutation`() {
        val replica = TerminalReplica(TerminalFullPayload(POSITION, 7, state(3), true))
        val before = replica.state

        assertFalse(replica.apply(TerminalDeltaPayload(POSITION, 7, delta(2, 4, TerminalChange.Reset))))
        assertFalse(replica.apply(TerminalDeltaPayload(POSITION, 8, delta(3, 4, TerminalChange.Reset))))
        assertEquals(before, replica.state)
    }

    @Test
    fun `delta applies scroll before patch in exact order`() {
        val cells = MutableList(CELL_COUNT) { cell(' ') }
        cells[0] = cell('A')
        cells[WIDTH] = cell('B')
        val replica = TerminalReplica(TerminalFullPayload(POSITION, 7, state(1, cells), true))
        val update =
            delta(
                1,
                2,
                TerminalChange.Scroll(1, cell('.')),
                TerminalChange.Patch(0, listOf(cell('X'))),
            )

        assertTrue(replica.apply(TerminalDeltaPayload(POSITION, 7, update)))
        assertEquals(cell('X'), replica.state.cells[0])
        assertEquals(cell('.'), replica.state.cells[(HEIGHT - 1) * WIDTH])
        assertEquals(2, replica.state.revision)
    }

    @Test
    fun `invalid later change rejects the whole delta atomically`() {
        val replica = TerminalReplica(TerminalFullPayload(POSITION, 7, state(1), true))
        val before = replica.state
        val update =
            delta(
                1,
                2,
                TerminalChange.Patch(0, listOf(cell('X'))),
                TerminalChange.Fill(50, 18, 2, 1, cell('Y')),
            )

        assertFalse(replica.apply(TerminalDeltaPayload(POSITION, 7, update)))
        assertEquals(before, replica.state)
    }

    @Test
    fun `invalid full state is rejected`() {
        val invalid = state(0).copy(cells = listOf(cell('x')))
        assertFailsWith<IllegalArgumentException> {
            TerminalReplica(TerminalFullPayload(POSITION, 1, invalid, true))
        }
    }

    private companion object {
        const val WIDTH = 51
        const val HEIGHT = 19
        const val CELL_COUNT = WIDTH * HEIGHT
        val POSITION = BlockPos(2, 3, 4)

        fun cell(value: Char): TerminalCell = TerminalCell(value.code, 15, 0)

        fun state(
            revision: Long,
            cells: List<TerminalCell> = List(CELL_COUNT) { cell(' ') },
        ): TerminalState = TerminalState(revision, WIDTH, HEIGHT, cells, TerminalPosition(0, 0), true)

        fun delta(
            base: Long,
            target: Long,
            vararg changes: TerminalChange,
        ): TerminalUpdate.Delta = TerminalUpdate.Delta(base, target, changes.toList())
    }
}
