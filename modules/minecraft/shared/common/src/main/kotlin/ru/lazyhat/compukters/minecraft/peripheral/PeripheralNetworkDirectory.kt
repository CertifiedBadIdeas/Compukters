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
import java.util.UUID

internal data class PeripheralNetworkMember(
    val instance: UUID,
    val identity: PeripheralDeviceIdentity,
    val name: String? = null,
) {
    val isComputer: Boolean get() = identity.providerId == COMPUTER_PROVIDER

    companion object {
        const val COMPUTER_PROVIDER = "compukters:computer"
    }
}

internal data class PeripheralNetwork(
    val id: UUID,
    val name: String,
    val members: List<PeripheralNetworkMember> = emptyList(),
)

/** Persistent membership, independent of reach, chunk loading and computer lifetime. */
internal class PeripheralNetworkDirectory(
    initial: List<PeripheralNetwork> = emptyList(),
) {
    private val networks = linkedMapOf<UUID, PeripheralNetwork>()
    private val membership = hashMapOf<UUID, UUID>()
    private var memberCount = 0

    init {
        require(initial.size <= MAXIMUM_NETWORKS) { "Too many peripheral networks" }
        initial.forEach { network ->
            require(network.id !in networks) { "Duplicate peripheral network" }
            require(network.name == normalizePeripheralName(network.name)) { "Invalid network name" }
            require(network.members.size <= MAXIMUM_MEMBERS) { "Too many network members" }
            require(
                network.members
                    .map { Triple(it.instance, it.identity.providerId, it.identity.deviceKey) }
                    .distinct()
                    .size == network.members.size,
            ) {
                "Duplicate network member"
            }
            network.members.forEach { member ->
                member.name?.let { require(it == normalizePeripheralName(it)) { "Invalid member name" } }
                require(member.identity.providerId.length in 1..128 && member.identity.deviceKey.length <= 128) { "Invalid member type" }
                require(member.identity.dimension.length in 1..128) { "Invalid member dimension" }
                require(membership[member.instance] == null || membership[member.instance] == network.id) {
                    "Member belongs to multiple networks"
                }
                membership[member.instance] = network.id
            }
            memberCount += network.members.size
            require(memberCount <= MAXIMUM_TOTAL_MEMBERS) { "Too many world network members" }
            networks[network.id] = network.copy(members = network.members.toList())
        }
    }

    fun snapshot(): List<PeripheralNetwork> = networks.values.toList()

    fun get(id: UUID): PeripheralNetwork? = networks[id]

    fun networkOf(instance: UUID): PeripheralNetwork? = membership[instance]?.let(networks::get)

    fun create(
        name: String,
        id: UUID = UUID.randomUUID(),
    ): PeripheralNetwork {
        require(networks.size < MAXIMUM_NETWORKS) { "Peripheral network limit reached" }
        require(id !in networks) { "Duplicate network id" }
        return PeripheralNetwork(id, normalizePeripheralName(name)).also { networks[id] = it }
    }

    fun bind(
        id: UUID,
        member: PeripheralNetworkMember,
    ) {
        val network = requireNotNull(networks[id]) { "Peripheral network does not exist" }
        require(membership[member.instance] == null || membership[member.instance] == id) { "Device belongs to another network" }
        val key = Triple(member.instance, member.identity.providerId, member.identity.deviceKey)
        val existing = network.members.indexOfFirst { Triple(it.instance, it.identity.providerId, it.identity.deviceKey) == key }
        val members = network.members.toMutableList()
        if (existing >= 0) {
            require(members[existing].identity.providerId == member.identity.providerId) { "Member type changed" }
            members[existing] = member.copy(name = members[existing].name)
        } else {
            require(members.size < MAXIMUM_MEMBERS && memberCount < MAXIMUM_TOTAL_MEMBERS) { "Peripheral member limit reached" }
            members += member
            memberCount++
        }
        networks[id] = network.copy(members = members.toList())
        membership[member.instance] = id
    }

    fun setName(
        instance: UUID,
        identity: PeripheralDeviceIdentity,
        name: String?,
    ) {
        val id = requireNotNull(membership[instance]) { "Member has no network" }
        val network = networks.getValue(id)
        val normalized = name?.let(::normalizePeripheralName)
        networks[id] =
            network.copy(
                members =
                    network.members.map {
                        if (it.instance == instance && it.identity == identity) it.copy(name = normalized) else it
                    },
            )
    }

    fun relocate(
        instance: UUID,
        dimension: String,
        position: BlockPos,
    ): Boolean {
        val network = networkOf(instance) ?: return false
        if (network.members
                .filter {
                    it.instance == instance
                }.all { it.identity.dimension == dimension && it.identity.anchor == position }
        ) {
            return false
        }
        networks[network.id] =
            network.copy(
                members =
                    network.members.map {
                        if (it.instance ==
                            instance
                        ) {
                            it.copy(identity = it.identity.copy(dimension = dimension, anchor = position.immutable()))
                        } else {
                            it
                        }
                    },
            )
        return true
    }

    fun rename(
        id: UUID,
        name: String,
    ) {
        val network = requireNotNull(networks[id]) { "Peripheral network does not exist" }
        networks[id] = network.copy(name = normalizePeripheralName(name))
    }

    fun createFor(member: PeripheralNetworkMember): PeripheralNetwork {
        require(memberCount < MAXIMUM_TOTAL_MEMBERS) { "Peripheral member limit reached" }
        require(member.instance !in membership) { "Device belongs to another network" }
        val id = UUID.randomUUID()
        val network = create("network-${id.toString().take(8)}", id)
        bind(id, member)
        return get(network.id)!!
    }

    /** Collapse physical/source screen memberships; a disagreement leaves the result unbound. */
    fun consolidate(
        sources: Set<UUID>,
        target: PeripheralNetworkMember,
    ) {
        val instances = sources + target.instance
        val inherited = instances.mapNotNull { membership[it] }.distinct().singleOrNull()
        instances.forEach(::remove)
        if (inherited != null) bind(inherited, target)
    }

    fun checkReplacementCapacity(
        instance: UUID,
        count: Int,
    ) {
        require(count >= 1)
        val network = networkOf(instance) ?: return
        val removed = network.members.count { it.instance == instance }
        check(network.members.size - removed + count <= MAXIMUM_MEMBERS && memberCount - removed + count <= MAXIMUM_TOTAL_MEMBERS) {
            "Peripheral member limit reached"
        }
    }

    fun replace(
        instance: UUID,
        members: List<PeripheralNetworkMember>,
    ) {
        require(members.distinctBy { it.instance }.size == members.size && members.all { it.instance !in membership })
        checkReplacementCapacity(instance, members.size)
        val network = networkOf(instance) ?: return
        remove(instance)
        members.forEach { bind(network.id, it) }
    }

    fun remove(instance: UUID): Boolean {
        val id = membership.remove(instance) ?: return false
        val network = networks.getValue(id)
        val members = network.members.filterNot { it.instance == instance }
        memberCount -= network.members.size - members.size
        networks[id] = network.copy(members = members)
        return true
    }

    companion object {
        const val MAXIMUM_NETWORKS = 4096
        const val MAXIMUM_MEMBERS = 1024
        const val MAXIMUM_TOTAL_MEMBERS = 65_536
    }
}

internal fun peripheralInRange(
    computerDimension: String,
    computer: BlockPos,
    device: PeripheralDeviceIdentity,
    radius: Int,
): Boolean {
    require(radius in 1..1024)
    if (computerDimension != device.dimension) return false
    val dx = computer.x.toDouble() - device.anchor.x
    val dy = computer.y.toDouble() - device.anchor.y
    val dz = computer.z.toDouble() - device.anchor.z
    return dx * dx + dy * dy + dz * dz <= radius.toDouble() * radius
}
