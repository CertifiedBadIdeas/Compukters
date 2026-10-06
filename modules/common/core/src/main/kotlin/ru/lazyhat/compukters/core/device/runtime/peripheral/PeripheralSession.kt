/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

/** A logical device location. It is not a live handle and never identifies a replacement instance. */
data class PeripheralIdentity<I : Any>(
    val providerId: String,
    val location: I,
    val deviceKey: String,
)

class PeripheralEndpoint<T : Any>(
    val value: T,
    val identity: Any,
    private val checkValid: () -> Boolean,
) {
    private var available = true

    fun valid(): Boolean {
        if (available) available = checkValid()
        return available
    }
}

/** The contract determines the endpoint type; the session checks it before exposing any retained endpoint. */
class PeripheralContract<I : Any, T : Any>(
    val id: String,
    val providerId: String,
    val deviceKey: String,
    val resolve: (PeripheralIdentity<I>) -> PeripheralEndpoint<T>?,
) {
    init {
        require(id.length <= 128 && Regex("[a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*").matches(id)) { "invalid peripheral contract id" }
    }

    internal fun accepts(identity: PeripheralIdentity<I>): Boolean = identity.providerId == providerId && identity.deviceKey == deviceKey
}

class PeripheralFailure(
    val kind: HostFailureKind,
    override val message: String,
) : RuntimeException(message)

/**
 * One program's bounded discovery snapshots and exact-instance handles. World adapters provide deterministic
 * discovery order and reachability checks. No endpoint can become valid again after a failed check.
 */
class PeripheralSession<I : Any>(
    contracts: List<PeripheralContract<I, *>>,
    private val discover: () -> List<PeripheralIdentity<I>>,
    private val side: (Int) -> List<PeripheralIdentity<I>>,
    private val lookupName: (String, String) -> PeripheralIdentity<I>?,
    private val maximumHandles: Int = 1_024,
    private val maximumSnapshots: Int = 4,
    private val maximumSnapshotEntries: Int = 1_024,
) {
    private val contracts = contracts.associateBy { it.id }
    private val handles = linkedMapOf<Int, Retained<I>>()
    private val snapshots = linkedMapOf<Int, List<Retained<I>>>()
    private var nextHandle = 1
    private var nextSnapshot = 1

    init {
        require(this.contracts.size == contracts.size) { "duplicate peripheral contract id" }
        require(maximumHandles > 0 && maximumSnapshots > 0 && maximumSnapshotEntries > 0)
    }

    fun openSnapshot(contractId: String): Int {
        val contract = contract(contractId)
        if (snapshots.size >= maximumSnapshots) unavailable("Peripheral snapshot limit reached")
        val identities = discover().distinct()
        if (identities.size > maximumSnapshotEntries) unavailable("Peripheral discovery limit exceeded")
        // Resolve exact instances now, without allocating device handles. Later reads cannot bind replacements.
        val entries = identities.filter(contract::accepts).mapNotNull { resolve(contract, it) }
        val id = nextId(nextSnapshot, "snapshot")
        nextSnapshot += 1
        snapshots[id] = entries
        return id
    }

    fun snapshotSize(snapshot: Int): Int = snapshot(snapshot).size

    fun snapshotGet(
        snapshot: Int,
        index: Int,
    ): Int {
        val entries = snapshot(snapshot)
        if (index !in entries.indices) invalid("Peripheral snapshot index is out of range")
        return retain(entries[index])
    }

    fun closeSnapshot(snapshot: Int) {
        if (snapshots.remove(snapshot) == null) invalid("Unknown peripheral snapshot")
    }

    /** Zero means absence; invalid inputs, wrong types and capacity failures remain errors. */
    fun at(
        contractId: String,
        sideIndex: Int,
    ): Int {
        val contract = contract(contractId)
        if (sideIndex !in 0..5) invalid("Invalid peripheral side")
        val matches = side(sideIndex).distinct().filter(contract::accepts)
        if (matches.size > 1) invalid("More than one peripheral satisfies the contract at that side")
        return matches.singleOrNull()?.let { resolve(contract, it) }?.let(::retain) ?: 0
    }

    fun named(
        contractId: String,
        name: String,
    ): Int {
        val contract = contract(contractId)
        val identity = lookupName(contract.providerId, name) ?: return 0
        if (!contract.accepts(identity)) invalid("Named peripheral does not satisfy the requested contract")
        return resolve(contract, identity)?.let(::retain) ?: 0
    }

    fun <T : Any> endpoint(
        contract: PeripheralContract<I, T>,
        handle: Int,
    ): T {
        if (contracts[contract.id] !== contract) invalid("Peripheral contract is not registered in this program")
        val retained = handles[handle] ?: stale("Unknown or expired peripheral handle")
        if (retained.contract !== contract) invalid("Peripheral handle belongs to a different contract")
        if (!retained.endpoint.valid()) {
            handles.remove(handle)
            stale("Peripheral was removed, replaced, disconnected, or unloaded")
        }
        // Contract identity was checked above; only its typed resolver can create the endpoint.
        @Suppress("UNCHECKED_CAST")
        return retained.endpoint.value as T
    }

    fun reset() {
        snapshots.clear()
        handles.clear()
        // Do not reuse issued tokens even when a host resets within the same program.
    }

    private fun contract(id: String): PeripheralContract<I, *> = contracts[id] ?: unavailable("Peripheral contract is not installed")

    private fun resolve(
        contract: PeripheralContract<I, *>,
        identity: PeripheralIdentity<I>,
    ): Retained<I>? = contract.resolve(identity)?.takeIf { it.valid() }?.let { Retained(contract, it) }

    private fun retain(retained: Retained<I>): Int {
        if (!retained.endpoint.valid()) stale("Peripheral snapshot device is no longer available")
        handles.entries.removeIf { !it.value.endpoint.valid() }
        handles.entries
            .firstOrNull { (_, existing) ->
                existing.contract === retained.contract && existing.endpoint.identity === retained.endpoint.identity
            }?.let { return it.key }
        if (handles.size >= maximumHandles) unavailable("Peripheral handle limit reached")
        val id = nextId(nextHandle, "handle")
        nextHandle += 1
        handles[id] = retained
        return id
    }

    private fun snapshot(id: Int): List<Retained<I>> = snapshots[id] ?: invalid("Unknown peripheral snapshot")

    private fun nextId(
        value: Int,
        kind: String,
    ): Int {
        if (value <= 0 || value == Int.MAX_VALUE) unavailable("Peripheral $kind identifiers exhausted")
        return value
    }

    private class Retained<I : Any>(
        val contract: PeripheralContract<I, *>,
        val endpoint: PeripheralEndpoint<*>,
    )

    private fun invalid(detail: String): Nothing = throw PeripheralFailure(HostFailureKind.OTHER, detail)

    private fun unavailable(detail: String): Nothing = throw PeripheralFailure(HostFailureKind.UNAVAILABLE, detail)

    private fun stale(detail: String): Nothing = throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, detail)
}
