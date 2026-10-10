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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import ru.lazyhat.compukters.core.network.ComputerMessageBudget
import ru.lazyhat.compukters.core.network.ComputerMessageLink
import ru.lazyhat.compukters.core.network.ComputerNetworkEndpoint
import ru.lazyhat.compukters.core.network.ComputerNetworkFailure
import ru.lazyhat.compukters.core.network.ComputerNetworkPort
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableBlocks
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Volatile loaded-world inboxes, with dependency indexes so an unrelated cable never interrupts a receive. */
object ComputerCableMessages {
    private val worlds = WeakHashMap<ServerLevel, World>()

    fun endpoint(
        level: ServerLevel,
        position: BlockPos,
    ): ComputerNetworkEndpoint =
        ComputerNetworkEndpoint {
            check(level.server.isSameThread)
            val line =
                ComputerCableLinks.line(level, position)?.takeIf { it.computers.size == 2 }
                    ?: return@ComputerNetworkEndpoint null
            val world = worlds.getOrPut(level) { World() }
            var record = world.nodes[position]
            if (record != null && !record.valid(level)) {
                world.remove(record)
                record = null
            }
            if (record == null) {
                val stamps = line.computers.map { stamp(level, it) ?: return@ComputerNetworkEndpoint null }
                if (world.records.size >= MAXIMUM_CONNECTIONS || world.nodes.size + line.nodes.size > MAXIMUM_POSITIONS) {
                    return@ComputerNetworkEndpoint null
                }
                record = Record(line.nodes.toSet(), line.computers.toList(), stamps, ComputerMessageLink(world.budget))
                world.records += record
                record.nodes.forEach { world.nodes[it] = record }
                record.chunks.forEach { world.chunks.getOrPut(it) { hashSetOf() }.add(record) }
            }
            val bound = record
            val side = bound.positions.indexOf(position)
            if (side < 0) return@ComputerNetworkEndpoint null
            object : ComputerNetworkPort {
                override val connected: Boolean get() {
                    if (!bound.link.ports[side].connected) return false
                    if (!bound.valid(level)) {
                        world.remove(bound)
                        return false
                    }
                    return true
                }

                override fun send(message: ByteArray) {
                    checkConnected()
                    bound.link.ports[side].send(message)
                }

                override fun receive(): ByteArray? {
                    checkConnected()
                    return bound.link.ports[side].receive()
                }

                private fun checkConnected() {
                    if (!connected) throw ComputerNetworkFailure("Computer cable connection was interrupted")
                }
            }
        }

    fun changed(
        level: Level,
        position: BlockPos,
        cable: Boolean,
    ) {
        if (level !is ServerLevel) return
        check(level.server.isSameThread)
        val world = worlds[level] ?: return
        val affected = hashSetOf<Record>()
        world.nodes[position]?.let(affected::add)
        Direction.entries.forEach { direction ->
            val neighbor = position.relative(direction)
            // Adjacent PCs do not connect directly; a new cable can touch either kind of endpoint.
            if (cable || (level.hasChunkAt(neighbor) && PeripheralCableBlocks.contains(level.getBlockState(neighbor)))) {
                world.nodes[neighbor]?.let(affected::add)
            }
        }
        affected.forEach(world::remove)
    }

    fun chunkChanged(
        level: ServerLevel,
        chunk: ChunkPos,
    ) {
        check(level.server.isSameThread)
        val world = worlds[level] ?: return
        world.chunks[chunk.x to chunk.z]?.toList()?.forEach(world::remove)
    }

    fun clear(level: ServerLevel) {
        worlds.remove(level)?.records?.forEach { it.link.disconnect() }
    }

    private fun stamp(
        level: ServerLevel,
        position: BlockPos,
    ): Stamp? {
        if (!level.hasChunkAt(position)) return null
        val entity = level.getBlockEntity(position) as? ComputerBlockEntity ?: return null
        if (entity.isRemoved || !entity.peripheralResourcesAvailable()) return null
        return Stamp(WeakReference(entity), entity.terminalMachineId ?: return null)
    }

    private data class Stamp(
        val entity: WeakReference<ComputerBlockEntity>,
        val epoch: Long,
    )

    private class Record(
        val nodes: Set<BlockPos>,
        val positions: List<BlockPos>,
        val stamps: List<Stamp>,
        val link: ComputerMessageLink,
    ) {
        val chunks =
            nodes
                .flatMap { node ->
                    (listOf(node) + Direction.entries.map(node::relative)).map { (it.x shr 4) to (it.z shr 4) }
                }.toSet()

        fun valid(level: ServerLevel): Boolean =
            positions.indices.all { index ->
                val position = positions[index]
                val saved = stamps[index]
                val current = stamp(level, position)
                current != null && current.epoch == saved.epoch && current.entity.get() === saved.entity.get()
            }
    }

    private class World {
        val budget = ComputerMessageBudget()
        val records = hashSetOf<Record>()
        val nodes = hashMapOf<BlockPos, Record>()
        val chunks = hashMapOf<Pair<Int, Int>, MutableSet<Record>>()

        fun remove(record: Record) {
            record.link.disconnect()
            records.remove(record)
            record.nodes.forEach { if (nodes[it] === record) nodes.remove(it) }
            record.chunks.forEach { chunk ->
                chunks[chunk]?.let { set ->
                    set.remove(record)
                    if (set.isEmpty()) chunks.remove(chunk)
                }
            }
        }
    }

    private const val MAXIMUM_CONNECTIONS = 256
    private const val MAXIMUM_POSITIONS = 65_536
}
