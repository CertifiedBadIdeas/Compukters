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

package ru.lazyhat.compukterkraft.common.computer.network.retained

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlockEntity
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice

sealed interface RetainedDisplayBinding {
    data class Menu(
        val containerId: Int,
    ) : RetainedDisplayBinding {
        init {
            require(containerId >= 0) { "Retained display menu container ID must be non-negative" }
        }
    }

    data class Notebook(
        val dimension: ResourceKey<Level>,
        val blockPos: BlockPos,
    ) : RetainedDisplayBinding
}

class RetainedDisplayAttachServerMessage : NetworkMessage<ServerNetworkContext> {
    val computerId: Int
    val binding: RetainedDisplayBinding

    constructor(computerId: Int, binding: RetainedDisplayBinding) {
        requireComputerId(computerId)
        this.computerId = computerId
        this.binding = binding
    }

    constructor(buffer: FriendlyByteBuf) {
        computerId = buffer.readVarInt().also(::requireComputerId)
        binding =
            when (val kind = buffer.readUnsignedByte().toInt()) {
                MENU_BINDING -> {
                    RetainedDisplayBinding.Menu(buffer.readVarInt())
                }

                NOTEBOOK_BINDING -> {
                    val dimension = ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation())
                    RetainedDisplayBinding.Notebook(dimension, buffer.readBlockPos())
                }

                else -> {
                    throw IllegalArgumentException("Unknown retained display binding kind: $kind")
                }
            }
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(computerId)
        when (val value = binding) {
            is RetainedDisplayBinding.Menu -> {
                buf.writeByte(MENU_BINDING)
                buf.writeVarInt(value.containerId)
            }

            is RetainedDisplayBinding.Notebook -> {
                buf.writeByte(NOTEBOOK_BINDING)
                buf.writeResourceLocation(value.dimension.location())
                buf.writeBlockPos(value.blockPos)
            }
        }
    }

    override fun handle(context: ServerNetworkContext) {
        val sender = context.sender()
        resolveDevice(sender, computerId, binding)?.attachRetainedDisplayViewer(sender.uuid)
    }

    override fun type(): MessageType<RetainedDisplayAttachServerMessage> = NetworkMessages.RETAINED_DISPLAY_ATTACH

    private companion object {
        const val MENU_BINDING = 1
        const val NOTEBOOK_BINDING = 2
    }
}

private fun resolveDevice(
    sender: net.minecraft.server.level.ServerPlayer,
    computerId: Int,
    binding: RetainedDisplayBinding,
): RuntimeDevice? {
    return when (binding) {
        is RetainedDisplayBinding.Menu -> {
            val menu = sender.containerMenu as? ComputerMenu ?: return null
            if ((sender.containerMenu).containerId != binding.containerId) return null
            menu.serverSide.device.takeIf { it.deviceId == computerId }
        }

        is RetainedDisplayBinding.Notebook -> {
            val level = sender.server.getLevel(binding.dimension) ?: return null
            if (sender.serverLevel() !== level || !level.isLoaded(binding.blockPos)) return null
            if (sender.distanceToSqr(
                    binding.blockPos.x + 0.5,
                    binding.blockPos.y + 0.5,
                    binding.blockPos.z + 0.5,
                ) > MAX_NOTEBOOK_OBSERVE_DISTANCE_SQUARED
            ) {
                return null
            }
            val notebook = level.getBlockEntity(binding.blockPos) as? NotebookBlockEntity ?: return null
            if (notebook.computerID != computerId) return null
            ServerContext.get(computerId)
        }
    }
}

internal fun requireComputerId(computerId: Int) {
    require(computerId > 0) { "Retained display computer ID must be positive" }
}

internal const val MAX_RETAINED_DISPLAY_PAYLOAD_BYTES = 524_288
private const val MAX_NOTEBOOK_OBSERVE_DISTANCE_SQUARED = 64.0 * 64.0
