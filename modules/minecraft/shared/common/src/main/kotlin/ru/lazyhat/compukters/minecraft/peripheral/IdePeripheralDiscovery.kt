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
import ru.lazyhat.compukters.ide.analysis.PeripheralAvailability
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import ru.lazyhat.compukters.impl.ide.target.IdePeripheralReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts

object IdePeripheralDiscovery {
    fun snapshot(
        level: ServerLevel,
        computer: BlockPos,
        includeUnavailable: Boolean = false,
    ): IdeTargetReply.NamedPeripherals {
        val traversal = PeripheralWorldDiscovery.discover(level, computer)
        require(traversal is PeripheralCableTraversal.Complete) { "Peripheral discovery limit exceeded" }
        val names = PeripheralNetworkAccess.names(level, traversal.contacts)
        val named =
            traversal.contacts
                .mapNotNull { identity -> names.nameOf(identity)?.let { identity to it } }
                .distinctBy { (identity, _) -> reference(level, identity) ?: identity }
        if (includeUnavailable) return inspection(level, computer, named)
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
                        ComputerAddonHosts.peripheralLookupTypes(
                            identity.providerId,
                            identity.deviceKey,
                        ),
                    )
                }
        return IdeTargetReply.NamedPeripherals(entries, named.size > entries.size)
    }

    private fun inspection(
        level: ServerLevel,
        computer: BlockPos,
        reachable: List<Pair<PeripheralDeviceIdentity, String>>,
    ): IdeTargetReply.NamedPeripherals {
        check(level.server.isSameThread)
        val observed = linkedMapOf<Any, IdeNamedPeripheral>()
        reachable.forEach { (identity, name) ->
            val ref = reference(level, identity)
            observed[ref ?: identity] =
                IdeNamedPeripheral(
                    name,
                    "minecraft:air",
                    reference = ref,
                    lookupTypes =
                        ComputerAddonHosts.peripheralLookupTypes(
                            identity.providerId,
                            identity.deviceKey,
                        ),
                )
        }
        val network = PeripheralNetworkAccess.network(level, computer)
        if (network != null) {
            val names = PeripheralNetworkAccess.names(level, network.members.map { it.identity }.toSet())
            network.members.filterNot { it.isComputer }.forEach { member ->
                val name = names.nameOf(member.identity) ?: return@forEach
                val ref = IdePeripheralReference(member.instance, member.identity.providerId, member.identity.deviceKey)
                if (ref in observed) return@forEach
                val availability =
                    when (PeripheralNetworkAccess.availability(level, computer, member)) {
                        PeripheralNetworkAvailability.AVAILABLE -> PeripheralAvailability.Available
                        PeripheralNetworkAvailability.OUT_OF_RANGE -> PeripheralAvailability.OutOfRange
                        PeripheralNetworkAvailability.OTHER_DIMENSION -> PeripheralAvailability.OtherDimension
                        PeripheralNetworkAvailability.UNLOADED -> PeripheralAvailability.Unloaded
                        PeripheralNetworkAvailability.REPLACED -> PeripheralAvailability.Replaced
                        else -> PeripheralAvailability.Unavailable
                    }
                observed[ref] =
                    IdeNamedPeripheral(
                        name,
                        "minecraft:air",
                        reference = ref,
                        lookupTypes =
                            ComputerAddonHosts.peripheralLookupTypes(
                                member.identity.providerId,
                                member.identity.deviceKey,
                            ),
                        availability = availability,
                    )
            }
        }
        val counts = observed.values.groupingBy { it.name }.eachCount()
        val entries =
            observed.values
                .sortedBy { it.name }
                .take(1024)
                .map { it.copy(duplicate = counts.getValue(it.name) > 1) }
        return IdeTargetReply.NamedPeripherals(entries, observed.size > entries.size)
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
