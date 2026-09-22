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
)

data class PeripheralConfiguratorEntry(
    val name: String?,
    val providerId: String,
    val deviceKey: String,
    val duplicate: Boolean,
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
    INVALID_NAME,
    CONFLICT,
    INVALID_TARGET,
    OUT_OF_RANGE,
}

object PeripheralConfiguratorServer {
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
        return open(player, hand, PeripheralConfiguratorContext(position.immutable(), face), PeripheralConfiguratorMode.EDIT_DEVICE)
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
        val identities = PeripheralDeviceNames.resolveContact(level, position, face)
        if (identities.size != 1) return PeripheralConfiguratorSaveResult.INVALID_TARGET
        val target = identities.single()
        val traversal = PeripheralWorldDiscovery.discoverFromDevice(level, position)
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
        val position = context.position
        val target =
            if (mode == PeripheralConfiguratorMode.EDIT_DEVICE) {
                PeripheralDeviceNames.resolveContact(level, position, context.face).singleOrNull()
            } else {
                null
            }
        val traversal =
            when (mode) {
                PeripheralConfiguratorMode.EDIT_DEVICE -> PeripheralWorldDiscovery.discoverFromDevice(level, position)
                PeripheralConfiguratorMode.INSPECT_NETWORK -> PeripheralWorldDiscovery.discoverFromCable(level, position)
            }
        val directory = PeripheralDeviceNameStorage.get(level).directory
        val targetName = target?.let(directory::nameOf)
        return when (val value = inspectPeripheralComponent(traversal, directory, target, targetName.orEmpty())) {
            is PeripheralInspection.LimitExceeded -> {
                PeripheralConfiguratorSnapshot(context, mode, targetName.orEmpty(), emptyList(), emptyMap(), targetName, 0, false, true)
            }

            is PeripheralInspection.Complete -> {
                PeripheralConfiguratorSnapshot(
                    context,
                    mode,
                    if (mode == PeripheralConfiguratorMode.EDIT_DEVICE) value.targetName.orEmpty() else "",
                    value.entries.map { PeripheralConfiguratorEntry(it.name, it.providerId, it.deviceKey, it.duplicate) },
                    value.nameCounts,
                    value.targetName,
                    value.totalDevices,
                    value.truncated,
                    false,
                )
            }
        }
    }

    private fun validRange(
        player: ServerPlayer,
        context: PeripheralConfiguratorContext,
    ): Boolean = player.distanceToSqr(context.position.center) <= 64.0
}
