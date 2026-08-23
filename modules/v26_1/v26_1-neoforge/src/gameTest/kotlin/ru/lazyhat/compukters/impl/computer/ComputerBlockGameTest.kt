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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import java.nio.file.Path
import kotlin.io.path.readText

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
            entity.installArtifact(loadTerminalFixture())

            helper.succeedWhen {
                helper.assertTrue(entity.runtimeState != neverStarted(), "computer did not auto-boot on the server ticker")
                helper.setBlock(position, Blocks.AIR)
                helper.assertTrue(entity.isRemoved, "removing the block did not remove its computer block entity")
                helper.assertTrue(entity.runtimeState == ProgramComputerState.Closed, "removing the block did not close its VM")
            }
        }

        override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

        override fun typeDescription(): MutableComponent = Component.literal("Compukters computer lifecycle")
    }

    private fun loadTerminalFixture(): ByteArray {
        val encoded = Path.of(requiredProperty("compukter.vm.terminalFixture")).readText().trim()
        require(encoded.length % 2 == 0) { "fixture contains incomplete hexadecimal byte" }
        return ByteArray(encoded.length / 2) { index ->
            encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing game-test system property $name" }

    private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
}
