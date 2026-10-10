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
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.UUID

enum class PeripheralBindingResult {
    CREATED,
    SELECTED,
    BOUND,
    REMOVED,
    OTHER_NETWORK,
    MISSING_NETWORK,
    INVALID_TARGET,
    LIMIT_REACHED,
}

internal object PeripheralNetworkBinding {
    fun click(
        player: ServerPlayer,
        hand: InteractionHand,
        position: BlockPos,
        face: Direction,
    ): PeripheralBindingResult {
        val level = player.level() as? ServerLevel ?: return PeripheralBindingResult.INVALID_TARGET
        check(level.server.isSameThread)
        if (!level.hasChunkAt(position) || player.distanceToSqr(position.center) > 64.0) return PeripheralBindingResult.INVALID_TARGET
        val stack = player.getItemInHand(hand)
        val item = stack.item as? PeripheralConfiguratorItem ?: return PeripheralBindingResult.INVALID_TARGET
        if (!item.networkMode(stack)) return PeripheralBindingResult.INVALID_TARGET
        val member = resolveMember(level, position, face, true) ?: return PeripheralBindingResult.INVALID_TARGET
        val storage = PeripheralNetworkStorage.get(level)
        val directory = storage.directory
        val existing = directory.networkOf(member.instance)
        if (player.isShiftKeyDown) {
            if (directory.remove(member.instance)) storage.setDirty()
            return PeripheralBindingResult.REMOVED
        }
        val selected = item.selectedNetwork(stack)
        if (selected == null) {
            val network =
                existing ?: runCatching { directory.createFor(member) }.getOrNull()
                    ?: return PeripheralBindingResult.LIMIT_REACHED
            if (existing == null) {
                storage.setDirty()
                PeripheralDeviceNameStorage.get(level).clearName(member.identity)
            }
            item.selectNetwork(stack, network.id)
            return if (existing == null) PeripheralBindingResult.CREATED else PeripheralBindingResult.SELECTED
        }
        if (directory.get(selected) == null) {
            item.selectNetwork(stack, null)
            return PeripheralBindingResult.MISSING_NETWORK
        }
        if (existing != null && existing.id != selected) return PeripheralBindingResult.OTHER_NETWORK
        if (runCatching { directory.bind(selected, member) }.isFailure) return PeripheralBindingResult.LIMIT_REACHED
        storage.setDirty()
        PeripheralDeviceNameStorage.get(level).clearName(member.identity)
        return PeripheralBindingResult.BOUND
    }

    fun resolveMember(
        level: ServerLevel,
        position: BlockPos,
        face: Direction,
        create: Boolean = false,
    ): PeripheralNetworkMember? {
        if (!level.hasChunkAt(position)) return null
        val contacted = level.getBlockEntity(position) ?: return null
        val identity =
            if (contacted is ComputerBlockEntity) {
                PeripheralDeviceIdentity(
                    PeripheralNetworkMember.COMPUTER_PROVIDER,
                    level.dimension().toString(),
                    position.immutable(),
                    "computer",
                )
            } else {
                PeripheralDeviceNames.resolveContact(level, position, face).singleOrNull() ?: return null
            }
        if (contacted is ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity) {
            val screen =
                ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess
                    .surface(level, contacted, create) ?: return null
            if (!DisplayNetworkAccess.migrate(level, screen)) return null
            return DisplayNetworkAccess.member(level, screen)
        }
        if (!level.hasChunkAt(identity.anchor)) return null
        val anchor = level.getBlockEntity(identity.anchor) ?: return null
        val instance = PeripheralNetworkAccess.instance(anchor, create) ?: return null
        return PeripheralNetworkMember(instance, identity, PeripheralDeviceNameStorage.get(level).directory.nameOf(identity))
    }

    fun removeSelected(
        player: ServerPlayer,
        hand: InteractionHand,
        context: PeripheralConfiguratorContext,
        member: UUID,
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val stack = player.getItemInHand(hand)
        val item = stack.item as? PeripheralConfiguratorItem ?: return false
        val directory = PeripheralNetworkStorage.get(level).directory
        val selected = item.selectedNetwork(stack) ?: return false
        if (!level.hasChunkAt(context.position) || player.distanceToSqr(context.position.center) > 64.0) return false
        if (context.instance == null || resolveMember(level, context.position, context.face)?.instance != context.instance) return false
        if (PeripheralNetworkAccess.network(level, context.position)?.id != selected) return false
        if (directory.networkOf(member)?.id != selected) return false
        if (!directory.remove(member)) return false
        PeripheralNetworkStorage.get(level).setDirty()
        return true
    }
}
