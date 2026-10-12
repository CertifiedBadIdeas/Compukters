/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.server.MinecraftServer
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralOwnership
import java.util.IdentityHashMap

/** Shared across computers and dimensions; device keys are exact logical-instance identities. */
object ComputerPeripheralOwnership {
    private val servers = IdentityHashMap<MinecraftServer, PeripheralOwnership>()

    internal fun forServer(server: MinecraftServer): PeripheralOwnership {
        check(server.isSameThread)
        return servers.getOrPut(server, ::PeripheralOwnership)
    }

    fun expire(server: MinecraftServer) {
        check(server.isSameThread)
        servers[server]?.expire()
    }

    fun stop(server: MinecraftServer) {
        check(server.isSameThread)
        servers.remove(server)?.reset()
    }
}
