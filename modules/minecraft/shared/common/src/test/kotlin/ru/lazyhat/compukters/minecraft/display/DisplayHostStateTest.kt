/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DisplayHostStateTest {
    @Test
    fun `whole-screen opacity validates ownership bounds and capability minor without changing pixels`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        host.call(8, VmValue.I32(handle), VmValue.I32(0x123456))
        for (opacity in listOf(0, 128, 255)) {
            assertEquals(HostResponse.UnitSuccess, host.call(22, VmValue.I32(handle), VmValue.I32(opacity)))
            assertEquals(opacity, endpoint.canvas.opacity)
            assertEquals(0x123456, endpoint.canvas.pixelAt(0, 0))
        }
        for (opacity in listOf(-1, 256)) assertIs<HostResponse.Failure>(host.call(22, VmValue.I32(handle), VmValue.I32(opacity)))
        assertEquals(255, endpoint.canvas.opacity)
        val old =
            ProgramAddonRequest(
                VmHostRequestIdentity(1, 1),
                ru.lazyhat.compukters.lang.runtime.vm
                    .CapabilityIdentity("compukters", "display", 1, 4),
                22,
                listOf(VmValue.I32(handle), VmValue.I32(0)),
            )
        assertIs<HostResponse.Failure>(assertIs<ProgramAddonDispatch.Completed>(host.dispatch(old)).response)
        assertEquals(255, endpoint.canvas.opacity)
        endpoint.present = false
        assertIs<HostResponse.Failure>(host.call(22, VmValue.I32(handle), VmValue.I32(0)))
        assertEquals(255, endpoint.canvas.opacity)
    }

    @Test
    fun `pointer polling uses bounded little endian fields and requires capability minor three`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(0, assertIs<HostResponse.ByteArraySuccess>(host.call(18, VmValue.I32(handle))).value.size)
        assertTrue(
            endpoint.canvas.offerPointer(
                ru.lazyhat.compukters.core.display
                    .DisplayPointerEvent(0, 0x123456, 12, 9),
            ),
        )
        kotlin.test.assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0x56, 0x34, 0x12, 0, 12, 0, 0, 0, 9, 0, 0, 0),
            assertIs<HostResponse.ByteArraySuccess>(host.call(18, VmValue.I32(handle))).value,
        )
        val old =
            ProgramAddonRequest(
                VmHostRequestIdentity(1, 1),
                ru.lazyhat.compukters.lang.runtime.vm
                    .CapabilityIdentity("compukters", "display", 1, 2),
                18,
                listOf(VmValue.I32(handle)),
            )
        assertIs<HostResponse.Failure>(assertIs<ProgramAddonDispatch.Completed>(host.dispatch(old)).response)
        host.reset()
        val replacement = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(0, assertIs<HostResponse.ByteArraySuccess>(host.call(18, VmValue.I32(replacement))).value.size)
    }

    @Test
    fun `clipped line and text operations preserve pixels outside their explicit region`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        host.call(8, VmValue.I32(handle), VmValue.I32(0x123456))
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(
                19,
                VmValue.I32(handle),
                VmValue.I32(-100),
                VmValue.I32(5),
                VmValue.I32(100),
                VmValue.I32(5),
                VmValue.I32(0xFF0000),
                VmValue.I32(4),
                VmValue.I32(5),
                VmValue.I32(3),
                VmValue.I32(2),
            ),
        )
        assertEquals(0xFF0000, endpoint.canvas.pixelAt(4, 5))
        assertEquals(0x123456, endpoint.canvas.pixelAt(3, 5))
        assertEquals(0x123456, endpoint.canvas.pixelAt(7, 5))
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(
                20,
                VmValue.I32(handle),
                VmValue.I32(0),
                VmValue.I32(0),
                VmValue.StringValue("WWW"),
                VmValue.I32(0x00FF00),
                VmValue.I32(1),
                VmValue.I32(4),
                VmValue.I32(5),
                VmValue.I32(3),
                VmValue.I32(2),
            ),
        )
        assertEquals(0x123456, endpoint.canvas.pixelAt(3, 5))
        assertEquals(0x123456, endpoint.canvas.pixelAt(7, 5))
        assertIs<HostResponse.Failure>(
            host.call(
                19,
                VmValue.I32(handle),
                VmValue.I32(0),
                VmValue.I32(0),
                VmValue.I32(1),
                VmValue.I32(1),
                VmValue.I32(0),
                VmValue.I32(0),
                VmValue.I32(0),
                VmValue.I32(-1),
                VmValue.I32(2),
            ),
        )
    }

    @Test
    fun `older display capability minors retain drawing but cannot poll input`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        for (minor in 0..1) {
            fun invoke(
                operation: Int,
                arguments: List<VmValue>,
            ): HostResponse =
                assertIs<ProgramAddonDispatch.Completed>(
                    host.dispatch(
                        ProgramAddonRequest(
                            VmHostRequestIdentity(1, 1),
                            ru.lazyhat.compukters.lang.runtime.vm
                                .CapabilityIdentity("compukters", "display", 1, minor),
                            operation,
                            arguments,
                        ),
                    ),
                ).response
            assertIs<HostResponse.Failure>(invoke(17, listOf(VmValue.I32(handle))))
            assertEquals(HostResponse.UnitSuccess, invoke(8, listOf(VmValue.I32(handle), VmValue.I32(123))))
        }
    }

    @Test
    fun `click polling claims the writer and encodes FIFO coordinates without sharing or replay`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(0, assertIs<HostResponse.ByteArraySuccess>(host.call(17, VmValue.I32(handle))).value.size)
        assertTrue(endpoint.canvas.offerClick(12, 9))
        val other = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val otherHandle = assertIs<HostResponse.IntSuccess>(other.call(0, VmValue.I32(0))).value
        assertIs<HostResponse.Failure>(other.call(17, VmValue.I32(otherHandle)))
        kotlin.test.assertContentEquals(
            byteArrayOf(12, 0, 0, 0, 9, 0, 0, 0),
            assertIs<HostResponse.ByteArraySuccess>(host.call(17, VmValue.I32(handle))).value,
        )
        endpoint.canvas.offerClick(1, 1)
        host.reset()
        assertEquals(0, assertIs<HostResponse.ByteArraySuccess>(other.call(17, VmValue.I32(otherHandle))).value.size)
    }

    @Test
    fun `named and adjacent handles write one exact display and reset preserves it`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val adjacent = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val named = assertIs<HostResponse.IntSuccess>(host.call(1, VmValue.StringValue("screen"))).value
        assertEquals(adjacent, named)
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(9, VmValue.I32(adjacent), VmValue.I32(1), VmValue.I32(2), VmValue.I32(0x123456)),
        )
        assertEquals(0x123456, endpoint.canvas.pixelAt(1, 2))
        host.reset()
        assertEquals(0x123456, endpoint.canvas.pixelAt(1, 2))
        assertIs<HostResponse.Failure>(host.call(3, VmValue.I32(adjacent)))
    }

    @Test
    fun `a replaced or disconnected display invalidates its acquired handle`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        endpoint.present = false
        val failure = assertIs<HostResponse.Failure>(host.call(3, VmValue.I32(handle)))
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `malformed calls do not acquire a handle`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        assertEquals(HostFailureKind.OTHER, assertIs<HostResponse.Failure>(host.call(0, VmValue.I32(0), VmValue.I32(1))).kind)
        assertEquals(1, assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value)
    }

    @Test
    fun `frames stay private until commit and reset discards drafts`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(9, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.I32(123)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        assertEquals(HostResponse.UnitSuccess, host.call(15, VmValue.I32(handle)))
        assertEquals(123, endpoint.canvas.pixelAt(0, 0))
        host.call(14, VmValue.I32(handle))
        host.call(8, VmValue.I32(handle), VmValue.I32(456))
        host.reset()
        assertEquals(123, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `malformed RGB image and mode requests leave pixels intact`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertIs<HostResponse.Failure>(host.call(7, VmValue.I32(handle), VmValue.I32(4)))
        assertIs<HostResponse.Failure>(host.call(13, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("zz0000")))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        assertEquals(2, endpoint.canvas.mode)
    }

    @Test
    fun `cooperative tasks cannot draw into or commit another tasks frame`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))

        fun other(
            operation: Int,
            vararg arguments: VmValue,
        ): HostResponse =
            assertIs<ProgramAddonDispatch.Completed>(
                host.dispatch(
                    ProgramAddonRequest(
                        VmHostRequestIdentity(2, 2),
                        DisplayHostState.SCHEMA.identity,
                        operation,
                        arguments.toList(),
                    ),
                ),
            ).response
        assertIs<HostResponse.Failure>(other(9, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.I32(123)))
        assertIs<HostResponse.Failure>(other(15, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(16, VmValue.I32(handle)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `legacy text overwrites its cells including spaces without changing other pixels`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(2, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("WWW")),
        )
        assertEquals(3, endpoint.canvas.mode)
        assertTrue(endpoint.canvas.encodeRgb().any { it != 0.toByte() })
        host.call(9, VmValue.I32(handle), VmValue.I32(100), VmValue.I32(100), VmValue.I32(123))
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(2, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("   ")),
        )
        assertFalse((0 until 12).any { y -> (0 until 18).any { x -> endpoint.canvas.pixelAt(x, y) != 0 } })
        assertEquals(123, endpoint.canvas.pixelAt(100, 100))
    }

    @Test
    fun `legacy text reaches the whole composite grid and crosses panel boundaries`() {
        val endpoint = FakeDisplayEndpoint(3, 2)
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value

        fun write(
            x: Int,
            y: Int,
            text: String,
        ) = host.call(2, VmValue.I32(handle), VmValue.I32(x), VmValue.I32(y), VmValue.StringValue(text))
        assertEquals(HostResponse.UnitSuccess, write(19, 0, "WWWWW"))
        assertTrue((0 until 12).any { y -> (128 until 144).any { x -> endpoint.canvas.pixelAt(x, y) != 0 } })
        assertEquals(HostResponse.UnitSuccess, write(59, 19, "W"))
        assertTrue((228 until 240).any { y -> (354 until 360).any { x -> endpoint.canvas.pixelAt(x, y) != 0 } })
        assertEquals(HostResponse.UnitSuccess, write(59, 19, " "))
        assertFalse((228 until 240).any { y -> (354 until 360).any { x -> endpoint.canvas.pixelAt(x, y) != 0 } })
        val before = endpoint.canvas.encodeRgb()
        for ((x, y, text) in listOf(Triple(60, 0, "W"), Triple(0, 20, "W"), Triple(59, 19, "WW"), Triple(-1, 0, "W"))) {
            assertIs<HostResponse.Failure>(write(x, y, text))
        }
        kotlin.test.assertContentEquals(before, endpoint.canvas.encodeRgb())
    }

    @Test
    fun `legacy text keeps single panel bounds`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(HostResponse.UnitSuccess, host.call(2, VmValue.I32(handle), VmValue.I32(19), VmValue.I32(9), VmValue.StringValue("W")))
        assertIs<HostResponse.Failure>(host.call(2, VmValue.I32(handle), VmValue.I32(20), VmValue.I32(0), VmValue.StringValue("W")))
        assertIs<HostResponse.Failure>(host.call(2, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(10), VmValue.StringValue("W")))
    }

    @Test
    fun `raster packets preserve scalar drawing order clipping text images and atomic publication`() {
        val endpoint = FakeDisplayEndpoint()
        val scalarEndpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val scalar = DisplayHostState({ scalarEndpoint }, { DisplayResolution.Found(scalarEndpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val scalarHandle = assertIs<HostResponse.IntSuccess>(scalar.call(0, VmValue.I32(0))).value

        fun draw(
            operation: Int,
            vararg fields: Int,
        ) = scalar.call(operation, VmValue.I32(scalarHandle), *fields.map(VmValue::I32).toTypedArray())
        draw(8, 0x123456)
        draw(11, 1, 1, 8, 8, 0x456789)
        draw(10, 0, 4, 12, 4, 0xABCDEF)
        draw(19, -100, 5, 100, 5, 0xFF0000, 4, 5, 3, 2)
        scalar.call(
            20,
            VmValue.I32(scalarHandle),
            VmValue.I32(0),
            VmValue.I32(0),
            VmValue.StringValue("W\uD83D\uDE00"),
            VmValue.I32(0xFFFFFF),
            VmValue.I32(1),
            VmValue.I32(4),
            VmValue.I32(5),
            VmValue.I32(3),
            VmValue.I32(2),
        )
        scalar.call(13, VmValue.I32(scalarHandle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("ff000000ff00"))
        draw(9, 0, 0, 0x778899)
        val packet =
            rasterPacket(7) {
                command(8, 0x123456)
                command(11, 1, 1, 8, 8, 0x456789)
                command(10, 0, 4, 12, 4, 0xABCDEF)
                command(19, -100, 5, 100, 5, 0xFF0000, 4, 5, 3, 2)
                put(20.toByte())
                putInt(0)
                putInt(0)
                putText("W\uD83D\uDE00")
                listOf(0xFFFFFF, 1, 4, 5, 3, 2).forEach { putInt(it) }
                command(13, 0, 0, 2, 0xFF0000, 0x00FF00)
                command(9, 0, 0, 0x778899)
            }
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(21, VmValue.I32(handle), VmValue.ByteArrayValue(packet)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        assertEquals(HostResponse.UnitSuccess, host.call(15, VmValue.I32(handle)))
        for (y in 0 until endpoint.canvas.height) {
            for (x in 0 until endpoint.canvas.width) {
                assertEquals(scalarEndpoint.canvas.pixelAt(x, y), endpoint.canvas.pixelAt(x, y), "$x,$y")
            }
        }
    }

    @Test
    fun `raster packets reject malformed data and poison partially applied frames`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val valid = rasterPacket(1) { command(9, 0, 0, 0x123456) }
        val malformed =
            listOf(
                valid.copyOf(valid.size - 1),
                valid + byteArrayOf(0),
                rasterPacket(1) { command(99) },
                rasterPacket(2) {
                    command(9, 0, 0, 0x123456)
                    command(99)
                },
                rasterPacket(129) {},
                rasterPacket(0) {},
                rasterPacket(1) { command(9, 0, 0, -1) },
                rasterPacket(1) { command(11, 0, 0, -1, 1, 0) },
                rasterPacket(1) { command(13, 0, 0, 257) },
                rasterPacket(1) {
                    put(12.toByte())
                    putInt(0)
                    putInt(0)
                    putText("\uD800")
                    putInt(0)
                    putInt(1)
                },
                valid.copyOf().also { it[0] = 2 },
            )
        malformed.forEach { packet ->
            assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))
            var charged = 0
            endpoint.canvas.admitWork = { charged++ }
            assertIs<HostResponse.Failure>(host.call(21, VmValue.I32(handle), VmValue.ByteArrayValue(packet)))
            assertEquals(0, charged, "Malformed packets must reject before applying any command")
            endpoint.canvas.admitWork = {}
            assertIs<HostResponse.Failure>(host.call(15, VmValue.I32(handle)))
            assertEquals(HostResponse.UnitSuccess, host.call(16, VmValue.I32(handle)))
            assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { DisplayRasterBatch.decode(ByteArray(4097)) }
        // The second command exceeds pixel-work quota after the first command has modified the private canvas.
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))
        endpoint.canvas.admitWork = { work -> check(work < 2) }
        val workFailure =
            rasterPacket(2) {
                command(9, 0, 0, 0x123456)
                command(11, 0, 0, 2, 2, 0)
            }
        assertIs<HostResponse.Failure>(host.call(21, VmValue.I32(handle), VmValue.ByteArrayValue(workFailure)))
        assertIs<HostResponse.Failure>(host.call(15, VmValue.I32(handle)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `raster batches require current minor an open frame and its task owner`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val packet = VmValue.ByteArrayValue(rasterPacket(1) { command(9, 0, 0, 0x123456) })
        assertIs<HostResponse.Failure>(host.call(21, VmValue.I32(handle), packet))
        host.call(14, VmValue.I32(handle))
        for ((task, minor) in listOf(2 to 4, 1 to 3)) {
            val response =
                assertIs<ProgramAddonDispatch.Completed>(
                    host.dispatch(
                        ProgramAddonRequest(
                            VmHostRequestIdentity(task, 1),
                            ru.lazyhat.compukters.lang.runtime.vm
                                .CapabilityIdentity("compukters", "display", 1, minor),
                            21,
                            listOf(VmValue.I32(handle), packet),
                        ),
                    ),
                ).response
            assertIs<HostResponse.Failure>(response)
        }
        assertIs<HostResponse.Failure>(host.call(14, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(21, VmValue.I32(handle), packet))
        assertEquals(HostResponse.UnitSuccess, host.call(16, VmValue.I32(handle)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        host.call(14, VmValue.I32(handle))
        endpoint.present = false
        assertIs<HostResponse.Failure>(host.call(21, VmValue.I32(handle), packet))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    private fun rasterPacket(
        count: Int,
        block: java.nio.ByteBuffer.() -> Unit,
    ): ByteArray {
        val buffer =
            java.nio.ByteBuffer
                .allocate(4096)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1)
        buffer.putInt(count)
        buffer.block()
        return buffer.array().copyOf(buffer.position())
    }

    private fun java.nio.ByteBuffer.command(
        operation: Int,
        vararg fields: Int,
    ) {
        put(operation.toByte())
        fields.forEach { putInt(it) }
    }

    private fun java.nio.ByteBuffer.putText(text: String) {
        putInt(text.length)
        text.forEach { putChar(it) }
    }

    private fun DisplayHostState.call(
        operation: Int,
        vararg arguments: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, 1),
                    DisplayHostState.SCHEMA.identity,
                    operation,
                    arguments.toList(),
                ),
            ),
        ).response

    private class FakeDisplayEndpoint(
        columns: Int = 1,
        rows: Int = 1,
    ) : DisplayEndpoint {
        override val identity = Any()
        override val canvas =
            ru.lazyhat.compukters.core.display
                .DisplayCanvas(columns, rows)
        var present = true

        override fun valid(): Boolean = present
    }
}
