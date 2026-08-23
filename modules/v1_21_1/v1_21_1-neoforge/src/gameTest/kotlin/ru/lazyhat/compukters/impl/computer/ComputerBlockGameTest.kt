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

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import java.nio.file.Path
import kotlin.io.path.readText

@GameTestHolder(MOD_ID)
@PrefixGameTestTemplate(false)
object ComputerBlockGameTest {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    fun registeredComputerAutoBootsAndClosesWhenRemoved(helper: GameTestHelper) {
        val position = BlockPos.ZERO
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(position, block)
        helper.assertBlockPresent(block, position)
        val entity = helper.getBlockEntity<NeoForgeComputerBlockEntity>(position)
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
