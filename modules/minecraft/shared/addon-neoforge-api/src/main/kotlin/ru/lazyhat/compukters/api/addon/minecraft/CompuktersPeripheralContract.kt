/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.addon.minecraft

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralHandleLease
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralContract
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup

/** Addons pin the exact physical instance here; the base also verifies reachability before every typed access. */
interface CompuktersPeripheralEndpoint {
    /**
     * Return the same identity object for every resolution/contract of one logical device.
     * Typed acquisition uses referential identity for exclusive program ownership, including reads.
     */
    val identity: Any

    fun valid(): Boolean
}

/** Optional server-thread callbacks for transient actuator ownership; discovery does not acquire a lease. */
interface CompuktersLeasedPeripheralEndpoint : CompuktersPeripheralEndpoint {
    fun acquire()

    /** Called once on close, program stop or disconnection. Must tolerate an already removed device. */
    fun release()
}

private class PeripheralLeaseAdapter(
    private val endpoint: CompuktersLeasedPeripheralEndpoint,
) : PeripheralHandleLease {
    override fun acquire() = endpoint.acquire()

    override fun release() = endpoint.release()
}

/** Optional hibernation contract; existing endpoint implementations remain binary compatible. */
interface CompuktersPersistentPeripheralEndpoint : CompuktersPeripheralEndpoint {
    /** Stable, persisted exact-device stamp, bounded to 128 UTF-8 bytes. */
    val persistentIdentity: String
}

/** The logical device anchor selected by discovery, with a validity check that latches disconnection. */
class CompuktersPeripheralLocation internal constructor(
    val level: ServerLevel,
    val position: BlockPos,
    val deviceKey: String,
    private val computerPosition: BlockPos,
    private val providerId: String,
) {
    private var reachable = true

    fun isReachable(): Boolean {
        if (reachable) {
            reachable =
                ComputerPeripheralLookup.isReachable(
                    level,
                    computerPosition,
                    ComputerPeripheralIdentity(providerId, position, deviceKey),
                )
        }
        return reachable
    }
}

/** Register this descriptor once and use the same object to resolve handles in the addon host. */
class CompuktersPeripheralContract<T : CompuktersPeripheralEndpoint>(
    val id: String,
    val deviceKey: String,
    private val resolve: (CompuktersPeripheralLocation) -> T?,
) {
    private var lookupType: String? = null
    val guestLookupType: String? get() = lookupType

    /** Optional fully qualified Guest provider type used by the IDE to copy named lookups. */
    constructor(
        id: String,
        deviceKey: String,
        guestLookupType: String,
        resolve: (CompuktersPeripheralLocation) -> T?,
    ) : this(id, deviceKey, resolve) {
        require(guestLookupType.length <= 256 && guestLookupType.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        lookupType = guestLookupType
    }

    internal fun bind(providerId: String): ComputerPeripheralContract<T> {
        val resolver: (ServerLevel, BlockPos, ComputerPeripheralIdentity) -> PeripheralEndpoint<T>? = { level, computer, identity ->
            resolve(CompuktersPeripheralLocation(level, identity.anchor, identity.deviceKey, computer, providerId))?.let { endpoint ->
                val persistentIdentity = (endpoint as? CompuktersPersistentPeripheralEndpoint)?.persistentIdentity
                val leased = endpoint as? CompuktersLeasedPeripheralEndpoint
                if (leased == null) {
                    PeripheralEndpoint(endpoint, endpoint.identity, persistentIdentity) { endpoint.valid() }
                } else {
                    PeripheralEndpoint(endpoint, endpoint.identity, persistentIdentity, PeripheralLeaseAdapter(leased)) { endpoint.valid() }
                }
            }
        }
        return lookupType?.let { ComputerPeripheralContract(id, providerId, deviceKey, false, it, resolver) }
            ?: ComputerPeripheralContract(id, providerId, deviceKey, resolver)
    }
}

/** Preserve host failure categories when forwarding typed peripheral operations to an addon capability. */
class CompuktersPeripheralAccessException internal constructor(
    val kind: HostFailureKind,
    override val message: String,
) : RuntimeException(message)
