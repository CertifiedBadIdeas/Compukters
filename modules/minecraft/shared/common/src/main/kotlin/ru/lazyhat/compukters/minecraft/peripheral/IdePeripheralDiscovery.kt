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
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import ru.lazyhat.compukters.impl.ide.target.IdePeripheralReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply

object IdePeripheralDiscovery {
    fun snapshot(
        level: ServerLevel,
        computer: BlockPos,
    ): IdeTargetReply.NamedPeripherals {
        val traversal = PeripheralWorldDiscovery.discover(level, computer)
        require(traversal is PeripheralCableTraversal.Complete) { "Peripheral discovery limit exceeded" }
        val names = PeripheralNetworkAccess.names(level, traversal.contacts)
        val named =
            traversal.contacts
                .mapNotNull { identity -> names.nameOf(identity)?.let { identity to it } }
                .distinctBy { (identity, _) -> reference(level, identity) ?: identity }
        val counts = named.groupingBy { it.second }.eachCount()
        val entries =
            named
                .sortedWith(compareBy({ it.second }, { it.first.providerId }, { it.first.deviceKey }))
                .take(1024)
                .map { (identity, name) ->
                    val item = level.getBlockState(identity.anchor).block.asItem()
                    IdeNamedPeripheral(
                        name,
                        BuiltInRegistries.ITEM.getKey(item).toString(),
                        counts.getValue(name) > 1,
                        reference(level, identity),
                        ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts.peripheralLookupTypes(
                            identity.providerId,
                            identity.deviceKey,
                        ),
                    )
                }
        return IdeTargetReply.NamedPeripherals(entries, named.size > entries.size)
    }

    private fun reference(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
    ): IdePeripheralReference? {
        val screen =
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .surface(level, identity)
        val instance = screen?.id ?: level.getBlockEntity(identity.anchor)?.let { PeripheralNetworkAccess.instance(it) } ?: return null
        return IdePeripheralReference(instance, identity.providerId, identity.deviceKey)
    }

    fun rename(
        level: ServerLevel,
        computer: BlockPos,
        device: IdePeripheralReference,
        expectedName: String,
        requestedName: String,
    ): IdeTargetReply {
        check(level.server.isSameThread)
        val normalized =
            runCatching { normalizePeripheralName(requestedName) }.getOrNull()
                ?: return failure("Use 1–32 letters, digits, underscores or hyphens, starting with a letter")
        val traversal =
            PeripheralWorldDiscovery.discover(level, computer) as? PeripheralCableTraversal.Complete
                ?: return failure("Peripheral discovery limit exceeded")
        val identity =
            traversal.contacts.firstOrNull { reference(level, it) == device }
                ?: return failure("Device is unavailable or has been replaced; refresh Devices")
        val names = PeripheralNetworkAccess.names(level, traversal.contacts)
        if (names.nameOf(identity) != expectedName) return failure("Device name changed; refresh Devices before renaming")
        val directory = PeripheralNetworkStorage.get(level).directory
        val networkIds = setOfNotNull(directory.networkOf(device.instance)?.id, PeripheralNetworkAccess.network(level, computer)?.id)
        val conflictingMember =
            networkIds.any { id ->
                directory.get(id)!!.members.any {
                    it.name == normalized &&
                        (
                            it.instance != device.instance || it.identity.providerId != device.providerId ||
                                it.identity.deviceKey != device.deviceKey
                        )
                }
            }
        if (conflictingMember || traversal.contacts.any { reference(level, it) != device && names.nameOf(it) == normalized }) {
            return failure("Another device in the network already uses this name")
        }
        PeripheralDeviceNames.setName(level, identity, normalized)
        return snapshot(level, computer)
    }

    private fun failure(message: String): IdeTargetReply =
        IdeTargetReply.Failed(IdeTargetFailure(IdeTargetFailureKind.Other, message), false)
}
