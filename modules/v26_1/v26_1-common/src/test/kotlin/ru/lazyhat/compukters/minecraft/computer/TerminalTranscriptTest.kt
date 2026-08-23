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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalTranscriptTest {
    @Test
    fun `overflow keeps newest UTF-16 code units and revision follows visible changes`() {
        val transcript = TerminalTranscript(maximumCodeUnits = 4)
        assertEquals(TerminalTranscript.Snapshot("", 0), transcript.snapshot())

        transcript.append("ABCD")
        assertEquals(TerminalTranscript.Snapshot("ABCD", 1), transcript.snapshot())
        transcript.append("EF")
        assertEquals(TerminalTranscript.Snapshot("CDEF", 2), transcript.snapshot())
        transcript.clear()
        assertEquals(TerminalTranscript.Snapshot("", 3), transcript.snapshot())
        transcript.clear()
        assertEquals(TerminalTranscript.Snapshot("", 3), transcript.snapshot())
    }

    @Test
    fun `append preserves surrogate code units without normalization`() {
        val transcript = TerminalTranscript(maximumCodeUnits = 2)

        transcript.append("\uD83D\uDE00")
        assertEquals("😀", transcript.snapshot().text)
        transcript.append("X")

        assertEquals("\uDE00X", transcript.snapshot().text)
        assertEquals(2, transcript.snapshot().text.length)
    }

    @Test
    fun `empty appends and invisible zero-capacity appends do not change revision`() {
        val zero = TerminalTranscript(maximumCodeUnits = 0)
        zero.append("ignored")
        zero.append("")
        assertEquals(TerminalTranscript.Snapshot("", 0), zero.snapshot())

        val transcript = TerminalTranscript(maximumCodeUnits = 2)
        transcript.append("")
        assertEquals(TerminalTranscript.Snapshot("", 0), transcript.snapshot())
    }

    @Test
    fun `input larger than capacity keeps only its newest suffix`() {
        val transcript = TerminalTranscript(maximumCodeUnits = 3)

        transcript.append("abcdef")

        assertEquals(TerminalTranscript.Snapshot("def", 1), transcript.snapshot())
    }

    @Test
    fun `append that leaves the visible suffix unchanged preserves revision`() {
        val transcript = TerminalTranscript(maximumCodeUnits = 3)
        transcript.append("aaa")

        transcript.append("a")

        assertEquals(TerminalTranscript.Snapshot("aaa", 1), transcript.snapshot())
    }

    @Test
    fun `negative capacity is rejected`() {
        assertFailsWith<IllegalArgumentException> { TerminalTranscript(maximumCodeUnits = -1) }
    }
}
