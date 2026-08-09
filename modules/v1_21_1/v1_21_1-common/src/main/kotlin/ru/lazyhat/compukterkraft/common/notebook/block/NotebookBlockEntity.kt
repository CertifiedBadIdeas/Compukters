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

package ru.lazyhat.compukterkraft.common.notebook.block

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.block.ComputerRuntimeDeviceFactory
import ru.lazyhat.compukterkraft.common.computer.block.ComputerState
import ru.lazyhat.compukterkraft.common.computer.menu.NotebookComputerMenu
import ru.lazyhat.compukterkraft.common.computer.module.SdkModuleBay
import ru.lazyhat.compukterkraft.common.computer.module.sdkArtifactIdentity
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice

open class NotebookBlockEntity(
    type: BlockEntityType<out NotebookBlockEntity>,
    pos: BlockPos,
    state: BlockState,
) : AbstractComputerBlockEntity(type, pos, state, familyOf(state)) {
    private val sdkModuleItems: NonNullList<ItemStack> = NonNullList.withSize(1, ItemStack.EMPTY)
    val sdkModuleBay =
        SdkModuleBay(
            items = sdkModuleItems,
            artifactIdentity = { it.sdkArtifactIdentity },
            isKnownArtifact = ModObjects.isKnownSdkArtifactIdentity,
            isRuntimeOn = ::isRuntimeDeviceOn,
            commitMutation = ::commitPoweredOffHardwareChange,
        )
    private var notebookMenuViewers: Int = 0

    override fun createComputer(id: Int): RuntimeDevice =
        ComputerRuntimeDeviceFactory.createK16Computer(
            level = level as ServerLevel,
            tile = this,
            deviceId = id,
            moduleIdentity = { sdkModuleBay.installedArtifactIdentity },
        )

    override fun updateBlockState(newState: ComputerState) {
        val currentState = level?.getBlockState(blockPos) ?: return
        if (!canApplyRuntimeBlockStateUpdate(currentState)) return
        currentState
            .takeIf { it.getValue(AbstractComputerBlock.state) != newState }
            ?.let {
                level?.setBlock(
                    blockPos,
                    currentState.setValue(AbstractComputerBlock.state, newState),
                    Block.UPDATE_CLIENTS,
                )
            }
    }

    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu =
        NotebookComputerMenu(
            ModObjects.computerMenuType(),
            containerId,
            playerInventory,
            getOrCreateRuntimeDevice(),
            sdkModuleBay,
            onRemoved = ::notebookMenuClosed,
        ).also {
            notebookMenuOpened()
        }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.saveAdditional(tag, registries)
        val bayTag = CompoundTag()
        ContainerHelper.saveAllItems(bayTag, sdkModuleItems, registries)
        tag.put(SDK_MODULE_BAY_TAG, bayTag)
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        sdkModuleItems[0] = ItemStack.EMPTY
        if (tag.contains(SDK_MODULE_BAY_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(SDK_MODULE_BAY_TAG), sdkModuleItems, registries)
        }
        sdkModuleBay.restoreStoredItem(sdkModuleItems[0])
    }

    fun notebookMenuOpened() {
        val level = level ?: return
        if (level.isClientSide) return

        if (notebookMenuViewers == 0) {
            setNotebookLidOpen(open = true)
        }
        notebookMenuViewers += 1
    }

    fun notebookMenuClosed() {
        val level = level ?: return
        if (level.isClientSide || notebookMenuViewers == 0) return

        notebookMenuViewers -= 1
        if (notebookMenuViewers == 0) {
            setNotebookLidOpen(open = false)
        }
    }

    protected open fun setNotebookLidOpen(open: Boolean) = Unit

    private companion object {
        const val SDK_MODULE_BAY_TAG: String = "SdkModuleBay"

        fun familyOf(state: BlockState): DeviceFamily =
            (state.block as? NotebookBlock)?.deviceFamily
                ?: error("NotebookBlockEntity requires NotebookBlock state")
    }
}
