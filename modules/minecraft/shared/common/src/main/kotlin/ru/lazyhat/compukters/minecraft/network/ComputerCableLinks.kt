/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import ru.lazyhat.compukters.core.network.CableNode
import ru.lazyhat.compukters.core.network.ComputerCableResult
import ru.lazyhat.compukters.core.network.ComputerCableTopology
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableBlocks
import java.util.Locale
import java.util.WeakHashMap

/** Loaded physical components; no saved link identity or arbitrary peer selection. */
object ComputerCableLinks {
    private val caches = WeakHashMap<ServerLevel, MutableMap<BlockPos, ComputerCableResult.Line<BlockPos>>>()
    private const val MAXIMUM_CACHED_POSITIONS = 65_536

    fun invalidate(level: Level) {
        if (level is ServerLevel) caches.remove(level)
    }

    fun allowPlacement(
        context: BlockPlaceContext,
        type: CableNode,
    ): Boolean {
        val level = context.level as? ServerLevel ?: return true
        check(level.server.isSameThread)
        val position = context.clickedPos.immutable()
        val result = inspect(level, position, type)
        if (result is ComputerCableResult.Rejected) {
            (context.player as? net.minecraft.server.level.ServerPlayer)?.sendSystemMessage(
                Component.translatable("message.compukters.cable.${result.reason.name.lowercase(Locale.ROOT)}"),
            )
            return false
        }
        return true
    }

    fun peer(
        level: ServerLevel,
        computer: BlockPos,
    ): BlockPos? {
        check(level.server.isSameThread)
        if (!level.hasChunkAt(computer) || level.getBlockState(computer).block !is ComputerBlock) return null
        val cache = caches.getOrPut(level) { hashMapOf() }
        val result =
            cache[computer] ?: inspect(level, computer).also { result ->
                if (result is ComputerCableResult.Line) {
                    if (cache.size + result.nodes.size > MAXIMUM_CACHED_POSITIONS) cache.clear()
                    result.nodes.forEach { cache[it] = result }
                }
            }
        return (result as? ComputerCableResult.Line)?.computers?.takeIf { it.size == 2 }?.firstOrNull { it != computer }
    }

    private fun inspect(
        level: ServerLevel,
        start: BlockPos,
        proposed: CableNode? = null,
    ): ComputerCableResult<BlockPos> =
        ComputerCableTopology<BlockPos>(
            kind = { position ->
                if (position == start && proposed != null) {
                    proposed
                } else if (!level.hasChunkAt(position)) {
                    CableNode.UNLOADED
                } else {
                    val state = level.getBlockState(position)
                    when {
                        state.block is ComputerBlock -> CableNode.COMPUTER
                        PeripheralCableBlocks.contains(state) -> CableNode.CABLE
                        else -> CableNode.EMPTY
                    }
                }
            },
            neighbors = { position -> Direction.entries.map { position.relative(it).immutable() } },
        ).inspect(start)
}
