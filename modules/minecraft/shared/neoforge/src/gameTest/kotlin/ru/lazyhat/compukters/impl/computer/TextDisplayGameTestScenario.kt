/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import java.util.concurrent.CompletableFuture

internal object TextDisplayGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        val junction = BlockPos(4, 2, 2)
        val displayPosition = BlockPos(5, 2, 2)
        helper.setBlock(computerPosition, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(BlockPos(3, 2, 2), CompuktersRegistry.PERIPHERAL_CABLE.get())
        helper.setBlock(junction, CompuktersRegistry.PERIPHERAL_CABLE.get())
        helper.setBlock(
            displayPosition,
            CompuktersRegistry.DISPLAY
                .get()
                .defaultBlockState()
                .setValue(DisplayBlock.FACING, Direction.EAST),
        )
        val display =
            checkNotNull(helper.level.getBlockEntity(helper.absolutePos(displayPosition)) as? DisplayBlockEntity) {
                "display block entity was not registered"
            }
        val computer = helper.compuktersComputerBlockEntity(computerPosition)
        ComputerPeripheralNames.setName(
            helper.level,
            ComputerPeripheralIdentity("compukters-display", helper.absolutePos(displayPosition), "text"),
            "panel",
        )
        computer.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null

        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(computer.runtimeState == ProgramComputerState.WaitingForInput, "computer shell is not ready")
            }.thenExecute {
                helper.assertTrue(
                    helper.getBlockState(displayPosition).getValue(DisplayBlock.FACING) == Direction.EAST,
                    "display orientation changed",
                )
                val lookup =
                    ComputerPeripheralLookup.find(
                        helper.level,
                        helper.absolutePos(computerPosition),
                        "compukters-display",
                        "panel",
                    )
                helper.assertTrue(lookup.status == ComputerPeripheralLookupStatus.FOUND, "display was not found through cable")
                setup =
                    computer
                        .verifyForDeployAsync(fixture())
                        .thenCompose { candidate ->
                            requireNotNull(candidate)
                            computer.executableRevisionAsync("/home/display").thenCompose { expected ->
                                requireNotNull(expected)
                                helper.assertTrue(expected == VmExecutableRevision.Absent, "display executable already existed")
                                computer.deployAsync("/home/display", expected, candidate)
                            }
                        }.thenCompose {
                            computer.submitTerminalTextAsync("display")
                        }.thenCompose { accepted ->
                            helper.assertTrue(accepted, "shell rejected the display command")
                            computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                        }.thenAccept { accepted ->
                            helper.assertTrue(accepted, "shell rejected the display command enter key")
                        }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "display program setup is pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                helper.assertTrue(display.displayRows()[0].startsWith("Ready"), "Guest program did not write on display")
            }.thenExecute {
                helper.setBlock(junction, Blocks.AIR)
            }.thenWaitUntil {
                helper.assertTrue(display.displayRows()[0].isBlank(), "display did not clear after cable disconnect")
            }.thenSucceed()
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/display.cpkt")) {
            "missing generated text display GameTest fixture"
        }.use { it.readAllBytes() }
}
