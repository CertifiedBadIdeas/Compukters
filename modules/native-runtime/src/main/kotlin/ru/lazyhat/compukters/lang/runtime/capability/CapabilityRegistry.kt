/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.capability

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest

sealed interface HostResponse {
    data object UnitSuccess : HostResponse

    data class StringSuccess(
        val value: String,
    ) : HostResponse

    data class Failure(
        val kind: HostFailureKind,
        val code: Long,
    ) : HostResponse
}

fun interface HostCapability {
    suspend fun invoke(request: VmHostRequest): HostResponse
}

interface IdentifiedHostCapability : HostCapability {
    val identity: CapabilityIdentity
}

class CapabilityRegistry(
    capabilities: List<IdentifiedHostCapability>,
) {
    private val capabilities =
        capabilities.associateBy(IdentifiedHostCapability::identity).also {
            require(it.size == capabilities.size) { "duplicate host capability identity" }
        }

    suspend fun dispatch(request: VmHostRequest): HostResponse =
        capabilities[request.capability]?.invoke(request)
            ?: HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0)
}
