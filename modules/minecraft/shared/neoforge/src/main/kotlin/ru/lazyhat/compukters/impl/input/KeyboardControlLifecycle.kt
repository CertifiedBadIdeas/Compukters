/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.input

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.minecraft.input.ComputerKeyboard

object KeyboardControlLifecycle {
    fun tick(event: ServerTickEvent.Post) = ComputerKeyboard.tick(event.server)

    fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let(ComputerKeyboard::disconnect)
    }

    fun stop(event: ServerStoppingEvent) = ComputerKeyboard.shutdown(event.server)
}
