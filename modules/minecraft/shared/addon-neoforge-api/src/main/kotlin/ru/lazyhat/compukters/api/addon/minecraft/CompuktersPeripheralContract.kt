/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.addon.minecraft

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralContract
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup

/** Addons pin the exact physical instance here; the base also verifies reachability before every typed access. */
interface CompuktersPeripheralEndpoint {
    val identity: Any

    fun valid(): Boolean
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
    internal fun bind(providerId: String): ComputerPeripheralContract<T> =
        ComputerPeripheralContract(id, providerId, deviceKey) { level, computer, identity ->
            resolve(CompuktersPeripheralLocation(level, identity.anchor, identity.deviceKey, computer, providerId))?.let { endpoint ->
                PeripheralEndpoint(
                    endpoint,
                    endpoint.identity,
                    (endpoint as? CompuktersPersistentPeripheralEndpoint)?.persistentIdentity,
                ) { endpoint.valid() }
            }
        }
}

/** Preserve host failure categories when forwarding typed peripheral operations to an addon capability. */
class CompuktersPeripheralAccessException internal constructor(
    val kind: HostFailureKind,
    override val message: String,
) : RuntimeException(message)
