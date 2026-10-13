/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.createaddition.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension
import net.neoforged.neoforge.common.util.FakePlayerFactory
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBindingResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorMode
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAvailability
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
import java.util.UUID

/** Real configurator actions at absolute world/plot positions, with persisted exact-device identity. */
internal object PeripheralNetworkGameTestFixtures {
    fun bind(
        helper: GameTestHelper,
        vararg positions: BlockPos,
    ) {
        val player = player(helper, positions.first())
        val stack = player.mainHandItem
        val item = stack.item as PeripheralConfiguratorItem
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        for (position in positions) {
            player.setPos(requireNotNull(PeripheralWorldPositions.project(helper.level, Vec3.atCenterOf(position))))
            val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, position, Direction.WEST)
            helper.assertTrue(
                result in listOf(PeripheralBindingResult.CREATED, PeripheralBindingResult.SELECTED, PeripheralBindingResult.BOUND),
                "Could not bind $position: $result",
            )
        }
        helper.assertTrue(item.selectedNetwork(stack) != null, "Configurator did not select a network")
    }

    fun removeReplaced(
        helper: GameTestHelper,
        computer: BlockPos,
        name: String,
    ) {
        val player = player(helper, computer)
        val item = player.mainHandItem.item as PeripheralConfiguratorItem
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        val selection = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, computer, Direction.WEST)
        helper.assertTrue(selection == PeripheralBindingResult.SELECTED, "Computer lost its existing network: $selection")
        val data = (checkNotNull(helper.level.getBlockEntity(computer)) as IBlockEntityExtension).persistentData
        val context =
            PeripheralConfiguratorContext(computer, Direction.WEST, UUID.fromString(data.getString("compukters_peripheral_instance")))
        val snapshot = PeripheralConfiguratorServer.inspect(helper.level, context, PeripheralConfiguratorMode.INSPECT_NETWORK)
        // The inspector no longer resolves a live name for a replaced device. These fixtures replace one member at a time.
        val replaced = snapshot.entries.filter { it.availability == PeripheralNetworkAvailability.REPLACED }
        helper.assertTrue(replaced.size == 1, "Expected one replaced $name member, got ${snapshot.entries}")
        val member = replaced.single()
        helper.assertTrue(
            PeripheralConfiguratorServer.removeNetworkMember(
                player,
                InteractionHand.MAIN_HAND,
                snapshot.context,
                checkNotNull(member.instance),
            ),
            "Inspector could not remove replaced $name member",
        )
    }

    fun assertNotPeripheral(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        val player = player(helper, position)
        val item = player.mainHandItem.item as PeripheralConfiguratorItem
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, position, Direction.WEST)
        helper.assertTrue(result == PeripheralBindingResult.INVALID_TARGET, "Non-peripheral was bound: $result")
    }

    fun unbind(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        val player = player(helper, position)
        val item = player.mainHandItem.item as PeripheralConfiguratorItem
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        player.setShiftKeyDown(true)
        try {
            val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, position, Direction.WEST)
            helper.assertTrue(result == PeripheralBindingResult.REMOVED, "Could not unbind $position: $result")
        } finally {
            player.setShiftKeyDown(false)
        }
    }

    fun name(
        helper: GameTestHelper,
        position: BlockPos,
        name: String,
    ): PeripheralConfiguratorSaveResult {
        val player = player(helper, position)
        val anchor =
            (helper.level.getBlockEntity(position) as? com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)
                ?.controllerBE
                ?.blockPos ?: position
        // The device controller owns the persisted stamp, even when interacting with another segment.
        val data = (helper.level.getBlockEntity(anchor) as? IBlockEntityExtension)?.persistentData
        val instance = runCatching { UUID.fromString(data?.getString("compukters_peripheral_instance")) }.getOrNull()
        val snapshot =
            PeripheralConfiguratorServer.inspect(
                helper.level,
                PeripheralConfiguratorContext(position, Direction.WEST, instance),
                PeripheralConfiguratorMode.EDIT_DEVICE,
            )
        return PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, snapshot.context, name)
    }

    private fun player(
        helper: GameTestHelper,
        position: BlockPos,
    ) = FakePlayerFactory.getMinecraft(helper.level).also {
        it.setShiftKeyDown(false)
        it.setPos(requireNotNull(PeripheralWorldPositions.project(helper.level, Vec3.atCenterOf(position))))
        it.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
    }
}
