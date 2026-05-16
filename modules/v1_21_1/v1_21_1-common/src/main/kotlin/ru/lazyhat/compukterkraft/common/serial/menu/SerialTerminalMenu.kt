/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.common.serial.menu

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.menu.MenuSide
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.serial.SerialConsoleBuffer
import ru.lazyhat.compukterkraft.common.serial.network.client.SerialConsoleOutputClientMessage
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceSerialEndpoint

class SerialTerminalMenu(
    menuType: MenuType<out AbstractComputerMenu>,
    containerId: Int,
    playerInventory: Inventory,
    family: DeviceFamily,
    computer: RuntimeDevice?,
    menuData: ComputerContainerData?,
    private val ownerPlayer: ServerPlayer?,
) : AbstractComputerMenu(
        menuType,
        containerId,
        { true },
        family,
        computer,
        menuData,
    ) {
    val serialBuffer = SerialConsoleBuffer()
    private var lastSentOutputSize: Int = 0

    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        menuData: ComputerContainerData,
    ) : this(
        menuType,
        containerId,
        playerInventory,
        menuData.family,
        null,
        menuData,
        null,
    )

    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        computer: RuntimeDevice,
        ownerPlayer: ServerPlayer,
    ) : this(
        menuType,
        containerId,
        playerInventory,
        computer.family,
        computer,
        null,
        ownerPlayer,
    )

    fun applySerialOutput(
        bytes: ByteArray,
        reset: Boolean,
    ) {
        serialBuffer.appendOutput(bytes, reset)
    }

    fun pushSerialInput(bytes: ByteArray) {
        val device = (side as? MenuSide.Server)?.device as? RuntimeDeviceSerialEndpoint ?: return
        device.pushSerialInput(bytes)
    }

    override fun quickMoveStack(
        player: Player,
        index: Int,
    ): ItemStack = ItemStack.EMPTY

    override fun broadcastChanges() {
        super.broadcastChanges()
        val device = (side as? MenuSide.Server)?.device as? RuntimeDeviceSerialEndpoint ?: return
        val player = ownerPlayer ?: return
        val snapshot = device.serialOutputSnapshot()
        val reset = snapshot.size < lastSentOutputSize
        val bytes =
            if (reset) {
                snapshot
            } else {
                snapshot.copyOfRange(lastSentOutputSize, snapshot.size)
            }
        if (reset || bytes.isNotEmpty()) {
            ServerNetworking.sendToPlayer(
                SerialConsoleOutputClientMessage(
                    containerId = containerId,
                    bytes = bytes,
                    reset = reset,
                ),
                player,
            )
        }
        lastSentOutputSize = snapshot.size
    }
}
