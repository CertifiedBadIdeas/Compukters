/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralOwnership

object PeripheralOwnershipLifecycle {
    fun beforeServerTick(event: ServerTickEvent.Pre) = ComputerPeripheralOwnership.expire(event.server)

    fun onServerStopped(event: ServerStoppedEvent) = ComputerPeripheralOwnership.stop(event.server)
}
