/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import ru.lazyhat.compukters.minecraft.display.attachedDisplayPointerTarget
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions

/** Empty-hand panel interaction consumes use before the backing block can open its own menu. */
object AttachedDisplayInteraction {
    fun click(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity
        if (event.hand != InteractionHand.MAIN_HAND || !player.mainHandItem.isEmpty || player.isShiftKeyDown || player.isSpectator ||
            !player.isAlive
        ) {
            return
        }
        val level = event.level
        if (level is ServerLevel) {
            if (!level.hasChunkAt(event.pos) || !level.mayInteract(player, event.pos) ||
                !PeripheralWorldPositions.within(level, player.position(), event.pos.center, 8.0)
            ) {
                return
            }
            val target = attachedDisplayPointerTarget(level, event.hitVec) ?: return
            target.canvas.offerClick(target.x, target.y)
        } else {
            if (!AttachedDisplayClient.contains(event.hitVec)) return
        }
        event.cancellationResult = InteractionResult.CONSUME
        event.isCanceled = true
    }
}
