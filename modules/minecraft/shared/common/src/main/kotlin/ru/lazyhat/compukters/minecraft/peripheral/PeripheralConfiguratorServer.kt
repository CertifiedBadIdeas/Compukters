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

data class PeripheralConfiguratorContext(
    val position: BlockPos,
    val face: Direction,
    val instance: java.util.UUID? = null,
)

data class PeripheralConfiguratorEntry(
    val name: String?,
    val providerId: String,
    val deviceKey: String,
    val duplicate: Boolean,
    val instance: java.util.UUID? = null,
    val availability: PeripheralNetworkAvailability = PeripheralNetworkAvailability.AVAILABLE,
)

enum class PeripheralConfiguratorMode {
    EDIT_DEVICE,
    INSPECT_NETWORK,
}

data class PeripheralConfiguratorSnapshot(
    val context: PeripheralConfiguratorContext,
    val mode: PeripheralConfiguratorMode,
    val configuredName: String,
    val entries: List<PeripheralConfiguratorEntry>,
    val nameCounts: Map<String, Int>,
    val targetName: String?,
    val totalDevices: Int,
    val truncated: Boolean,
    val topologyLimitExceeded: Boolean,
)

enum class PeripheralConfiguratorSaveResult {
    NAMED_DEVICE,
    NAMED_NETWORK,
    MEMBER_REMOVED,
    INVALID_NAME,
    CONFLICT,
    INVALID_TARGET,
    OUT_OF_RANGE,
}

object PeripheralConfiguratorServer {
    @JvmStatic
    fun bind(
        player: ServerPlayer,
        hand: InteractionHand,
        position: BlockPos,
        face: Direction,
    ): PeripheralBindingResult = PeripheralNetworkBinding.click(player, hand, position, face)

    @JvmStatic
    fun removeNetworkMember(
        player: ServerPlayer,
        hand: InteractionHand,
        context: PeripheralConfiguratorContext,
        instance: java.util.UUID,
    ): Boolean = PeripheralNetworkBinding.removeSelected(player, hand, context, instance)

    private var opener: ((ServerPlayer, InteractionHand, PeripheralConfiguratorSnapshot) -> Unit)? = null

    @JvmStatic
    @Synchronized
    fun installOpener(value: (ServerPlayer, InteractionHand, PeripheralConfiguratorSnapshot) -> Unit) {
        require(opener == null || opener === value) { "peripheral configurator opener is already installed" }
        opener = value
    }

    @JvmStatic
    fun openDevice(
        player: ServerPlayer,
        hand: InteractionHand,
        position: BlockPos,
        face: Direction,
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (PeripheralDeviceNames.resolveContact(level, position, face).size != 1) return false
        val member = PeripheralNetworkBinding.resolveMember(level, position, face, true) ?: return false
        return open(
            player,
            hand,
            PeripheralConfiguratorContext(position.immutable(), face, member.instance),
            PeripheralConfiguratorMode.EDIT_DEVICE,
        )
    }

    @JvmStatic
    fun openComputer(
        player: ServerPlayer,
        hand: InteractionHand,
        position: BlockPos,
        face: Direction,
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val network = PeripheralNetworkAccess.network(level, position) ?: return false
        val stack = player.getItemInHand(hand)
        val item = stack.item as? PeripheralConfiguratorItem ?: return false
        item.selectNetwork(stack, network.id)
        val member = PeripheralNetworkBinding.resolveMember(level, position, face) ?: return false
        return open(
            player,
            hand,
            PeripheralConfiguratorContext(position.immutable(), face, member.instance),
            PeripheralConfiguratorMode.INSPECT_NETWORK,
        )
    }

    @JvmStatic
    fun openCable(
        player: ServerPlayer,
        hand: InteractionHand,
        position: BlockPos,
        face: Direction,
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (!PeripheralCableBlocks.contains(level.getBlockState(position))) return false
        return open(player, hand, PeripheralConfiguratorContext(position.immutable(), face), PeripheralConfiguratorMode.INSPECT_NETWORK)
    }

