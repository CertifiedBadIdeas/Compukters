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

package ru.lazyhat.compukterkraft.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Nameable
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.compukterkraftMod
import ru.lazyhat.compukterkraft.computer.ServerComputer
import ru.lazyhat.compukterkraft.context.ServerContext
import ru.lazyhat.compukterkraft.utils.computerID
import ru.lazyhat.compukterkraft.utils.computerLabel
import ru.lazyhat.compukterkraft.utils.ifServerSide
import ru.lazyhat.compukterkraft.utils.updateBlock

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

    private var fresh = false
    private var _label: String? = null
        set(value) {
            compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.setLabel $value")
            field = value
        }
    private var _computerID: Int? = null
        set(value) {
            compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.setComputerId $value")
            field = value
        }

    var label: String?
        get() = _label
        set(value) {
            compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.setLabelPublic $value")
            value
                ?.ifServerSide(level)
                ?.takeIf { _label != value }
                ?.let {
                    _label = value
                    updateBlock()
                }
        }

    var computerID: Int?
        get() = _computerID
        set(value) {
            compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.setComputerIdPublic $value")
            value
                ?.ifServerSide(level)
                ?.takeIf { _computerID != value }
                ?.let {
                    _computerID = value
                    updateBlock()
                }
        }

    init {
        compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity init ID: $_computerID, $_label")
    }

    abstract fun updateBlockState(newState: ComputerState)

    abstract fun createComputer(id: Int): ServerComputer

    fun serverTick() {
        if (level?.isClientSide ?: true) return
        if (_computerID == null) return
    }

    fun createServerComputer(): ServerComputer {
        val server = level?.server ?: error("Cannot access server computer on the client.")
        val serverLevel = level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server computer on the client.")

        compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.createServerComputer() ID: $_computerID")

        return _computerID
            ?.let {
                ServerContext.registry.getServerComputer(it)
            }
            ?: run {
                createComputer(
                    _computerID
                        ?: (0..9)
                            .random()
                            .also { computerID = it },
                ).also {
                    ServerContext.registry.addServerComputer(it)
                    fresh = true
                }
            }
    }

    override fun saveAdditional(tag: CompoundTag) {
        tag.computerID = _computerID
        tag.computerLabel = _label
        compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.saveAdditional() tag: $tag")

        super.saveAdditional(tag)
    }

    override fun load(tag: CompoundTag) {
        compukterkraftMod.LOGGER.info("AbstractComputerBlockEntity.load() tag: $tag")
        _computerID = tag.computerID
        _label = tag.computerLabel
    }

    override fun getName(): Component = customName ?: Component.translatable(blockState.block.getDescriptionId())

    override fun hasCustomName(): Boolean = !_label.isNullOrEmpty()

    override fun getCustomName(): Component? = _label?.takeIf { it.isEmpty() }?.let { Component.literal(it) }
}
