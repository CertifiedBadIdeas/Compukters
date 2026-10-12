/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import java.util.IdentityHashMap

/** Server-confined, shared device ownership. The owner is one program's peripheral session. */
class PeripheralOwnership {
    private class Lease(
        val owner: Any,
        val valid: () -> Boolean,
        val release: () -> Unit,
    )

    private val devices = IdentityHashMap<Any, IdentityHashMap<Any, Lease>>()

    fun acquire(
        device: Any,
        owner: Any,
        token: Any,
        valid: () -> Boolean,
        release: () -> Unit,
    ) {
        devices[device]
            ?.values
            ?.filter { !it.valid() }
            ?.toList()
            ?.forEach { it.release() }
        val leases = devices[device]
        if (leases != null && leases.values.any { it.owner !== owner }) {
            throw PeripheralFailure(HostFailureKind.UNAVAILABLE, "Peripheral is owned by another program")
        }
        val target = leases ?: IdentityHashMap<Any, Lease>().also { devices[device] = it }
        check(token !in target) { "Peripheral ownership token is already retained" }
        target[token] = Lease(owner, valid, release)
    }

    fun release(
        device: Any,
        token: Any,
    ) {
        val leases = devices[device] ?: return
        leases.remove(token)
        if (leases.isEmpty()) devices.remove(device)
    }

    /** Caller drives this from the server tick, independent of Guest device operations. */
    fun expire() {
        devices.values
            .flatMap { it.values }
            .filter { !it.valid() }
            .forEach { it.release() }
    }

    fun reset() {
        val releases = devices.values.flatMap { it.values }.map { it.release }
        devices.clear()
        releases.forEach { it() }
    }
}
