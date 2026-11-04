/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.commands

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandBatchCodecTest {
    @Test
    fun `all scalar types and bytes round trip without changing argument order`() {
        val values =
            listOf(
                VmValue.I32(Int.MIN_VALUE),
                VmValue.I64(Long.MAX_VALUE),
                VmValue.F32((-0.0f).toRawBits()),
                VmValue.F64(Double.MIN_VALUE.toRawBits()),
                VmValue.Bool(true),
                VmValue.CharValue('\uD800'),
                VmValue.StringValue("λ\uD800x"),
                VmValue.ByteArrayValue(byteArrayOf(-1, 0, 7)),
            )
        val bytes = CommandBatchCodec.encode(listOf(CommandBatchCodec.Command("fixture.Binding.write", values)))
        val decoded = CommandBatchCodec.decode(bytes).single()
        assertEquals("fixture.Binding.write", decoded.symbol)
        assertEquals(values, decoded.arguments)
        assertContentEquals(bytes, CommandBatchCodec.encode(listOf(decoded)))
    }

    @Test
    fun `truncation at every byte and excess counts are rejected`() {
        val bytes = CommandBatchCodec.encode(listOf(CommandBatchCodec.Command("fixture.Binding.write", listOf(VmValue.I32(7)))))
        for (length in bytes.indices) assertFailsWith<IllegalArgumentException> { CommandBatchCodec.decode(bytes.copyOf(length)) }
        assertFailsWith<IllegalArgumentException> { CommandBatchCodec.decode(bytes + 0) }
        assertFailsWith<IllegalArgumentException> { CommandBatchCodec.decode(byteArrayOf(1, 0)) }
        assertFailsWith<IllegalArgumentException> { CommandBatchCodec.decode(byteArrayOf(1, 33)) }
        assertFailsWith<IllegalArgumentException> { CommandBatchCodec.decode(ByteArray(4097)) }
        assertFailsWith<IllegalArgumentException> { CommandBatchCodec.encode(List(33) { CommandBatchCodec.Command("f", emptyList()) }) }
    }

    @Test
    fun `worst case failure details fit one packet and keep surrogate boundaries`() {
        val text = "x".repeat(47) + "😀" + "tail"
        val bytes = CommandBatchCodec.encodeResults(List(32) { HostResponse.Failure(HostFailureKind.OTHER, text) })
        assertTrue(bytes.size <= 4096)
        assertEquals(1, bytes[0].toInt())
        assertEquals(32, bytes[1].toInt())
        assertEquals(47, bytes[3].toInt())
    }
}