    @JvmStatic
    fun save(
        player: ServerPlayer,
        hand: InteractionHand,
        context: PeripheralConfiguratorContext,
        requestedName: String,
    ): PeripheralConfiguratorSaveResult {
        val normalized =
            runCatching { normalizePeripheralName(requestedName) }.getOrNull()
                ?: return PeripheralConfiguratorSaveResult.INVALID_NAME
        val level = player.level() as? ServerLevel ?: return PeripheralConfiguratorSaveResult.INVALID_TARGET
        if (!validRange(player, context)) return PeripheralConfiguratorSaveResult.OUT_OF_RANGE
        val stack = player.getItemInHand(hand)
        if (stack.item !is PeripheralConfiguratorItem) return PeripheralConfiguratorSaveResult.INVALID_TARGET
        val position = context.position
        val face = context.face
        if (context.instance == null || PeripheralNetworkBinding.resolveMember(level, position, face)?.instance != context.instance) {
            return PeripheralConfiguratorSaveResult.INVALID_TARGET
        }
        if (level.getBlockEntity(position) is ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity) {
            val network = PeripheralNetworkAccess.network(level, position) ?: return PeripheralConfiguratorSaveResult.INVALID_TARGET
            val storage = PeripheralNetworkStorage.get(level)
            storage.directory.rename(network.id, normalized)
            storage.setDirty()
            return PeripheralConfiguratorSaveResult.NAMED_NETWORK
        }
        val identities = PeripheralDeviceNames.resolveContact(level, position, face)
        if (identities.size != 1) return PeripheralConfiguratorSaveResult.INVALID_TARGET
        val target = identities.single()
        val screen =
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .surface(level, target)
        if (screen != null) {
            val connected =
                PeripheralNetworkAccess
                    .network(level, target.anchor)
                    ?.members
                    ?.map { it.identity }
                    ?.toSet()
                    ?: (PeripheralWorldDiscovery.discoverFromDevice(level, target.anchor) as? PeripheralCableTraversal.Complete)?.contacts
                    ?: return PeripheralConfiguratorSaveResult.INVALID_TARGET
            val names = PeripheralNetworkAccess.names(level, connected)
            if (connected.any { identity ->
                    ru.lazyhat.compukters.minecraft.display.DisplayNames
                        .surface(level, identity)
                        ?.id != screen.id &&
                        names.nameOf(identity) == normalized
                }
            ) {
                return PeripheralConfiguratorSaveResult.CONFLICT
            }
            PeripheralDeviceNames.setName(level, target, normalized)
            return PeripheralConfiguratorSaveResult.NAMED_DEVICE
        }
        val member = PeripheralNetworkAccess.member(level, target)
        if (member != null) {
            val network = PeripheralNetworkAccess.network(level, target.anchor) ?: return PeripheralConfiguratorSaveResult.INVALID_TARGET
            if (network.members.any {
                    (it.instance != member.instance || it.identity != member.identity) && it.name == normalized
                }
            ) {
                return PeripheralConfiguratorSaveResult.CONFLICT
            }
            PeripheralDeviceNames.setName(level, target, normalized)
            return PeripheralConfiguratorSaveResult.NAMED_DEVICE
        }
        val traversal = PeripheralWorldDiscovery.discoverFromDevice(level, target.anchor)
        val reachable =
            (traversal as? PeripheralCableTraversal.Complete)?.contacts
                ?: return PeripheralConfiguratorSaveResult.INVALID_TARGET
        val storage = PeripheralDeviceNameStorage.get(level)
        val result = storage.assignName(reachable + target, target, normalized)
        if (result == PeripheralCandidateStatus.CONFLICT) return PeripheralConfiguratorSaveResult.CONFLICT
        if (result == PeripheralCandidateStatus.INVALID) return PeripheralConfiguratorSaveResult.INVALID_NAME
        return PeripheralConfiguratorSaveResult.NAMED_DEVICE
    }

    private fun open(
        player: ServerPlayer,
        hand: InteractionHand,
        context: PeripheralConfiguratorContext,
        mode: PeripheralConfiguratorMode,
    ): Boolean {
        val sender = opener ?: return false
        val level = player.level() as? ServerLevel ?: return false
        if (!validRange(player, context)) return false
        val inspection = inspection(level, context, mode)
        sender(player, hand, inspection)
        return true
    }

    private fun inspection(
        level: ServerLevel,
        context: PeripheralConfiguratorContext,
        mode: PeripheralConfiguratorMode,
    ): PeripheralConfiguratorSnapshot {
        val target =
            if (mode == PeripheralConfiguratorMode.EDIT_DEVICE) {
                PeripheralDeviceNames.resolveContact(level, context.position, context.face).singleOrNull()
            } else {
                null
            }
        val network = PeripheralNetworkAccess.network(level, target?.anchor ?: context.position)
        if (network != null) {
            val names = PeripheralNetworkAccess.names(level, network.members.map { it.identity }.toSet())
            val counts =
                network.members
                    .distinctBy {
                        ru.lazyhat.compukters.minecraft.display.DisplayNames
                            .surface(level, it.identity)
                            ?.id ?: it.instance
                    }.mapNotNull { names.nameOf(it.identity) }
                    .groupingBy { it }
                    .eachCount()
            val targetName = target?.let(names::nameOf)
            val entries =
                network.members.map { member ->
                    val name = names.nameOf(member.identity)
                    PeripheralConfiguratorEntry(
                        name,
                        member.identity.providerId,
                        member.identity.deviceKey,
                        name != null && (counts[name] ?: 0) > 1,
                        member.instance,
                        if (mode ==
                            PeripheralConfiguratorMode.INSPECT_NETWORK
                        ) {
                            PeripheralNetworkAccess.availability(level, context.position, member)
                        } else {
                            PeripheralNetworkAvailability.AVAILABLE
                        },
                    )
                }
            return PeripheralConfiguratorSnapshot(
                context,
                mode,
                if (mode == PeripheralConfiguratorMode.INSPECT_NETWORK) network.name else targetName.orEmpty(),
                entries,
                counts,
                targetName,
                entries.size,
                false,
                false,
            )
        }
        val identities = setOfNotNull(target)
        val directory = PeripheralNetworkAccess.names(level, identities)
        val targetName = target?.let(directory::nameOf)
        return PeripheralConfiguratorSnapshot(
            context,
            mode,
            targetName.orEmpty(),
            identities.map { PeripheralConfiguratorEntry(directory.nameOf(it), it.providerId, it.deviceKey, false) },
            targetName?.let { mapOf(it to 1) }.orEmpty(),
            targetName,
            identities.size,
            false,
            false,
        )
    }

    private fun validRange(
        player: ServerPlayer,
        context: PeripheralConfiguratorContext,
    ): Boolean = player.distanceToSqr(context.position.center) <= 64.0
}
