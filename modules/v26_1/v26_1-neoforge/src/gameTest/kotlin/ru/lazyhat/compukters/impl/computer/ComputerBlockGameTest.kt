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

package ru.lazyhat.compukters.impl.computer

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@EventBusSubscriber(modid = MOD_ID)
object ComputerBlockGameTest {
    @JvmStatic
    @SubscribeEvent
    fun registerTests(event: RegisterGameTestsEvent) {
        val environment =
            event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MOD_ID, "empty"),
                TestEnvironmentDefinition.AllOf(),
            )
        val testData =
            TestData(
                environment,
                Identifier.withDefaultNamespace("bastion/mobs/empty"),
                200,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                0,
            )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_lifecycle"),
            ComputerLifecycleGameTest(testData),
        )
    }

    private class ComputerLifecycleGameTest(
        testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
    ) : GameTestInstance(testData) {
        override fun run(helper: GameTestHelper) {
            val position = BlockPos.ZERO
            val block = CompuktersRegistry.COMPUTER.get()
            helper.setBlock(position, block)
            helper.assertBlockPresent(block, position)
            val entity = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
            helper.assertTrue(
                entity.type === CompuktersRegistry.COMPUTER_BLOCK_ENTITY.get(),
                "computer block created the wrong block entity type",
            )
            helper
                .startSequence()
                .thenWaitUntil { assertAutoBooted(helper, entity) }
                .thenExecute {
                    helper.setBlock(position, Blocks.AIR)
                    helper.assertTrue(entity.isRemoved, "removing the block did not remove its computer block entity")
                    helper.assertTrue(entity.runtimeState == ProgramComputerState.Closed, "removing the block did not close its VM")
                    verifyTombstoneRecovery(helper, position)
                    verifyTwoComputerWorldRestart(helper, position, position.east())
                }.thenSucceed()
        }

        override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

        override fun typeDescription(): MutableComponent = Component.literal("Compukters computer lifecycle")
    }

    private fun assertAutoBooted(
        helper: GameTestHelper,
        entity: NeoForgeComputerBlockEntity,
    ) {
        helper.assertTrue(entity.runtimeState != neverStarted(), "computer did not auto-boot on the server ticker")
        val populated = entity.terminalFullState()
        helper.assertTrue(populated != null, "running computer did not expose its Rust terminal")
        helper.assertTrue(populated!!.revision > 0, "terminal output was not committed")
        helper.assertTrue(
            populated.cells.any { cell -> cell.codePoint != ' '.code },
            "terminal fixture did not draw any cells",
        )
    }

    private fun verifyTombstoneRecovery(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(position, block)
        val entity = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
        val computerId = entity.computerId()
        val context = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, computerId, emptyRom())
        writeFixture(context.store, computerId, "filesystem-write.cpkt")

        val absolutePosition = helper.absolutePos(position)
        block.playerWillDestroy(
            helper.level,
            absolutePosition,
            helper.level.getBlockState(absolutePosition),
            helper.makeMockPlayer(GameType.SURVIVAL),
        )
        helper.setBlock(position, Blocks.AIR)

        val tombstoneRejected =
            try {
                VmSession.openInStore(fixture("filesystem-read.cpkt"), context.store, computerId, emptyRom()).use { }
                false
            } catch (_: VmBridgeException) {
                true
            }
        helper.assertTrue(tombstoneRejected, "tombstoned filesystem accepted a new machine")

        NeoForgeWorldFileSystemStores.recover(helper.level, computerId)
        assertMarker(helper, context.store, computerId, FIRST_MARKER)
    }

    private fun verifyTwoComputerWorldRestart(
        helper: GameTestHelper,
        firstPosition: BlockPos,
        secondPosition: BlockPos,
    ) {
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(firstPosition, block)
        helper.setBlock(secondPosition, block)
        val first = helper.getBlockEntity(firstPosition, NeoForgeComputerBlockEntity::class.java)
        val second = helper.getBlockEntity(secondPosition, NeoForgeComputerBlockEntity::class.java)
        val firstId = first.computerId()
        val secondId = second.computerId()
        helper.assertTrue(firstId != secondId, "two computers received the same identity")

        val firstContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, emptyRom())
        val secondContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, secondId, emptyRom())
        val firstGeneration = writeFixture(firstContext.store, firstId, "filesystem-write.cpkt")
        val secondGeneration = writeFixture(secondContext.store, secondId, "filesystem-write-alternate.cpkt")

        first.prepareTerminal()
        second.prepareTerminal()
        val restoredFirst = reloadComputer(helper, firstPosition, firstId)
        val restoredSecond = reloadComputer(helper, secondPosition, secondId)
        restoredFirst.prepareTerminal()
        restoredSecond.prepareTerminal()

        NeoForgeWorldFileSystemStores.onLevelSave(LevelEvent.Save(helper.level))
        helper.assertTrue(
            firstContext.store.durableGeneration(firstId) == firstGeneration,
            "world save did not flush the first computer generation",
        )
        helper.assertTrue(
            secondContext.store.durableGeneration(secondId) == secondGeneration,
            "world save did not flush the second computer generation",
        )
        helper.assertTrue(firstContext.store.health() == FileSystemStoreHealth.ACTIVE, "world store was not active before stop")

        val stoppedStore = firstContext.store
        NeoForgeWorldFileSystemStores.onServerStopping(ServerStoppingEvent(helper.level.server))
        helper.assertTrue(restoredFirst.runtimeState == ProgramComputerState.Closed, "first VM was not drained on stop")
        helper.assertTrue(restoredSecond.runtimeState == ProgramComputerState.Closed, "second VM was not drained on stop")
        helper.assertTrue(rejectsAfterClose { stoppedStore.health() }, "closed store still exposed health")
        helper.assertTrue(
            rejectsAfterClose { stoppedStore.flush(firstId, firstGeneration) },
            "closed store accepted a write after worker shutdown",
        )

        val reopened = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, emptyRom()).store
        assertMarker(helper, reopened, firstId, FIRST_MARKER)
        assertMarker(helper, reopened, secondId, SECOND_MARKER)
    }

    private fun reloadComputer(
        helper: GameTestHelper,
        position: BlockPos,
        expectedId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
    ): NeoForgeComputerBlockEntity {
        val level = helper.level
        val absolutePosition = helper.absolutePos(position)
        val current = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
        val blockState = level.getBlockState(absolutePosition)
        val payload = current.saveWithFullMetadata(level.registryAccess())
        level.removeBlockEntity(absolutePosition)
        helper.assertTrue(current.isRemoved, "unloaded computer did not close its block entity")
        val restored =
            BlockEntity.loadStatic(absolutePosition, blockState, payload, level.registryAccess()) as? NeoForgeComputerBlockEntity
        helper.assertTrue(restored != null, "saved computer block entity did not reload")
        level.setBlockEntity(restored!!)
        helper.assertTrue(restored.computerId() == expectedId, "computer identity changed across unload/reload")
        return restored
    }

    private fun writeFixture(
        store: ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore,
        computerId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
        name: String,
    ): Long {
        val generation =
            VmSession.openInStore(fixture(name), store, computerId, emptyRom()).use { session ->
                advanceUntilHalted(session)
                session.filesystemGeneration()
            }
        store.flush(computerId, generation)
        return generation
    }

    private fun assertMarker(
        helper: GameTestHelper,
        store: ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore,
        computerId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
        marker: String,
    ) {
        VmSession.openInStore(fixture("filesystem-read.cpkt"), store, computerId, emptyRom()).use { session ->
            helper.assertTrue(
                advanceUntilHalted(session) == VmOutcome.Halted(VmValue.I32(marker.hashCode())),
                "filesystem marker was missing or belonged to another computer",
            )
        }
    }

    private fun advanceUntilHalted(session: VmSession): VmOutcome.Halted {
        repeat(10_000) {
            when (val outcome = session.advance(64, 64)) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return outcome
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("filesystem reader did not halt")
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(ComputerBlockGameTest::class.java.getResourceAsStream("/fixtures/$name")) {
            "missing GameTest fixture $name"
        }.use { it.readAllBytes() }

    private fun emptyRom(): ByteArray {
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .array()
        return header + MessageDigest.getInstance("SHA-256").digest(header)
    }

    private inline fun rejectsAfterClose(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (_: IllegalStateException) {
            true
        }

    private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)

    private const val FIRST_MARKER = "fun main() = 42\n"
    private const val SECOND_MARKER = "fun main() = 7\n"
}
