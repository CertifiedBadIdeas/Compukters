/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
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

internal object GraphicalDisplayHibernationGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        val displayPosition = BlockPos(5, 2, 2)
        helper.setBlock(computerPosition, CompuktersRegistry.COMPUTER.get())
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
        var computer = helper.compuktersComputerBlockEntity(computerPosition)
        val computerId = computer.computerId()
        var saved: CompoundTag? = null
        var oldEpoch: Long? = null
        ComputerPeripheralNames.setName(
            helper.level,
            ComputerPeripheralIdentity("compukters-display", helper.absolutePos(displayPosition), "text"),
            "panel",
        )
        PeripheralNetworkGameTestFixtures.bind(helper, computerPosition, displayPosition)
        computer.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var terminal: CompletableFuture<ru.lazyhat.compukters.lang.runtime.vm.TerminalState?>? = null

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
                helper.assertTrue(lookup.status == ComputerPeripheralLookupStatus.FOUND, "display was not found through bound network")
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
                helper.assertTrue(display.displayColor(0, 0) == 0x112233, "published pixel is missing")
                if (terminal == null) terminal = computer.terminalFullStateAsync()
                helper.assertTrue(terminal!!.isDone, "terminal snapshot is pending")
                val state = terminal!!.join()
                terminal = null
                val text = state?.cells?.joinToString("") { String(Character.toChars(it.codePoint)) }
                helper.assertTrue(text?.contains("FRAME-PENDING") == true, "frame has not opened")
            }.thenExecute {
                oldEpoch = computer.terminalMachineId
                saved = computer.saveWithFullMetadata(helper.level.registryAccess())
                helper.level.removeBlockEntity(helper.absolutePos(computerPosition))
                helper.assertTrue(computer.isRemoved, "display writer computer did not unload")
            }.thenWaitUntil {
                helper.assertTrue(display.hasDisplayPixels(), "unloaded writer erased the published image")
            }.thenExecute {
                val position = helper.absolutePos(computerPosition)
                computer =
                    BlockEntity.loadStatic(
                        position,
                        helper.level.getBlockState(position),
                        requireNotNull(saved),
                        helper.level.registryAccess(),
                    ) as NeoForgeComputerBlockEntity
                helper.level.setBlockEntity(computer)
                helper.assertTrue(computer.computerId() == computerId, "display writer identity changed")
            }.thenWaitUntil {
                // Only resuming inside the open frame can publish both pixels; cold start cannot satisfy this.
                helper.assertTrue(
                    display.displayColor(0, 0) == 0x445566 && display.displayColor(1, 0) == 0x778899,
                    "hibernation did not commit frame: pixels=${display.displayColor(
                        0,
                        0,
                    )},${display.displayColor(1, 0)} state=${computer.runtimeState}",
                )
                helper.assertTrue(computer.terminalMachineId != oldEpoch, "restored display writer retained its old epoch")
            }.thenExecute {
                PeripheralNetworkGameTestFixtures.unbind(helper, displayPosition)
            }.thenWaitUntil {
                helper.assertTrue(display.hasDisplayPixels(), "network removal erased the published image")
            }.thenSucceed()
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/graphical-display-hibernation.cpkt")) {
            "missing generated text display GameTest fixture"
        }.use { it.readAllBytes() }
}
