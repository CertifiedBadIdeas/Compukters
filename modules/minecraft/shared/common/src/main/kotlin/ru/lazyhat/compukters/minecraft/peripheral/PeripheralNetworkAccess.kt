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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.UUID

/** Loader adapter for saved exact-instance stamps and configured access range. */
object PeripheralNetworkAccess {
    private var stamps: ((BlockEntity, Boolean) -> UUID?)? = null
    private var range: () -> Int = { 64 }

    @JvmStatic
    fun install(
        stamp: (BlockEntity, Boolean) -> UUID?,
        radius: () -> Int,
    ) {
        check(stamps == null) { "Peripheral identity adapter is already installed" }
        stamps = stamp
        range = radius
    }

    internal fun instance(
        entity: BlockEntity,
        create: Boolean = false,
    ): UUID? = stamps?.invoke(entity, create)

    internal fun radius(): Int = range().coerceIn(1, 1024)

    internal fun network(
        level: ServerLevel,
        computer: BlockPos,
    ): PeripheralNetwork? {
        if (!level.hasChunkAt(computer)) return null
        val entity = level.getBlockEntity(computer) ?: return null
        val id = instance(entity) ?: return null
        return PeripheralNetworkStorage.get(level).directory.networkOf(id)
    }

    internal fun available(
        level: ServerLevel,
        computer: BlockPos,
        member: PeripheralNetworkMember,
    ): Boolean {
        if (member.isComputer || !peripheralInRange(level.dimension().toString(), computer, member.identity, radius())) return false
        val position = member.identity.anchor
        if (!level.hasChunkAt(position)) return false
        val entity = level.getBlockEntity(position) ?: return false
        if (instance(entity) != member.instance) return false
        return net.minecraft.core.Direction.entries.any { face ->
            member.identity in PeripheralDeviceNames.resolveContact(level, position, face)
        }
    }
}
