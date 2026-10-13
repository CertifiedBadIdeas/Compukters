/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralContract
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralIdentity
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralSession
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity

/** A registration descriptor. Each program binds its resolver to that computer's world context. */
class ComputerPeripheralContract<T : Any>(
    val id: String,
    val providerId: String,
    val deviceKey: String,
    val ownsReachability: Boolean,
    internal val resolve: (ServerLevel, BlockPos, ComputerPeripheralIdentity) -> PeripheralEndpoint<T>?,
) {
    private var lookupType: String? = null
    val guestLookupType: String? get() = lookupType

    constructor(
        id: String,
        providerId: String,
        deviceKey: String,
        ownsReachability: Boolean,
        guestLookupType: String,
        resolve: (ServerLevel, BlockPos, ComputerPeripheralIdentity) -> PeripheralEndpoint<T>?,
    ) : this(id, providerId, deviceKey, ownsReachability, resolve) {
        require(guestLookupType.length <= 256 && guestLookupType.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        lookupType = guestLookupType
    }

    /** Retain the JVM constructor used by already compiled addons. */
    constructor(
        id: String,
        providerId: String,
        deviceKey: String,
        resolve: (ServerLevel, BlockPos, ComputerPeripheralIdentity) -> PeripheralEndpoint<T>?,
    ) : this(id, providerId, deviceKey, false, resolve)
}

/** Program-scoped world adapter for base discovery and typed addon operations. */
class ComputerPeripheralRuntime internal constructor(
    private val level: ServerLevel,
    private val computer: BlockPos,
    private val facing: Direction,
    descriptors: List<ComputerPeripheralContract<*>>,
) {
    private val contracts = descriptors.associateWith { bind(it) }
    internal val session =
        PeripheralSession(
            contracts.values.toList(),
            ::discover,
            ::at,
            ::named,
            encodeLocation = { location -> location.encode(computer) },
            ownership = ComputerPeripheralOwnership.forServer(level.server),
            decodeLocation = { bytes -> PeripheralCheckpointLocation.decode(bytes, computer) },
        )

    fun <T : Any> endpoint(
        contract: ComputerPeripheralContract<T>,
        handle: Int,
    ): T {
        requireServerThread()
        @Suppress("UNCHECKED_CAST")
        val bound =
            contracts[contract] as? PeripheralContract<PeripheralCheckpointLocation, T>
                ?: throw PeripheralFailure(HostFailureKind.OTHER, "Peripheral contract is not registered")
        return session.endpoint(bound, handle)
    }

    fun at(
        contract: ComputerPeripheralContract<*>,
        side: Int,
    ): Int {
        requireRegistered(contract)
        return session.at(contract.id, side)
    }

    fun named(
        contract: ComputerPeripheralContract<*>,
        name: String,
    ): Int {
        requireRegistered(contract)
        return session.named(contract.id, name)
    }

    fun <T : Any> close(
        contract: ComputerPeripheralContract<T>,
        handle: Int,
    ) {
        requireRegistered(contract)
        @Suppress("UNCHECKED_CAST")
        session.close(contracts.getValue(contract) as PeripheralContract<PeripheralCheckpointLocation, T>, handle)
    }

    private fun requireRegistered(contract: ComputerPeripheralContract<*>) {
        requireServerThread()
        if (contract !in contracts) throw PeripheralFailure(HostFailureKind.OTHER, "Peripheral contract is not registered")
    }

    private fun <T : Any> bind(descriptor: ComputerPeripheralContract<T>): PeripheralContract<PeripheralCheckpointLocation, T> =
        PeripheralContract(descriptor.id, descriptor.providerId, descriptor.deviceKey) { identity ->
            requireServerThread()
            val anchor =
                identity.location.resolve(level.dimension().toString(), PeripheralNetworkStorage.get(level).directory)
                    ?: return@PeripheralContract null
            val location = ComputerPeripheralIdentity(identity.providerId, anchor, identity.deviceKey)
            descriptor.resolve(level, computer, location)?.let { endpoint ->
                endpoint.withValidity {
                    descriptor.ownsReachability || ComputerPeripheralLookup.isReachable(level, computer, location)
                }
            }
        }

    private fun discover(): List<PeripheralIdentity<PeripheralCheckpointLocation>> {
        requireServerThread()
        return when (val traversal = PeripheralWorldDiscovery.discover(level, computer)) {
            is PeripheralCableTraversal.LimitExceeded -> {
                throw PeripheralFailure(HostFailureKind.UNAVAILABLE, "Peripheral discovery limit exceeded")
            }

            is PeripheralCableTraversal.Complete -> {
                traversal.contacts
                    .distinct()
                    .sortedWith(
                        compareBy({ it.anchor.x }, { it.anchor.y }, { it.anchor.z }, { it.providerId }, { it.deviceKey }),
                    ).map { PeripheralIdentity(it.providerId, checkpointLocation(it.anchor), it.deviceKey) }
            }
        }
    }

    private fun at(side: Int): List<PeripheralIdentity<PeripheralCheckpointLocation>> {
        requireServerThread()
        val direction =
            when (side) {
                0 -> facing
                1 -> facing.opposite
                2 -> facing.counterClockWise
                3 -> facing.clockWise
                4 -> Direction.UP
                5 -> Direction.DOWN
                else -> throw PeripheralFailure(HostFailureKind.OTHER, "Invalid peripheral side")
            }
        val position = computer.relative(direction)
        if (!level.hasChunkAt(position)) return emptyList()
        return ComputerAddonHosts.resolvePeripheralContact(level, position, direction.opposite).map {
            PeripheralIdentity(it.providerId, checkpointLocation(it.anchor), it.deviceKey)
        }
    }

    private fun named(
        provider: String,
        name: String,
    ): PeripheralIdentity<PeripheralCheckpointLocation>? {
        requireServerThread()
        val result = ComputerPeripheralLookup.find(level, computer, provider, name)
        return when (result.status) {
            ComputerPeripheralLookupStatus.FOUND -> {
                checkNotNull(
                    result.identity,
                ).let { PeripheralIdentity(it.providerId, checkpointLocation(it.anchor), it.deviceKey) }
            }

            ComputerPeripheralLookupStatus.MISSING -> {
                null
            }

            ComputerPeripheralLookupStatus.AMBIGUOUS -> {
                throw PeripheralFailure(HostFailureKind.OTHER, "Peripheral name is ambiguous")
            }

            ComputerPeripheralLookupStatus.INVALID_NAME -> {
                throw PeripheralFailure(HostFailureKind.OTHER, "Invalid peripheral name")
            }

            ComputerPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED -> {
                throw PeripheralFailure(
                    HostFailureKind.UNAVAILABLE,
                    "Peripheral discovery limit exceeded",
                )
            }
        }
    }

    private fun checkpointLocation(anchor: BlockPos): PeripheralCheckpointLocation {
        val instance = if (level.hasChunkAt(anchor)) level.getBlockEntity(anchor)?.let { PeripheralNetworkAccess.instance(it) } else null
        val member =
            instance?.takeIf { stamp ->
                PeripheralNetworkStorage.get(level).directory.networkOf(stamp)?.members?.any {
                    it.instance == stamp && it.identity.dimension == level.dimension().toString() && it.identity.anchor == anchor
                } == true
            }
        return PeripheralCheckpointLocation(anchor.immutable(), member)
    }

    private fun requireServerThread() {
        check(level.server.isSameThread) { "peripheral runtime must be accessed on the server thread" }
    }
}
