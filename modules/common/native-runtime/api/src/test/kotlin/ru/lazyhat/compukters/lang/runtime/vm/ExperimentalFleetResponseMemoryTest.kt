/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import ru.lazyhat.compukters.lang.runtime.capability.HostRecordField
import ru.lazyhat.compukters.lang.runtime.capability.HostRecordSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostRecordValue
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentalFleetResponseMemoryTest {
    @Test
    fun `charge includes immutable array and UTF16 payloads without encoding a response`() {
        val bytes = ByteArray(4096) { 7 }
        val response = HostResponse.ByteArraySuccess(bytes)
        val empty = ExperimentalFleetResponseMemory.retainedBytes(HostResponse.ByteArraySuccess(byteArrayOf()))
        assertEquals(empty + 4096, ExperimentalFleetResponseMemory.retainedBytes(response))
        bytes.fill(0)
        response.value.fill(1)
        assertEquals(empty + 4096, ExperimentalFleetResponseMemory.retainedBytes(response))
        val string = ExperimentalFleetResponseMemory.retainedBytes(HostResponse.StringSuccess(""))
        assertEquals(string + 8, ExperimentalFleetResponseMemory.retainedBytes(HostResponse.StringSuccess("\ud83d\ude00ab")))
        assertEquals(4096 + 6, ExperimentalFleetResponseWire.success(response).size)
    }

    @Test
    fun `nested record charge includes shared schema descriptors and every value`() {
        val childSchema = HostRecordSchema("test.Vector", listOf(HostRecordField("x", HostValueType.F64)))
        val schema =
            HostRecordSchema(
                "test.Pair",
                listOf(
                    HostRecordField("first", HostValueType.RECORD, childSchema),
                    HostRecordField("second", HostValueType.RECORD, childSchema),
                ),
            )
        val child = HostResponse.RecordSuccess(HostRecordValue(childSchema, listOf(HostResponse.DoubleSuccess(1.0))))
        val response = HostResponse.RecordSuccess(HostRecordValue(schema, listOf(child, child)))
        assertTrue(ExperimentalFleetResponseMemory.retainedBytes(response) > 2 * ExperimentalFleetResponseMemory.retainedBytes(child))
        assertTrue(ExperimentalFleetResponseMemory.retainedBytes(response) > ExperimentalFleetResponseWire.success(response).size)
    }

    @Test
    fun `failure detail is charged and invalid success payloads are rejected before queueing`() {
        val short = HostResponse.Failure(HostFailureKind.UNAVAILABLE, "a")
        val long = HostResponse.Failure(HostFailureKind.UNAVAILABLE, "abc")
        assertEquals(4L, ExperimentalFleetResponseMemory.retainedBytes(long) - ExperimentalFleetResponseMemory.retainedBytes(short))
        assertFailsWith<IllegalArgumentException> {
            ExperimentalFleetResponseMemory.retainedBytes(HostResponse.StringSuccess("x".repeat(4097)))
        }
    }
}
