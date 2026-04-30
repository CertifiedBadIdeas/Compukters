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

package ru.lazyhat.compukterkraft.common.computer.block

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
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.ifServerSide
import ru.lazyhat.compukterkraft.common.utils.updateBlock
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice

abstract class AbstractComputerBlockEntity(
    type: BlockEntityType<out AbstractComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: DeviceFamily,
) : BlockEntity(type, pos, state),
    Nameable,
    MenuConstructor {
    var family: DeviceFamily = family
        private set
    private var _label: String? = null
    private var _computerID: Int? = null

    var label: String?
        get() = _label
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { _label != value }
                ?.let {
                    _label = value
                    _computerID
                        ?.let(ServerContext.deviceManager::get)
                        ?.let { device -> device.label = value }
                    updateBlock()
                }
        }

    var computerID: Int?
        get() = _computerID
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { _computerID != value }
                ?.let {
                    _computerID = value
                    updateBlock()
                }
        }

    abstract fun updateBlockState(newState: ComputerState)

    /** Adapter for [ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink]. */
    internal fun updateBlockState(isOn: Boolean) {
        updateBlockState(if (isOn) ComputerState.ON else ComputerState.OFF)
    }

    abstract fun createComputer(id: Int): RuntimeDevice

    fun serverTick() {
        if (level?.isClientSide ?: true) return
        if (_computerID == null) return
        val device = getOrCreateRuntimeDevice()
        device.serverTick()
        updateBlockState(if (device.isOn) ComputerState.ON else ComputerState.OFF)
    }

    fun getOrCreateRuntimeDevice(): RuntimeDevice {
        level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server device on the client.")
        val resolvedDeviceId =
            _computerID ?: ServerContext.allocateDeviceId().also { allocatedDeviceId ->
                computerID = allocatedDeviceId
                ServerContext.deviceManager.ensureWorkspaceInitialized(allocatedDeviceId)
            }

        return _computerID
            ?.let {
                ServerContext.deviceManager.get(it)
            }
            ?: run {
                createComputer(resolvedDeviceId).also {
                    ServerContext.deviceManager.add(it)
                }
            }
    }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        tag.computerID = _computerID
        tag.computerLabel = _label

        super.saveAdditional(tag, registries)
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)

        _computerID = tag.computerID
        _label = tag.computerLabel
    }

    override fun setRemoved() {
        releaseRuntimeDevice()
        super.setRemoved()
    }

    override fun getName(): Component = customName ?: Component.translatable(blockState.block.descriptionId)

    override fun hasCustomName(): Boolean = !_label.isNullOrEmpty()

    override fun getCustomName(): Component? = _label?.takeIf { it.isEmpty() }?.let { Component.literal(it) }

    protected fun releaseRuntimeDevice() {
        ifServerSide(level) {
            _computerID
                .takeIf { ServerContext.isInitialized }
                ?.let(ServerContext.deviceManager::remove)
                ?.close()
        }
    }
}
