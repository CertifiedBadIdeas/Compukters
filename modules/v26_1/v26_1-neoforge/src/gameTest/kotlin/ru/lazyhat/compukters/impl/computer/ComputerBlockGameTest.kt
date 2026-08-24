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
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
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
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_filesystem_recovery"),
            ComputerFileSystemRecoveryGameTest(testData),
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
            helper.succeedWhen {
                helper.assertTrue(entity.runtimeState != neverStarted(), "computer did not auto-boot on the server ticker")
                val populated = entity.terminalFullState()
                helper.assertTrue(populated != null, "running computer did not expose its Rust terminal")
                helper.assertTrue(populated!!.revision > 0, "terminal output was not committed")
                helper.assertTrue(
                    populated.cells.any { cell -> cell.codePoint != ' '.code },
                    "terminal fixture did not draw any cells",
                )

                helper.setBlock(position, Blocks.AIR)
                helper.assertTrue(entity.isRemoved, "removing the block did not remove its computer block entity")
                helper.assertTrue(entity.runtimeState == ProgramComputerState.Closed, "removing the block did not close its VM")
            }
        }

        override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

        override fun typeDescription(): MutableComponent = Component.literal("Compukters computer lifecycle")
    }

    private class ComputerFileSystemRecoveryGameTest(
        testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
    ) : GameTestInstance(testData) {
        override fun run(helper: GameTestHelper) {
            val position = BlockPos.ZERO
            val block = CompuktersRegistry.COMPUTER.get()
            helper.setBlock(position, block)
            val entity = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
            val computerId = entity.computerId()
            val context = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, computerId, emptyRom())
            val writtenGeneration =
                VmSession
                    .openInStore(fixture("filesystem-write.cpkt"), context.store, computerId, emptyRom())
                    .use { session ->
                        advanceUntilHalted(session)
                        session.filesystemGeneration()
                    }
            context.store.flush(computerId, writtenGeneration)

            helper.succeedWhen {
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
                        VmSession
                            .openInStore(fixture("filesystem-read.cpkt"), context.store, computerId, emptyRom())
                            .use { }
                        false
                    } catch (_: VmBridgeException) {
                        true
                    }
                helper.assertTrue(tombstoneRejected, "tombstoned filesystem accepted a new machine")

                NeoForgeWorldFileSystemStores.recover(helper.level, computerId)
                VmSession
                    .openInStore(fixture("filesystem-read.cpkt"), context.store, computerId, emptyRom())
                    .use { session ->
                        helper.assertTrue(
                            advanceUntilHalted(session) == VmOutcome.Halted(VmValue.I32("fun main() = 42\n".hashCode())),
                            "recovered filesystem did not retain /home/project/main.kt",
                        )
                    }
            }
        }

        override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

        override fun typeDescription(): MutableComponent = Component.literal("Compukters filesystem recovery")
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

    private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
}
