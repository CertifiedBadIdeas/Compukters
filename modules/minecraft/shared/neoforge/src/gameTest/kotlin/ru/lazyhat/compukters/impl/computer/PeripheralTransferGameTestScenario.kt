/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBlockTransfers
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorMode
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer

/** Real block-copy lifecycle proves explicit transfer, copy protection and exceptional-scope cleanup. */
internal object PeripheralTransferGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computer = BlockPos(2, 3, 2)
        val display = BlockPos(5, 3, 2)
        val moved = BlockPos(8, 3, 2)
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(display, CompuktersRegistry.DISPLAY.get())
        val level = helper.level
        val from = helper.absolutePos(display)
        val to = helper.absolutePos(moved)
        val entity = level.getBlockEntity(from) as DisplayBlockEntity
        val canvas = requireNotNull(entity.screenIdentity())
        PeripheralNetworkGameTestFixtures.bind(helper, computer, display)
        ComputerPeripheralNames.setName(level, ComputerPeripheralIdentity("compukters-display", from, "text"), "moved-display")

        fun found() = ComputerPeripheralLookup.find(level, helper.absolutePos(computer), "compukters-display", "moved-display")
        // Copying an existing stamp outside an explicit move cannot hijack the loaded source canvas.
        val duplicate = from.south(4)
        level.setBlockAndUpdate(duplicate, entity.blockState)
        val clone =
            BlockEntity.loadStatic(
                duplicate,
                entity.blockState,
                entity.saveWithFullMetadata(level.registryAccess()),
                level.registryAccess(),
            ) as DisplayBlockEntity
        level.setBlockEntity(clone)
        helper.assertTrue(clone.screenIdentity() == null && found().identity?.anchor == from, "NBT copy stole the source canvas")
        val movedDuplicate = duplicate.south(2)
        PeripheralBlockTransfers.begin(level, level, mapOf(duplicate to movedDuplicate)).use {
            level.setBlockAndUpdate(movedDuplicate, clone.blockState)
            level.setBlockEntity(
                requireNotNull(
                    BlockEntity.loadStatic(
                        movedDuplicate,
                        clone.blockState,
                        clone.saveWithFullMetadata(level.registryAccess()),
                        level.registryAccess(),
                    ),
                ),
            )
            level.removeBlock(duplicate, false)
        }
        helper.assertTrue(
            found().identity?.anchor == from && entity.screenIdentity() == canvas,
            "moving an NBT copy stole the source canvas",
        )
        helper.assertTrue(
            (level.getBlockEntity(movedDuplicate) as DisplayBlockEntity).screenIdentity() == null,
            "moved NBT copy adopted source canvas",
        )
        level.removeBlock(movedDuplicate, false)
        try {
            PeripheralBlockTransfers.begin(level, level, mapOf(from to to)).use {
                val saved = entity.saveWithFullMetadata(level.registryAccess())
                level.setBlockAndUpdate(to, entity.blockState)
                level.setBlockEntity(requireNotNull(BlockEntity.loadStatic(to, entity.blockState, saved, level.registryAccess())))
                level.removeBlock(from, false)
                throw ExpectedTransferFailure()
            }
        } catch (_: ExpectedTransferFailure) {
            // Successfully moved blocks must survive a later exception, and the guard must be released.
        }
        helper.assertTrue(found().identity?.anchor == to, "transfer lost name or member address")
        helper.assertTrue((level.getBlockEntity(to) as DisplayBlockEntity).screenIdentity() == canvas, "transfer changed canvas UUID")
        val recovered = to.south(4)
        val rollback = PeripheralBlockTransfers.recover(level, level, mapOf(to to recovered), false)
        val saved = requireNotNull(level.getBlockEntity(to)).saveWithFullMetadata(level.registryAccess())
        val state = level.getBlockState(to)

        fun copy(target: BlockPos) {
            level.setBlockAndUpdate(target, state)
            level.setBlockEntity(requireNotNull(BlockEntity.loadStatic(target, state, saved, level.registryAccess())))
        }
        copy(recovered)
        helper.assertTrue(
            (level.getBlockEntity(recovered) as DisplayBlockEntity).screenIdentity() == null && found().identity?.anchor != recovered,
            "Staged recovery copy became an available device",
        )
        level.removeBlock(recovered, false)
        rollback.resolve()
        rollback.close()
        helper.assertTrue(found().identity?.anchor == to, "Rollback lost source membership")
        PeripheralBlockTransfers.recover(level, level, mapOf(to to recovered), true).use { recovery ->
            copy(recovered)
            level.removeBlock(to, false)
            recovery.resolve()
        }
        helper.assertTrue(found().identity?.anchor == recovered, "Committed recovery lost saved display identity")
        helper.assertTrue((level.getBlockEntity(recovered) as DisplayBlockEntity).screenIdentity() == canvas, "Recovery changed canvas")
        // Closing a recovery scope is cancellation, never an implicit authority decision.
        PeripheralBlockTransfers.recover(level, level, mapOf(recovered to to), true).close()
        helper.assertTrue(found().identity?.anchor == recovered, "Closed recovery changed authority")
        val computerEntity = level.getBlockEntity(helper.absolutePos(computer)) as ComputerBlockEntity
        PeripheralBlockTransfers.guard(level, listOf(computerEntity.blockPos)).use {
            helper.assertTrue(computerEntity.prepareTerminalAsync().getNow(null) == null, "Guard admitted a second computer runtime")
        }
        level.removeBlock(recovered, false)
        helper.assertTrue(found().status == ComputerPeripheralLookupStatus.MISSING, "ordinary removal remained guarded")
        val inspection =
            PeripheralConfiguratorServer.inspect(
                level,
                PeripheralConfiguratorContext(helper.absolutePos(computer), Direction.NORTH),
                PeripheralConfiguratorMode.INSPECT_NETWORK,
            )
        helper.assertTrue(inspection.entries.size == 1, "ordinary removal retained display membership")
        val failedComputer = BlockPos(11, 3, 2)
        helper.setBlock(failedComputer, CompuktersRegistry.COMPUTER.get())
        PeripheralNetworkGameTestFixtures.bind(helper, computer, failedComputer)
        val failedPosition = helper.absolutePos(failedComputer)
        PeripheralBlockTransfers.begin(level, level, mapOf(failedPosition to failedPosition.south(4))).use {
            level.removeBlock(failedPosition, false)
        }
        val afterFailure =
            PeripheralConfiguratorServer.inspect(
                level,
                PeripheralConfiguratorContext(helper.absolutePos(computer), Direction.NORTH),
                PeripheralConfiguratorMode.INSPECT_NETWORK,
            )
        helper.assertTrue(afterFailure.entries.size == 1, "failed move retained destroyed computer membership")
        helper.succeed()
    }

    private class ExpectedTransferFailure : RuntimeException()
}
