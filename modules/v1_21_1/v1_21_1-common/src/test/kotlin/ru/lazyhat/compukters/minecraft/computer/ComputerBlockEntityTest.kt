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

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.computer.ProgramImageSource
import ru.lazyhat.compukters.core.device.computer.ProgramTerminalSink
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerBlockEntityTest {
    @Test
    fun `missing program stays lazy and installed program starts then advances once per tick`() {
        val fixture = fixture()

        fixture.entity.serverTick()
        assertTrue(fixture.carriers.isEmpty())

        fixture.entity.installArtifact(byteArrayOf(1, 2, 3))
        assertTrue(fixture.carriers.isEmpty())
        fixture.entity.serverTick()

        val carrier = fixture.carriers.single()
        assertEquals(1, carrier.turnOnCalls)
        assertEquals(1, carrier.serverTickCalls)
        assertContentEquals(byteArrayOf(1, 2, 3), carrier.loadedArtifact)

        fixture.entity.serverTick()
        assertEquals(1, carrier.turnOnCalls)
        assertEquals(2, carrier.serverTickCalls)
    }

    @Test
    fun `replacement is defensive and reboots the existing carrier`() {
        val fixture = fixture(maximumArtifactBytes = 3)
        val initial = byteArrayOf(1)
        fixture.entity.installArtifact(initial)
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        val replacement = byteArrayOf(2, 3)
        fixture.entity.installArtifact(replacement)
        replacement[0] = 9

        assertEquals(1, carrier.rebootCalls)
        assertEquals(1, fixture.carriers.size)
        assertContentEquals(byteArrayOf(2, 3), fixture.entity.installedArtifact())
        assertContentEquals(byteArrayOf(2, 3), carrier.loadedArtifact)
    }

    @Test
    fun `oversized replacement is atomic`() {
        val fixture = fixture(maximumArtifactBytes = 2)
        fixture.entity.installArtifact(byteArrayOf(1, 2))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        assertFailsWith<IllegalArgumentException> {
            fixture.entity.installArtifact(byteArrayOf(3, 4, 5))
        }

        assertContentEquals(byteArrayOf(1, 2), fixture.entity.installedArtifact())
        assertEquals(0, carrier.rebootCalls)
    }

    @Test
    fun `terminal and state adapters expose bounded transient observations`() {
        val fixture = fixture(transcriptCodeUnits = 4)
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        carrier.publish("abcdef")
        carrier.publishState(ProgramComputerState.WaitingForInput)

        assertEquals(TerminalTranscript.Snapshot("cdef", 1), fixture.entity.terminalSnapshot())
        assertEquals(ProgramComputerState.WaitingForInput, fixture.entity.runtimeState)
    }

    @Test
    fun `new install clears prior visible transcript once`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        fixture.carriers.single().publish("old")
        val before = fixture.entity.terminalSnapshot().revision

        fixture.entity.installArtifact(byteArrayOf(2))

        assertEquals("", fixture.entity.terminalSnapshot().text)
        assertEquals(before + 1, fixture.entity.terminalSnapshot().revision)
    }

    @Test
    fun `remove artifact shuts down without discarding the carrier`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        fixture.entity.removeArtifact()
        fixture.entity.serverTick()

        assertNull(fixture.entity.installedArtifact())
        assertEquals(1, carrier.shutdownCalls)
        assertEquals(1, fixture.carriers.size)
        assertEquals(1, carrier.serverTickCalls)
    }

    @Test
    fun `NBT round trip persists only the artifact and reload stays lazy`() {
        val source = fixture()
        source.entity.installArtifact(byteArrayOf(1, 2, 3))
        source.entity.serverTick()
        source.carriers.single().publish("transient")
        val tag = source.entity.saveForTest()

        val restored = fixture()
        restored.entity.loadForTest(tag)

        assertContentEquals(byteArrayOf(1, 2, 3), restored.entity.installedArtifact())
        assertEquals(TerminalTranscript.Snapshot("", 0), restored.entity.terminalSnapshot())
        assertEquals(neverStarted(), restored.entity.runtimeState)
        assertTrue(restored.carriers.isEmpty())
        assertEquals(setOf("compukters"), tag.allKeys)
    }

    @Test
    fun `loading replacement state closes current carrier and invalid data remains idle`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        fixture.entity.loadForTest(CompoundTag())
        fixture.entity.serverTick()

        assertEquals(1, carrier.closeCalls)
        assertNull(fixture.entity.installedArtifact())
        assertEquals(1, fixture.carriers.size)
        assertEquals(neverStarted(), fixture.entity.runtimeState)
    }

    @Test
    fun `setRemoved closes one carrier exactly once`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        fixture.entity.setRemoved()
        fixture.entity.setRemoved()

        assertEquals(1, carrier.closeCalls)
    }

    private fun fixture(
        maximumArtifactBytes: Int = 16,
        transcriptCodeUnits: Int = 16,
    ): Fixture {
        val carriers = mutableListOf<FakeCarrier>()
        val entity =
            TestComputerBlockEntity(
                carrierFactory =
                    ComputerCarrierFactory { deviceId, imageSource, terminalSink, stateSink ->
                        FakeCarrier(deviceId, imageSource, terminalSink, stateSink).also(carriers::add)
                    },
                maximumArtifactBytes = maximumArtifactBytes,
                transcriptCodeUnits = transcriptCodeUnits,
            )
        return Fixture(entity, carriers)
    }

    private class TestComputerBlockEntity(
        carrierFactory: ComputerCarrierFactory,
        maximumArtifactBytes: Int,
        transcriptCodeUnits: Int,
    ) : ComputerBlockEntity(
            TEST_TYPE,
            BlockPos(2, 3, 4),
            Blocks.FURNACE.defaultBlockState(),
            carrierFactory,
            InstalledProgramStorage(maximumArtifactBytes),
            TerminalTranscript(transcriptCodeUnits),
        ) {
        fun saveForTest(): CompoundTag = CompoundTag().also { saveAdditional(it, EMPTY_PROVIDER) }

        fun loadForTest(tag: CompoundTag) = loadAdditional(tag, EMPTY_PROVIDER)
    }

    private class FakeCarrier(
        private val deviceId: Int,
        private val imageSource: ProgramImageSource,
        private val terminalSink: ProgramTerminalSink,
        private val stateSink: ProgramComputerStateSink,
    ) : ComputerCarrier {
        override var state: ProgramComputerState = neverStarted()
            private set
        var turnOnCalls = 0
        var serverTickCalls = 0
        var rebootCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        var loadedArtifact: ByteArray? = null

        override fun turnOn(): ProgramComputerState {
            turnOnCalls++
            loadedArtifact = imageSource.loadInstalledArtifact(deviceId)
            return publishState(ProgramComputerState.Running)
        }

        override fun serverTick(): ProgramComputerState {
            serverTickCalls++
            return state
        }

        override fun reboot(): ProgramComputerState {
            rebootCalls++
            loadedArtifact = imageSource.loadInstalledArtifact(deviceId)
            return publishState(ProgramComputerState.Running)
        }

        override fun shutdown() {
            shutdownCalls++
            publishState(ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown))
        }

        override fun close() {
            closeCalls++
            publishState(ProgramComputerState.Closed)
        }

        fun publish(text: String) = terminalSink.publishOutput(deviceId, text)

        fun publishState(next: ProgramComputerState): ProgramComputerState {
            state = next
            stateSink.publishState(deviceId, next)
            return state
        }
    }

    private data class Fixture(
        val entity: TestComputerBlockEntity,
        val carriers: List<FakeCarrier>,
    )

    companion object {
        @Suppress("unused")
        private val MINECRAFT_BOOTSTRAP =
            run {
                SharedConstants.tryDetectVersion()
                Bootstrap.bootStrap()
            }

        private val EMPTY_PROVIDER = HolderLookup.Provider.create(Stream.empty())

        @Suppress("UNCHECKED_CAST")
        private val TEST_TYPE = BlockEntityType.FURNACE as BlockEntityType<ComputerBlockEntity>

        private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
