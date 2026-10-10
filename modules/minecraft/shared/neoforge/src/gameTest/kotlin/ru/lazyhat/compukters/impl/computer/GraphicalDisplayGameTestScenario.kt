/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.display.DisplayAssembly
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import java.util.concurrent.CompletableFuture

internal object GraphicalDisplayGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        val first = BlockPos(6, 3, 3)
        val second = BlockPos(4, 2, 3)
        helper.setBlock(computerPosition, CompuktersRegistry.COMPUTER.get())
        for (position in listOf(first, second)) {
            helper.setBlock(
                position,
                CompuktersRegistry.DISPLAY
                    .get()
                    .defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.NORTH),
            )
        }
        val initialOverlay =
            ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer.collect(
                helper.level,
                helper.absolutePos(first),
            )
        helper.assertTrue(
            initialOverlay.devices
                .first {
                    it.position == helper.absolutePos(first)
                }.screen == null,
            "overlay created an uninitialized screen",
        )
        val player = helper.makeMockServerPlayerInLevel()
        val stack = ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get())
        val selections = mutableMapOf<String, String>()
        for (position in listOf(first, second)) {
            val absolute = helper.absolutePos(position)
            player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
            DisplayAssembly.click(player, stack, absolute, { selections[it].orEmpty() }, { key, value ->
                if (value == null) selections.remove(key) else selections[key] = value
            })
        }
        val display = helper.level.getBlockEntity(helper.absolutePos(first)) as DisplayBlockEntity
        val other = helper.level.getBlockEntity(helper.absolutePos(second)) as DisplayBlockEntity
        val id = display.screenIdentity()
        helper.assertTrue(id != null && other.screenIdentity() == id, "configurator did not create one composite screen")
        ComputerPeripheralNames.setName(
            helper.level,
            ComputerPeripheralIdentity("compukters-display", helper.absolutePos(first), "text"),
            "panel",
        )
        PeripheralNetworkGameTestFixtures.bind(helper, computerPosition, first, second)
        val beforeOverlayColor = display.displayColor(0, 0)
        val overlay =
            ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer
                .collect(helper.level, helper.absolutePos(first))
        helper.assertTrue(
            overlay.screens.any {
                it.id == id && it.panels.size == 2 && it.columns == 3 && it.rows == 2
            },
            "overlay lost composite geometry or holes",
        )
        helper.assertTrue(
            overlay.devices.any {
                it.position == helper.absolutePos(computerPosition) && it.network != null
            },
            "overlay lost network membership",
        )
        helper.assertTrue(
            display.screenIdentity() == id && display.displayColor(0, 0) == beforeOverlayColor,
            "holding configurator changed the screen",
        )
        helper.assertTrue(
            ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer.collect(
                player,
                net.minecraft.world.InteractionHand.MAIN_HAND,
            ) ==
                null,
            "overlay accepted a player without a configurator",
        )
        val computer = helper.compuktersComputerBlockEntity(computerPosition)
        computer.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(computer.runtimeState == ProgramComputerState.WaitingForInput, "shell is not ready")
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
                        .thenAccept { accepted -> helper.assertTrue(accepted, "shell rejected panel command") }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "deployment is pending")
                setup!!.getNow(null)
                helper.assertTrue(
                    display.displayColor(0, 0) == 0xFFFF00 && display.displayColor(1, 0) == 0xFF00FF,
                    "Guest frame commit or exception abort failed",
                )
                helper.assertTrue(computer.runtimeState == ProgramComputerState.WaitingForInput, "drawing program did not exit")
            }.thenExecute {
                helper.assertTrue(display.screenMode() == 0, "applied ultra-low mode was not retained")
                helper.assertTrue(
                    display.displayColor(2, 2) == 0xFF0000 && display.displayColor(3, 2) == 0x0000FF,
                    "Guest image rows did not preserve RGB channels",
                )
                helper.assertTrue(display.screenIdentity() == other.screenIdentity(), "composite identity diverged")
                helper.setBlock(first, Blocks.AIR)
                helper.assertTrue(other.screenIdentity() == id, "removing a panel deleted the remaining canvas")
                helper.assertTrue(other.displayColor(0, 0) == 0xFFFF00, "pixels in a hole were lost")
                helper.setBlock(
                    first,
                    CompuktersRegistry.DISPLAY
                        .get()
                        .defaultBlockState()
                        .setValue(DisplayBlock.FACING, Direction.NORTH),
                )
                val unjoinedOverlay =
                    ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer.collect(
                        helper.level,
                        helper.absolutePos(first),
                    )
                helper.assertTrue(
                    unjoinedOverlay.screens.any {
                        it.id == id && helper.absolutePos(first) !in it.panels
                    },
                    "overlay treated a replacement as an existing member",
                )
                val absolute = helper.absolutePos(first)
                player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
                DisplayAssembly.click(player, stack, absolute, { selections[it].orEmpty() }, { key, value ->
                    if (value == null) selections.remove(key) else selections[key] = value
                })
                val replacement = helper.level.getBlockEntity(absolute) as DisplayBlockEntity
                helper.assertTrue(
                    replacement.screenIdentity() == id && replacement.displayColor(0, 0) == 0xFFFF00,
                    "joining a replacement panel did not recover the saved hole image",
                )
                val tile =
                    com.mojang.serialization.Codec.BYTE_BUFFER
                        .fieldOf("display_rgb")
                        .codec()
                        .parse(net.minecraft.nbt.NbtOps.INSTANCE, replacement.getUpdateTag(helper.level.registryAccess()))
                        .result()
                        .orElseThrow()
                helper.assertTrue(
                    tile.get(0).toInt() and 255 == 255 && tile.get(1).toInt() and 255 == 255 && tile.get(2) == 0.toByte(),
                    "replacement panel update packet did not recover its RGB tile",
                )
                helper.setBlock(first, Blocks.AIR)
                helper.setBlock(second, Blocks.AIR)
                helper.setBlock(
                    second,
                    CompuktersRegistry.DISPLAY
                        .get()
                        .defaultBlockState()
                        .setValue(DisplayBlock.FACING, Direction.NORTH),
                )
                val fresh = helper.level.getBlockEntity(helper.absolutePos(second)) as DisplayBlockEntity
                helper.assertTrue(
                    fresh.screenIdentity() != id && fresh.screenMode() == 2 && !fresh.hasDisplayPixels(),
                    "last panel destruction retained the old canvas",
                )
            }.thenSucceed()
    }
}
