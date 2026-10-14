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

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import java.nio.ByteBuffer
import java.util.UUID

/** A network member has one authoritative address; unbound devices move relative to their computer. */
internal data class PeripheralCheckpointLocation(
    val anchor: BlockPos?,
    val member: UUID? = null,
) {
    fun resolve(
        dimension: String,
        directory: PeripheralNetworkDirectory,
    ): BlockPos? =
        anchor ?: member?.let { instance ->
            directory
                .networkOf(instance)
                ?.members
                ?.filter {
                    it.instance == instance && it.identity.dimension == dimension
                }?.map { it.identity.anchor }
                ?.distinct()
                ?.singleOrNull()
        }

    fun encode(computer: BlockPos): ByteArray =
        if (member != null) {
            ByteBuffer
                .allocate(18)
                .put(1)
                .put(1)
                .putLong(member.mostSignificantBits)
                .putLong(member.leastSignificantBits)
                .array()
        } else {
            val offset = requireNotNull(anchor).subtract(computer)
            ByteBuffer
                .allocate(14)
                .put(1)
                .put(0)
                .putInt(offset.x)
                .putInt(offset.y)
                .putInt(offset.z)
                .array()
        }

    companion object {
        fun decode(
            bytes: ByteArray,
            computer: BlockPos,
        ): PeripheralCheckpointLocation {
            // Explicit legacy format: old checkpoints retain their absolute address semantics.
            if (bytes.size == 8) return PeripheralCheckpointLocation(BlockPos.of(ByteBuffer.wrap(bytes).long))
            val input = ByteBuffer.wrap(bytes)
            require(bytes.size >= 2 && input.get().toInt() == 1) { "Unsupported peripheral location checkpoint" }
            return when (input.get().toInt()) {
                0 -> {
                    require(bytes.size == 14)
                    val x = Math.addExact(computer.x, input.int)
                    val y = Math.addExact(computer.y, input.int)
                    val z = Math.addExact(computer.z, input.int)
                    PeripheralCheckpointLocation(BlockPos(x, y, z))
                }

                1 -> {
                    require(bytes.size == 18)
                    PeripheralCheckpointLocation(null, UUID(input.long, input.long))
                }

                else -> {
                    throw IllegalArgumentException("Unsupported peripheral location kind")
                }
            }
        }
    }
}
