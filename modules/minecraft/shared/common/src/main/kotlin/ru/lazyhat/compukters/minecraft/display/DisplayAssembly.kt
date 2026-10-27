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
        face: net.minecraft.core.Direction = net.minecraft.core.Direction.NORTH,
    ) {
        val level = player.level() as? ServerLevel ?: return
        if (!player.mayBuild() || !level.mayInteract(player, position) ||
            !ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
                .within(level, player.position(), position.center, 8.0)
        ) {
            return
        }
        val entity =
            level.getBlockEntity(position) as? DisplayBlockEntity ?: run {
                attachedClick(player, position, face, read, write)
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

    private fun attachedClick(
        player: ServerPlayer,
        position: BlockPos,
        face: net.minecraft.core.Direction,
        read: (String) -> String,
        write: (String, String?) -> Unit,
    ) {
        val level = player.level() as? ServerLevel ?: return
        runCatching {
            require(level.hasChunkAt(position) && !level.getBlockState(position).isAir)
            val surface = AttachedDisplaySurfaces.at(level, position, face)
            if (player.isShiftKeyDown) {
                requireNotNull(surface) { "No attached display on this face" }
                AttachedDisplaySurfaces.remove(level, surface.id)
                SELECTION_KEYS.forEach { write(it, null) }
                "removed"
            } else {
                val first = read(CORNER_KEY).takeIf(String::isNotEmpty)?.toLong()?.let(BlockPos::of)
                if (first == null || read(FACE_KEY).isEmpty()) {
                    SELECTION_KEYS.forEach { write(it, null) }
                    write(CORNER_KEY, position.asLong().toString())
                    write(DIMENSION_KEY, level.dimension().toString())
                    write(FACE_KEY, face.name)
                    "corner"
                } else {
                    require(
                        read(DIMENSION_KEY) == level.dimension().toString() && read(FACE_KEY) == face.name,
                    ) { "Select the same face in the same world" }
                    val id =
                        AttachedDisplaySurfaces.create(level, first, position, face) {
                            player.mayBuild() &&
                                level.mayInteract(player, it)
                        }
                    SELECTION_KEYS.forEach { write(it, null) }
                    write(SCREEN_KEY, id.toString())
                    write(DIMENSION_KEY, level.dimension().toString())
                    "created"
                }
            }
        }.fold({ player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.display.$it")) }, { error ->
            player.sendSystemMessage(
                Component.translatable("item.compukters.peripheral_configurator.display.failed", error.message.orEmpty()),
            )
        })
    }

    const val FACE_KEY = "compukters_screen_corner_face"

    val SELECTION_KEYS: List<String> = listOf(SCREEN_KEY, CORNER_KEY, DIMENSION_KEY, INSTANCE_KEY, FACE_KEY)
    private const val SCREEN_KEY = "compukters_selected_screen"
    private const val CORNER_KEY = "compukters_screen_corner"
    private const val DIMENSION_KEY = "compukters_screen_dimension"
    private const val INSTANCE_KEY = "compukters_screen_corner_instance"
}
