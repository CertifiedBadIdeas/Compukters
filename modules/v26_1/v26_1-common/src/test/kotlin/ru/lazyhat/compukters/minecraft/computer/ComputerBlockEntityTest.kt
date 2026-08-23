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
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.computer.ProgramImageSource
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
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
        val initialMachineId = requireNotNull(fixture.entity.terminalMachineId)

        val replacement = byteArrayOf(2, 3)
        fixture.entity.installArtifact(replacement)
        replacement[0] = 9

        assertEquals(1, carrier.rebootCalls)
        assertTrue(requireNotNull(fixture.entity.terminalMachineId) > initialMachineId)
        assertEquals(1, fixture.carriers.size)
        assertContentEquals(byteArrayOf(2, 3), fixture.entity.installedArtifact())
        assertContentEquals(byteArrayOf(2, 3), carrier.loadedArtifact)
    }

    @Test
    fun `terminal open starts a blank machine without advancing an extra tick`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))

        assertEquals(terminalState("", 0), fixture.entity.prepareTerminal())
        assertEquals(1, fixture.carriers.single().turnOnCalls)
        assertEquals(0, fixture.carriers.single().serverTickCalls)
        assertTrue(requireNotNull(fixture.entity.terminalMachineId) > 0)
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
    fun `terminal and state adapters expose Rust full and delta observations`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        carrier.terminal = terminalState("abcdef", 1)
        carrier.update = TerminalUpdate.Unchanged(1)
        carrier.publishState(ProgramComputerState.WaitingForInput)

        assertEquals(carrier.terminal, fixture.entity.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(1), fixture.entity.terminalChangesSince(1))
        assertEquals(ProgramComputerState.WaitingForInput, fixture.entity.runtimeState)
    }

    @Test
    fun `accepted terminal input is forwarded without Kotlin echo`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()
        carrier.terminal = terminalState("prompt", 1)
        carrier.publishState(ProgramComputerState.WaitingForInput)

        assertTrue(fixture.entity.submitTerminalLine("Ada"))

        assertEquals(listOf("Ada"), carrier.submittedLines)
        assertEquals(terminalState("prompt", 1), fixture.entity.terminalFullState())
    }

    @Test
    fun `new install replaces the Rust machine with a blank terminal`() {
        val fixture = fixture()
        fixture.entity.installArtifact(byteArrayOf(1))
        fixture.entity.serverTick()
        fixture.carriers.single().terminal = terminalState("old", 4)

        fixture.entity.installArtifact(byteArrayOf(2))

        assertEquals(terminalState("", 0), fixture.entity.terminalFullState())
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
        source.carriers.single().terminal = terminalState("transient", 1)
        val tag = source.entity.saveForTest()

        val restored = fixture()
        restored.entity.loadForTest(tag)

        assertContentEquals(byteArrayOf(1, 2, 3), restored.entity.installedArtifact())
        assertNull(restored.entity.terminalFullState())
        assertEquals(neverStarted(), restored.entity.runtimeState)
        assertTrue(restored.carriers.isEmpty())
        assertEquals(setOf("compukters"), tag.keySet())
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

    private fun fixture(maximumArtifactBytes: Int = 16): Fixture {
        val carriers = mutableListOf<FakeCarrier>()
        val entity =
            TestComputerBlockEntity(
                carrierFactory =
                    ComputerCarrierFactory { deviceId, imageSource, stateSink ->
                        FakeCarrier(deviceId, imageSource, stateSink).also(carriers::add)
                    },
                maximumArtifactBytes = maximumArtifactBytes,
            )
        return Fixture(entity, carriers)
    }

    private class TestComputerBlockEntity(
        carrierFactory: ComputerCarrierFactory,
        maximumArtifactBytes: Int,
    ) : ComputerBlockEntity(
            TEST_TYPE,
            BlockPos(2, 3, 4),
            Blocks.FURNACE.defaultBlockState(),
            carrierFactory,
            InstalledProgramStorage(maximumArtifactBytes),
        ) {
        fun saveForTest(): CompoundTag {
            val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, EMPTY_PROVIDER)
            saveAdditional(output)
            return output.buildResult()
        }

        fun loadForTest(tag: CompoundTag) = loadAdditional(TagValueInput.create(ProblemReporter.DISCARDING, EMPTY_PROVIDER, tag))
    }

    private class FakeCarrier(
        private val deviceId: Int,
        private val imageSource: ProgramImageSource,
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
        val submittedLines = mutableListOf<String>()
        var terminal: TerminalState = terminalState("", 0)
        var update: TerminalUpdate = TerminalUpdate.Unchanged(0)
        val keys = mutableListOf<Triple<TerminalKey, TerminalKeyAction, Set<TerminalModifier>>>()
        val texts = mutableListOf<String>()

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
            terminal = terminalState("", 0)
            update = TerminalUpdate.Unchanged(0)
            return publishState(ProgramComputerState.Running)
        }

        override fun shutdown() {
            shutdownCalls++
            publishState(ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown))
        }

        override fun submitLine(line: String): Boolean {
            submittedLines += line
            return true
        }

        override fun terminalFullState(): TerminalState = terminal

        override fun terminalChangesSince(revision: Long): TerminalUpdate = update

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ): Boolean {
            keys += Triple(key, action, modifiers)
            return true
        }

        override fun sendTerminalText(value: String): Boolean {
            texts += value
            return true
        }

        override fun close() {
            closeCalls++
            publishState(ProgramComputerState.Closed)
        }

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

        private fun terminalState(
            text: String,
            revision: Long,
        ): TerminalState {
            val codePoints = text.codePoints().toArray()
            return TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { index -> TerminalCell(codePoints.getOrElse(index) { ' '.code }, 15, 0) },
                TerminalPosition(codePoints.size.coerceAtMost(50), 0),
                true,
            )
        }
    }
}
