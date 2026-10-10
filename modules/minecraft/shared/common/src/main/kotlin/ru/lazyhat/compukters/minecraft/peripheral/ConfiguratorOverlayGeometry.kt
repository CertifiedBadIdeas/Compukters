/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.UUID

/** Immutable world geometry shared by both client renderers. */
data class ConfiguratorOutline(
    val bounds: AABB,
    val color: Int,
    val strong: Boolean,
    val dashed: Boolean = false,
)

object ConfiguratorOverlayGeometry {
    const val UNJOINED_COLOR = 0xFFBA59
    private val colors = intArrayOf(0x6CDBFF, 0xA8E878, 0xCC9BFF, 0xFF91B5, 0xFFD879, 0x76E5CF)

    fun color(group: UUID?): Int = if (group == null) 0x9AAAB8 else colors[Math.floorMod(group.hashCode(), colors.size)]

    fun unjoined(
        snapshot: ConfiguratorOverlaySnapshot,
        device: ConfiguratorOverlayDevice,
    ): ConfiguratorOverlayScreen? =
        snapshot.screens.firstOrNull { screen ->
            screen.columns * screen.rows > 1 && device.screen != null && screen.id != device.screen && device.position in screen.cells()
        }

    fun outlines(
        snapshot: ConfiguratorOverlaySnapshot,
        mode: Int,
        hover: BlockPos?,
        selectedNetwork: UUID?,
        selectedScreen: UUID?,
    ): List<ConfiguratorOutline> {
        val hovered = snapshot.devices.firstOrNull { it.position == hover }
        val hoverGroup = if (mode == 2) hovered?.screen ?: hovered?.network else hovered?.network ?: hovered?.screen
        val result =
            snapshot.devices
                .map { device ->
                    val group = if (mode == 2) device.screen ?: device.network else device.network ?: device.screen
                    val strong =
                        device.position == hover ||
                            (group != null && (group == hoverGroup || group == selectedNetwork || group == selectedScreen))
                    ConfiguratorOutline(
                        AABB(device.position).inflate(0.008),
                        if (unjoined(snapshot, device) !=
                            null
                        ) {
                            UNJOINED_COLOR
                        } else {
                            color(group)
                        },
                        strong,
                    )
                }.toMutableList()
        snapshot.screens.filter { it.columns * it.rows > 1 }.forEach { screen ->
            val strong = screen.id == selectedScreen || screen.id == hovered?.screen
            val bounds = front(AABB(screen.origin).minmax(AABB(screen.position(screen.columns - 1, screen.rows - 1))), screen.facing)
            result += ConfiguratorOutline(bounds.inflate(0.008), color(screen.id), strong)
            (screen.cells() - screen.panels.toSet()).forEach { cell ->
                result += ConfiguratorOutline(front(AABB(cell).deflate(0.04), screen.facing), UNJOINED_COLOR, strong, true)
            }
        }
        return result
    }

    fun preview(
        first: BlockPos,
        firstFacing: Direction,
        second: BlockPos,
        secondFacing: Direction,
    ): ConfiguratorOutline? {
        val delta = second.subtract(first)
        if (firstFacing != secondFacing || !firstFacing.axis.isHorizontal ||
            delta.x * firstFacing.stepX + delta.z * firstFacing.stepZ != 0
        ) {
            return null
        }
        val right = firstFacing.counterClockWise
        val columns = kotlin.math.abs(delta.x * right.stepX + delta.z * right.stepZ) + 1
        val rows = kotlin.math.abs(delta.y) + 1
        if (columns > 8 || rows > 8) return null
        return ConfiguratorOutline(front(AABB(first).minmax(AABB(second)), firstFacing).inflate(0.015), 0xA8E878, true)
    }

    private fun front(
        box: AABB,
        facing: Direction,
    ): AABB =
        when (facing) {
            Direction.NORTH -> AABB(box.minX, box.minY, box.minZ - 0.012, box.maxX, box.maxY, box.minZ - 0.011)
            Direction.SOUTH -> AABB(box.minX, box.minY, box.maxZ + 0.011, box.maxX, box.maxY, box.maxZ + 0.012)
            Direction.WEST -> AABB(box.minX - 0.012, box.minY, box.minZ, box.minX - 0.011, box.maxY, box.maxZ)
            Direction.EAST -> AABB(box.maxX + 0.011, box.minY, box.minZ, box.maxX + 0.012, box.maxY, box.maxZ)
            else -> error("Display must face horizontally")
        }
}
