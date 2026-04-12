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

package ru.lazyhat.compukterkraft.common.block

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
import ru.lazyhat.compukterkraft.common.computer.ServerComputer
import ru.lazyhat.compukterkraft.common.context.ServerContext
import ru.lazyhat.compukterkraft.common.utils.ifServerSide
import ru.lazyhat.compukterkraft.common.utils.updateBlock
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.core.content.ComputerBlockEntityPolicy
import ru.lazyhat.compukterkraft.core.content.ComputerPersistencePolicy

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
                ?.takeIf { ComputerBlockEntityPolicy.shouldPersistLabel(_label, value) }
                ?.let {
                    _label = it
                    _computerID
                        ?.let(ServerContext.computerManager::get)
                        ?.updateLabel(it)
                    updateBlock()
                }
        }

    var computerID: Int?
        get() = _computerID
        set(value) {
            LOGGER.info { "AbstractComputerBlockEntity.setComputerIdPublic $value" }
            value
                ?.ifServerSide(level)
                ?.takeIf { ComputerBlockEntityPolicy.shouldPersistComputerId(_computerID, value) }
                ?.let {
                    _computerID = it
                    updateBlock()
                }
        }

    init {
        LOGGER.info { "AbstractComputerBlockEntity init ID: $_computerID, $_label" }
    }

    abstract fun updateBlockState(newState: ComputerState)

    abstract fun createComputer(id: Int): ServerComputer

    fun serverTick() {
        if (!ComputerBlockEntityPolicy.shouldRunServerTick(level?.isClientSide ?: true, _computerID)) return
        val computer = getOrCreateServerComputer()
        computer.serverTick()
        updateBlockState(
            ComputerBlockEntityPolicy.desiredVisualState(computer.isOn).toMinecraftState(),
        )
    }

    fun getOrCreateServerComputer(): ServerComputer {
        level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server computer on the client.")
        val resolvedComputerId =
            ComputerBlockEntityPolicy.resolveComputerId(_computerID) {
                ServerContext.allocateComputerId().also { allocatedComputerId ->
                    computerID = allocatedComputerId
                    ServerContext.computerManager.ensureWorkspaceInitialized(allocatedComputerId)
                }
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
        tag.writeComputerPersistence(
            ComputerPersistencePolicy.snapshot(
                computerId = _computerID,
                label = _label,
            ),
        )
        LOGGER.info { "AbstractComputerBlockEntity.saveAdditional() tag: $tag" }

        super.saveAdditional(tag, registries)
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)

        LOGGER.info { "AbstractComputerBlockEntity.load() tag: $tag" }
        tag.readComputerPersistence().also { data ->
            _computerID = data.computerId
            _label = data.label
        }
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
