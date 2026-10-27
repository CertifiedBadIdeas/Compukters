/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayMessage
import ru.lazyhat.compukters.minecraft.display.AttachedDisplaySurfaces
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayView
import ru.lazyhat.compukters.minecraft.display.DisplayAssembly
import ru.lazyhat.compukters.minecraft.display.displayPointerTarget
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBindingResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBlockTransfers
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import java.util.concurrent.CompletableFuture

internal object AttachedDisplayGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPos = BlockPos(2, 2, 2)
        val first = helper.absolutePos(BlockPos(6, 3, 3))
        val second = helper.absolutePos(BlockPos(4, 2, 3))
        helper.setBlock(computerPos, CompuktersRegistry.COMPUTER.get())
        val rectangle = AttachedDisplaySurfaces.rectangle(first, second, Direction.NORTH)
        rectangle.positions.forEach { helper.level.setBlockAndUpdate(it, Blocks.QUARTZ_BLOCK.defaultBlockState()) }
        val player = helper.makeMockServerPlayerInLevel()
        player.setPos(first.center.x, first.center.y, first.center.z - 2)
        val stack = ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get())
        val item = stack.item as PeripheralConfiguratorItem
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val selection = linkedMapOf<String, String>()
        for (pos in listOf(first, second)) {
            DisplayAssembly.click(player, stack, pos, { selection[it].orEmpty() }, { key, value ->
                if (value == null) selection.remove(key) else selection[key] = value
            }, Direction.NORTH)
        }
        helper.assertTrue(
            rectangle.positions.all {
                helper.level.getBlockState(it).`is`(Blocks.QUARTZ_BLOCK) &&
                    helper.level.getBlockEntity(it) == null
            },
            "surface assembly replaced ordinary backing blocks",
        )
        val hit = BlockHitResult(Vec3(first.x + 0.5, first.y + 0.5, first.z.toDouble()), Direction.NORTH, first, false)
        val canvas = requireNotNull(displayPointerTarget(helper.level, hit)).canvas
        helper.assertTrue(displayPointerTarget(helper.level, hit.withDirection(Direction.SOUTH)) == null, "back face exposed display input")
        val screen = ConfiguratorOverlayServer.collect(helper.level, first).screens.single { first in it.panels }
        helper.assertTrue(screen.columns == 3 && screen.rows == 2 && screen.panels.size == 6, "surface overlay lost its rectangle")
        item.selectMode(stack, 1)
        for (pos in listOf(helper.absolutePos(computerPos), first)) {
            player.setPos(pos.center.x, pos.center.y, pos.center.z - 2)
            helper.assertTrue(
                PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, pos, Direction.NORTH) in
                    setOf(PeripheralBindingResult.CREATED, PeripheralBindingResult.BOUND),
                "surface network binding failed",
            )
        }
        ComputerPeripheralNames.setName(helper.level, ComputerPeripheralIdentity("compukters-display", first, "text"), "panel")
        val computerWorld = helper.absolutePos(computerPos)
        val mainNetwork = requireNotNull(item.selectedNetwork(stack))
        val overlay = AttachedDisplaySurfaces.create(helper.level, computerWorld, computerWorld, Direction.SOUTH)
        item.selectNetwork(stack, null)
        player.setPos(computerWorld.center.x, computerWorld.center.y, computerWorld.center.z + 2)
        helper.assertTrue(
            PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, computerWorld, Direction.SOUTH) ==
                PeripheralBindingResult.CREATED,
            "computer face attachment could not join a separate network",
        )
        helper.assertTrue(
            PeripheralConfiguratorServer.save(
                player,
                InteractionHand.MAIN_HAND,
                PeripheralConfiguratorContext(computerWorld, Direction.SOUTH, overlay),
                "computer-face-panel",
            ) == PeripheralConfiguratorSaveResult.NAMED_DEVICE,
            "naming a face attachment renamed the backing computer's network",
        )
        item.selectNetwork(stack, mainNetwork)
        // The Guest below must still discover the original wall panel through the computer's own network.
        val computer = helper.compuktersComputerBlockEntity(computerPos)
        computer.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(computer.runtimeState == ProgramComputerState.WaitingForInput, "surface shell not ready")
            }.thenExecute {
                val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/graphical-display.cpkt")).use { it.readAllBytes() }
                setup =
                    computer
                        .verifyForDeployAsync(bytes)
                        .thenCompose { candidate ->
                            computer.executableRevisionAsync("/home/panel").thenCompose { revision ->
                                computer.deployAsync("/home/panel", requireNotNull(revision), requireNotNull(candidate))
                            }
                        }.thenCompose { computer.submitTerminalTextAsync("panel") }
                        .thenCompose { computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS) }
                        .thenAccept { helper.assertTrue(it, "surface program command rejected") }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "surface deploy pending")
                setup!!.getNow(null)
                helper.assertTrue(
                    canvas.pixelAt(0, 0) == 0xFFFF00 && canvas.pixelAt(1, 0) == 0xFF00FF,
                    "existing Guest graphics API did not draw on ordinary blocks",
                )
            }.thenExecute {
                val click = BlockHitResult(Vec3(second.x + .8125, second.y + .875, second.z.toDouble()), Direction.NORTH, second, false)
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                player.setPos(second.center.x, second.center.y, second.center.z - 2)
                val interaction =
                    net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                        player,
                        InteractionHand.MAIN_HAND,
                        second,
                        click,
                    )
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                    .post(interaction)
                helper.assertTrue(interaction.isCanceled, "attached panel did not consume use before its backing block")
                val target = requireNotNull(displayPointerTarget(helper.level, click))
                val input =
                    ru.lazyhat.compukters.core.display
                        .DisplayPointerController()
                input.update(player.uuid, 0, true, target)
                input.update(player.uuid, 1, true, target.copy(x = target.x + 1))
                input.update(player.uuid, 2, false, null)
            }.thenWaitUntil {
                helper.assertTrue(
                    computer.runtimeState == ProgramComputerState.WaitingForInput,
                    "surface Guest did not finish pointer input",
                )
                helper.assertTrue(
                    canvas.pixelAt(35, 18) == 0x123456 && canvas.pixelAt(36, 18) == 0x654321,
                    "surface pointer did not traverse Guest bindings",
                )
            }.thenExecute {
                helper.assertTrue(canvas.opacity == 128, "Guest whole-panel opacity was not applied")
                val messages = mutableListOf<AttachedDisplayMessage>()
                AttachedDisplayView().update(helper.level, first.center, messages::add)
                helper.assertTrue(
                    messages.filterIsInstance<AttachedDisplayMessage.Manifest>().any {
                        it.surface == screen.id &&
                            it.members.size == 6
                    },
                    "new viewer did not receive surface metadata",
                )
                helper.assertTrue(
                    messages.filterIsInstance<AttachedDisplayMessage.Tile>().count {
                        it.surface == screen.id && it.base == -1L &&
                            it.rgb.size == 16 * 16 * 3
                    } ==
                        6,
                    "new viewer did not receive independent full tiles",
                )
                val offset = BlockPos(10, 0, 0)
                val positions = rectangle.positions.associateWith { it.offset(offset) }
                PeripheralBlockTransfers.begin(helper.level, helper.level, positions).use {
                    for ((from, to) in positions) {
                        helper.level.setBlockAndUpdate(to, helper.level.getBlockState(from))
                        helper.level.setBlockAndUpdate(from, Blocks.AIR.defaultBlockState())
                    }
                }
                helper.assertTrue(displayPointerTarget(helper.level, hit) == null, "old surface location was revived after transfer")
                val moved =
                    requireNotNull(
                        displayPointerTarget(
                            helper.level,
                            BlockHitResult(hit.location.add(10.0, 0.0, 0.0), Direction.NORTH, first.offset(offset), false),
                        ),
                    )
                helper.assertTrue(moved.canvas.pixelAt(35, 18) == 0x123456, "transfer lost the published surface image")
                helper.assertTrue(moved.canvas.opacity == 128, "transfer lost whole-panel opacity")
                val movedScreen =
                    ConfiguratorOverlayServer.collect(helper.level, first.offset(offset)).screens.single {
                        first.offset(offset) in
                            it.panels
                    }
                helper.assertTrue(movedScreen.id == screen.id && movedScreen.name == "panel", "transfer lost surface identity or name")
                helper.level.setBlockAndUpdate(first.offset(offset), Blocks.AIR.defaultBlockState())
                helper.level.setBlockAndUpdate(first.offset(offset), Blocks.QUARTZ_BLOCK.defaultBlockState())
                helper.assertTrue(
                    displayPointerTarget(
                        helper.level,
                        BlockHitResult(hit.location.add(10.0, 0.0, 0.0), Direction.NORTH, first.offset(offset), false),
                    ) ==
                        null,
                    "replacement inherited old attached tile",
                )
            }.thenExecute {
                val a = helper.absolutePos(BlockPos(3, 5, 6))
                val b = a.east()
                listOf(a, b).forEach { helper.level.setBlockAndUpdate(it, Blocks.OAK_PLANKS.defaultBlockState()) }
                val id = AttachedDisplaySurfaces.create(helper.level, a, b, Direction.UP)

                fun aim(pos: BlockPos) = BlockHitResult(Vec3(pos.x + .5, pos.y + 1.0, pos.z + .5), Direction.UP, pos, false)
                val before = requireNotNull(displayPointerTarget(helper.level, aim(a))).canvas
                val owner = Any()
                before.acquire(owner) { true }
                before.pixel(owner, 0, 0, 0x112233)
                before.pixel(owner, 64, 0, 0x445566)
                before.setOpacity(owner, 63)
                val move = mapOf(a to a.offset(5, 0, 0), b to b.offset(5, 0, 0))
                PeripheralBlockTransfers.begin(helper.level, helper.level, move).use { }
                helper.assertTrue(
                    ConfiguratorOverlayServer.collect(helper.level, a).screens.any {
                        it.id == id
                    },
                    "rolled-back surface move changed identity",
                )
                PeripheralBlockTransfers.begin(helper.level, helper.level, move).use {
                    helper.level.setBlockAndUpdate(move.getValue(a), helper.level.getBlockState(a))
                    helper.level.setBlockAndUpdate(a, Blocks.AIR.defaultBlockState())
                }
                val moved = requireNotNull(displayPointerTarget(helper.level, aim(move.getValue(a)))).canvas
                val retained = requireNotNull(displayPointerTarget(helper.level, aim(b))).canvas
                helper.assertTrue(
                    moved.pixelAt(0, 0) == 0x112233 && retained.pixelAt(0, 0) == 0x445566 && moved.opacity == 63 && retained.opacity == 63,
                    "partial transfer lost independent tiles or opacity",
                )
            }.thenSucceed()
    }
}
