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

package ck.mod.block

import ck.mod.LOGGER
import ck.mod.computer.ServerComputer
import ck.mod.context.ServerContext
import ck.mod.utils.computerID
import ck.mod.utils.computerLabel
import ck.mod.utils.ifServerSide
import ck.mod.utils.updateBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Nameable
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

abstract class AbstractComputerBlockEntity(
    type: BlockEntityType<out AbstractComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: ComputerFamily,
) : BlockEntity(type, pos, state),
    Nameable,
    MenuConstructor {
    var family: ComputerFamily = family
        private set
    private var _label: String? = null
        set(value) {
            LOGGER.info { "AbstractComputerBlockEntity.setLabel $value" }
            field = value
        }
    private var _computerID: Int? = null
        set(value) {
            LOGGER.info { "AbstractComputerBlockEntity.setComputerId $value" }
            field = value
        }

    var label: String?
        get() = _label
        set(value) {
            LOGGER.info { "AbstractComputerBlockEntity.setLabelPublic $value" }
            value
                ?.ifServerSide(level)
                ?.takeIf { _label != value }
                ?.let {
                    _label = value
                    _computerID
                        ?.let(ServerContext.computerManager::get)
                        ?.updateLabel(value)
                    updateBlock()
                }
        }

    var computerID: Int?
        get() = _computerID
        set(value) {
            LOGGER.info { "AbstractComputerBlockEntity.setComputerIdPublic $value" }
            value
                ?.ifServerSide(level)
                ?.takeIf { _computerID != value }
                ?.let {
                    _computerID = value
                    updateBlock()
                }
        }

    init {
        LOGGER.info { "AbstractComputerBlockEntity init ID: $_computerID, $_label" }
    }

    abstract fun updateBlockState(newState: ComputerState)

    abstract fun createComputer(id: Int): ServerComputer

    fun serverTick() {
        if (level?.isClientSide ?: true) return
        if (_computerID == null) return
        val computer = getOrCreateServerComputer()
        computer.serverTick()
        updateBlockState(if (computer.isOn) ComputerState.ON else ComputerState.OFF)
    }

    fun getOrCreateServerComputer(): ServerComputer {
        level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server computer on the client.")
        val resolvedComputerId =
            _computerID ?: ServerContext.allocateComputerId().also { allocatedComputerId ->
                computerID = allocatedComputerId
                ServerContext.computerManager.ensureWorkspaceInitialized(allocatedComputerId)
            }

        return _computerID
            ?.let {
                ServerContext.computerManager.get(it)
            }
            ?: run {
                createComputer(resolvedComputerId).also {
                    ServerContext.computerManager.add(it)
                }
            }
    }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        tag.computerID = _computerID
        tag.computerLabel = _label
        LOGGER.info { "AbstractComputerBlockEntity.saveAdditional() tag: $tag" }

        super.saveAdditional(tag, registries)
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        LOGGER.info { "AbstractComputerBlockEntity.load() tag: $tag" }
        _computerID = tag.computerID
        _label = tag.computerLabel
    }

    override fun setRemoved() {
        releaseServerComputer()
        super.setRemoved()
    }

    override fun getName(): Component = customName ?: Component.translatable(blockState.block.getDescriptionId())

    override fun hasCustomName(): Boolean = !_label.isNullOrEmpty()

    override fun getCustomName(): Component? = _label?.takeIf { it.isEmpty() }?.let { Component.literal(it) }

    protected fun releaseServerComputer() {
        if (level?.isClientSide ?: true) return
        _computerID
            .takeIf { ServerContext.isInitialized }
            ?.let(ServerContext.computerManager::remove)
            ?.close()
    }
}
