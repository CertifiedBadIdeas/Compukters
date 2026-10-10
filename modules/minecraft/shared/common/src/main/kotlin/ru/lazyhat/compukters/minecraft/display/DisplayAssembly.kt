/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** The item adapter persists only bounded selection tokens; the world remains authoritative. */
object DisplayAssembly {
    fun click(
        player: ServerPlayer,
        stack: ItemStack,
        position: BlockPos,
        read: (String) -> String,
        write: (String, String?) -> Unit,
    ) {
        val level = player.level() as? ServerLevel ?: return
        if (!player.mayBuild() || !level.mayInteract(player, position) || player.distanceToSqr(position.center) > 64.0) return
        val entity =
            level.getBlockEntity(position) as? DisplayBlockEntity ?: run {
                player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.display.missing"))
                return
            }
        runCatching {
            val selected = read(SCREEN_KEY).takeIf(String::isNotEmpty)?.let(UUID::fromString)
            val first = read(CORNER_KEY).takeIf(String::isNotEmpty)?.toLong()?.let(BlockPos::of)
            if (player.isShiftKeyDown) {
                val surface = requireNotNull(DisplayWorldAccess.surface(level, entity))
                if (selected == surface.id && first == null && read(DIMENSION_KEY) == level.dimension().toString() &&
                    surface.canvas.columns * surface.canvas.rows > 1
                ) {
                    DisplayWorldAccess.split(level, surface.id)
                    SELECTION_KEYS.forEach { write(it, null) }
                    "split"
                } else {
                    write(SCREEN_KEY, surface.id.toString())
                    write(CORNER_KEY, null)
                    write(DIMENSION_KEY, level.dimension().toString())
                    "selected"
                }
            } else if (selected != null && first == null) {
                require(read(DIMENSION_KEY) == level.dimension().toString())
                DisplayWorldAccess.join(level, selected, entity)
                "joined"
            } else if (first == null) {
                write(CORNER_KEY, position.asLong().toString())
                write(INSTANCE_KEY, entity.checkpointIdentity)
                write(DIMENSION_KEY, level.dimension().toString())
                "corner"
            } else {
                require(read(DIMENSION_KEY) == level.dimension().toString())
                require(level.hasChunkAt(first))
                val previous = level.getBlockEntity(first) as? DisplayBlockEntity
                require(previous?.checkpointIdentity == read(INSTANCE_KEY)) { "Selected corner was replaced" }
                val surface = DisplayWorldAccess.assemble(level, first, position)
                write(CORNER_KEY, null)
                write(SCREEN_KEY, surface.id.toString())
                "created"
            }
        }.fold({ result ->
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.display.$result"))
        }, { error ->
            player.sendSystemMessage(
                Component.translatable("item.compukters.peripheral_configurator.display.failed", error.message.orEmpty()),
            )
        })
    }

    val SELECTION_KEYS: List<String> = listOf(SCREEN_KEY, CORNER_KEY, DIMENSION_KEY, INSTANCE_KEY)
    private const val SCREEN_KEY = "compukters_selected_screen"
    private const val CORNER_KEY = "compukters_screen_corner"
    private const val DIMENSION_KEY = "compukters_screen_dimension"
    private const val INSTANCE_KEY = "compukters_screen_corner_instance"
}
