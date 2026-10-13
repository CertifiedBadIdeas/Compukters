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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSelectionTest {
    @Test
    fun `reverse drag selects exactly the rectangle including supplementary Unicode and spaces`() {
        val selection = TerminalSelection()
        selection.start(TerminalSelection.Point(3, 11))
        selection.extend(TerminalSelection.Point(1, 10))
        val rows = mapOf(10L to row("a😀 c"), 11L to row("abcd"))
        assertEquals("😀 c\nbcd", selection.copy(rows::get))
        assertTrue(selection.contains(2, 10))
        assertFalse(selection.contains(0, 10))
        assertFalse(selection.contains(2, 12))
    }

    @Test
    fun `single cell can be copied and clearing removes selection`() {
        val selection = TerminalSelection()
        selection.start(TerminalSelection.Point(0, 0))
        assertEquals("x", selection.copy { row("x") })
        selection.clear()
        assertFalse(selection.active)
        assertEquals("", selection.copy { row("x") })
    }

    private fun row(text: String): List<TerminalCell> =
        text.codePoints().toArray().map { TerminalCell(it, 15, 0) } +
            List(TerminalModel.WIDTH - text.codePointCount(0, text.length)) { TerminalCell(32, 15, 0) }
}
