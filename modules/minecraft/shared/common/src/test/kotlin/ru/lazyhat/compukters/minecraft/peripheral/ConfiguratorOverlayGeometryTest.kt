/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguratorOverlayGeometryTest {
    private val id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @Test
    fun `replacement inside a hole stays distinguishable until it joins`() {
        for (facing in listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            val origin = BlockPos(197, -58, 176)
            val member = origin.relative(facing.counterClockWise)
            val screen = ConfiguratorOverlayScreen(id, "wall", origin, facing, 4, 3, listOf(member))
            val replacement = ConfiguratorOverlayDevice(origin, "display", screen = UUID.randomUUID())
            val snapshot =
                ConfiguratorOverlaySnapshot(listOf(replacement, ConfiguratorOverlayDevice(member, "display", screen = id)), listOf(screen))
            assertEquals(screen, ConfiguratorOverlayGeometry.unjoined(snapshot, replacement))
            val outlines = ConfiguratorOverlayGeometry.outlines(snapshot, 2, null, null, id)
            assertEquals(ConfiguratorOverlayGeometry.UNJOINED_COLOR, outlines.first().color)
            assertEquals(11, outlines.count { it.dashed })
            assertTrue(outlines.filter { it.dashed }.all { it.strong })
            assertNull(ConfiguratorOverlayGeometry.unjoined(snapshot, replacement.copy(screen = id)))
            assertNull(ConfiguratorOverlayGeometry.unjoined(snapshot, replacement.copy(position = origin.above())))
        }
    }

    @Test
    fun `hover highlights the whole network and group colors stay stable`() {
        val first = ConfiguratorOverlayDevice(BlockPos.ZERO, "a", id)
        val second = first.copy(position = BlockPos(1, 0, 0), title = "b")
        val unrelated = ConfiguratorOverlayDevice(BlockPos(2, 0, 0), "c", UUID.randomUUID())
        val snapshot = ConfiguratorOverlaySnapshot(listOf(first, second, unrelated), emptyList())
        val outlines = ConfiguratorOverlayGeometry.outlines(snapshot, 1, first.position, null, null)
        assertTrue(outlines[0].strong && outlines[1].strong && !outlines[2].strong)
        assertEquals(outlines[0].color, outlines[1].color)
        assertEquals(ConfiguratorOverlayGeometry.color(id), outlines[0].color)
    }

    @Test
    fun `preview accepts reversed planar corners and rejects different planes facing and oversize`() {
        val first = BlockPos(10, 5, 10)
        assertNotNull(ConfiguratorOverlayGeometry.preview(first, Direction.NORTH, BlockPos(3, -2, 10), Direction.NORTH))
        assertNull(ConfiguratorOverlayGeometry.preview(first, Direction.NORTH, BlockPos(2, 5, 10), Direction.NORTH))
        assertNull(ConfiguratorOverlayGeometry.preview(first, Direction.NORTH, first.below(8), Direction.NORTH))
        assertNull(ConfiguratorOverlayGeometry.preview(first, Direction.NORTH, first.north(), Direction.NORTH))
        assertNull(ConfiguratorOverlayGeometry.preview(first, Direction.NORTH, first, Direction.SOUTH))
    }

    @Test
    fun `screen metadata rejects duplicates and panels outside its rectangle`() {
        assertFailsWith<IllegalArgumentException> {
            ConfiguratorOverlayScreen(id, "", BlockPos.ZERO, Direction.NORTH, 2, 2, listOf(BlockPos.ZERO, BlockPos.ZERO))
        }
        assertFailsWith<IllegalArgumentException> {
            ConfiguratorOverlayScreen(id, "", BlockPos.ZERO, Direction.NORTH, 2, 2, listOf(BlockPos.ZERO.above()))
        }
    }
}
