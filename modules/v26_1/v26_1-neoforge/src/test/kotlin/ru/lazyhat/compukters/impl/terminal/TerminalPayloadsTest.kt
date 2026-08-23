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

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPayloadsTest {
    @Test
    fun `bounded full and ordered delta payloads round trip`() {
        val full = TerminalFullPayload(POSITION, 9, terminalState(3), openScreen = true)
        assertEquals(full, roundTrip(TerminalFullPayload.STREAM_CODEC, full))

        val delta =
            TerminalDeltaPayload(
                POSITION,
                9,
                TerminalUpdate.Delta(
                    3,
                    4,
                    listOf(
                        TerminalChange.Scroll(1, cell(' ')),
                        TerminalChange.Patch(0, listOf(cell('λ'), cell(0x1f600))),
                        TerminalChange.Cursor(TerminalPosition(2, 0), visible = true),
                    ),
                ),
            )
        assertEquals(delta, roundTrip(TerminalDeltaPayload.STREAM_CODEC, delta))
    }

    @Test
    fun `stable key and atomic Unicode text payloads round trip`() {
        val key =
            TerminalKeyPayload(
                POSITION,
                9,
                TerminalKey.F12,
                TerminalKeyAction.REPEAT,
                setOf(TerminalModifier.SHIFT, TerminalModifier.CONTROL),
            )
        assertEquals(key, roundTrip(TerminalKeyPayload.STREAM_CODEC, key))

        val text = TerminalTextPayload(POSITION, 9, "λ😀")
        assertEquals(text, roundTrip(TerminalTextPayload.STREAM_CODEC, text))
    }

    @Test
    fun `invalid scalar palette rectangle and count are rejected before publication`() {
        val invalidScalar = terminalState(0).copy(cells = List(CELL_COUNT) { cell(0xd800) })
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidScalar, false))
        }
        val invalidPalette = terminalState(0).copy(cells = List(CELL_COUNT) { TerminalCell(' '.code, 16, 0) })
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidPalette, false))
        }
        val invalidRectangle =
            TerminalDeltaPayload(
                POSITION,
                1,
                TerminalUpdate.Delta(0, 1, listOf(TerminalChange.Fill(50, 18, 2, 1, cell('x')))),
            )
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalDeltaPayload.STREAM_CODEC, invalidRectangle)
        }
        val invalidCount = terminalState(0).copy(cells = emptyList())
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidCount, false))
        }
    }

    @Test
    fun `input rate limits are isolated per viewer and reset each tick`() {
        val limiter = TerminalInputRateLimiter(maximumEventsPerTick = 2)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(limiter.accept(first, 10))
        assertTrue(limiter.accept(first, 10))
        assertFalse(limiter.accept(first, 10))
        assertTrue(limiter.accept(second, 10))
        assertTrue(limiter.accept(first, 11))
    }

    @Suppress("DEPRECATION")
    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private companion object {
        const val CELL_COUNT = 51 * 19
        val POSITION = BlockPos(2, 3, 4)

        fun cell(codePoint: Int): TerminalCell = TerminalCell(codePoint, 15, 0)

        fun cell(value: Char): TerminalCell = cell(value.code)

        fun terminalState(revision: Long): TerminalState =
            TerminalState(
                revision,
                51,
                19,
                List(CELL_COUNT) { cell(' ') },
                TerminalPosition(0, 0),
                true,
            )
    }
}
