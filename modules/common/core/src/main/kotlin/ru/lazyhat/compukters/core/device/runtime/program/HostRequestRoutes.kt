/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest

/** Routes native request batches without executing any world action. */
internal object HostRequestRoutes {
    private val redstone = CapabilityIdentity("compukter", "redstone", 1, 0)
    private val sound = CapabilityIdentity("compukter", "sound", 1, 0)
    val timer = CapabilityIdentity("compukter", "timer", 1, 0)

    fun isSound(request: VmHostRequest): Boolean = request.capability == sound && request.operation == 0

    fun isAddon(
        request: VmHostRequest,
        schemas: List<HostCapabilitySchema>,
    ): Boolean =
        schemas.any { schema ->
            schema.identity.namespace == request.capability.namespace &&
                schema.identity.name == request.capability.name &&
                schema.identity.abiMajor == request.capability.abiMajor &&
                schema.identity.abiMinor >= request.capability.abiMinor
        }

    fun partition(
        requests: List<VmHostRequest>,
        addonSchemas: List<HostCapabilitySchema>,
    ): HostRequestBatch {
        val redstoneRequests = ArrayList<VmHostRequest>()
        val soundRequests = ArrayList<VmHostRequest>()
        val timerRequests = ArrayList<VmHostRequest>()
        val addonRequests = ArrayList<VmHostRequest>()
        val unavailable = ArrayList<VmHostRequest>()
        for (request in requests) {
            when {
                request.capability == redstone && request.operation in 6..7 -> redstoneRequests.add(request)
                isSound(request) -> soundRequests.add(request)
                request.capability == timer && request.operation == 0 -> timerRequests.add(request)
                isAddon(request, addonSchemas) -> addonRequests.add(request)
                else -> unavailable.add(request)
            }
        }
        return HostRequestBatch(redstoneRequests, soundRequests, timerRequests, addonRequests, unavailable)
    }
}

internal data class HostRequestBatch(
    val redstone: List<VmHostRequest>,
    val sound: List<VmHostRequest>,
    val timers: List<VmHostRequest>,
    val addons: List<VmHostRequest>,
    val unavailable: List<VmHostRequest>,
)
