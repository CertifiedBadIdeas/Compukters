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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguratorOverlayGeometryTest {
    private val id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @Test
    fun `multiblock hover and outline use whole geometry from every tank segment`() {
        val origin = BlockPos(8, 10, -5)
        val boiler = ConfiguratorOverlayDevice(origin, "boiler", id, bounds = ConfiguratorDeviceBounds(origin, origin.offset(2, 7, 2)))
        val computer = ConfiguratorOverlayDevice(origin.west(3), "computer", id)
        val other = ConfiguratorOverlayDevice(origin.east(5), "other")
        val snapshot = ConfiguratorOverlaySnapshot(listOf(boiler, computer, other), emptyList())
        for (mode in 0..2) {
            for (hover in listOf(origin, origin.offset(1, 4, 1), origin.offset(2, 7, 2))) {
                val outlines = ConfiguratorOverlayGeometry.outlines(snapshot, mode, hover, null, null)
                assertEquals(3, outlines.size)
                assertTrue(outlines[0].strong && outlines[1].strong && !outlines[2].strong)
                assertEquals(3.016, outlines[0].bounds.xsize, 0.000001)
                assertEquals(8.016, outlines[0].bounds.ysize, 0.000001)
                assertEquals(3.016, outlines[0].bounds.zsize, 0.000001)
            }
        }
        assertFalse(boiler.contains(origin.above(8)))
        assertFalse(boiler.contains(null))
    }

    @Test
    fun `provider geometry rejects unbounded fractional reversed or unrelated bounds`() {
        val origin = BlockPos.ZERO
        val valid =
            net.minecraft.world.phys
                .AABB(origin)
                .expandTowards(2.0, 7.0, 2.0)
        assertNotNull(ConfiguratorDeviceBounds.from(valid, origin, origin.above(7)))
        assertNull(ConfiguratorDeviceBounds.from(valid, origin, origin.above(8)))
        assertNull(ConfiguratorDeviceBounds.from(valid.move(0.5, 0.0, 0.0), origin, origin))
        assertNull(ConfiguratorDeviceBounds.from(valid.expandTowards(64.0, 0.0, 0.0), origin, origin))
        assertFailsWith<IllegalArgumentException> { ConfiguratorDeviceBounds(origin, origin.west()) }
        assertFailsWith<IllegalArgumentException> { ConfiguratorDeviceBounds(origin, origin.above(64)) }
        assertFailsWith<IllegalArgumentException> {
            ConfiguratorOverlayDevice(origin.above(10), "invalid", bounds = ConfiguratorDeviceBounds(origin, origin.above(7)))
        }
    }

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
    fun `joined displays have one rectangular outline in every mode and orientation`() {
        val network = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbc")
        for (facing in listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            val origin = BlockPos(197, -58, 176)
            val screen =
                ConfiguratorOverlayScreen(id, "wall", origin, facing, 3, 2, listOf(origin)).let { it.copy(panels = it.cells()) }
            val devices = screen.panels.map { ConfiguratorOverlayDevice(it, "display", network, screen = id) }
            val snapshot = ConfiguratorOverlaySnapshot(devices, listOf(screen))
            for (mode in 0..2) {
                val outline = ConfiguratorOverlayGeometry.outlines(snapshot, mode, screen.panels.last(), null, null).single()
                assertEquals(ConfiguratorOverlayGeometry.color(if (mode == 2) id else network), outline.color)
                assertTrue(outline.strong)
                assertFalse(outline.dashed)
                val width = if (facing.axis == Direction.Axis.X) outline.bounds.zsize else outline.bounds.xsize
                assertEquals(3.016, width, 0.000001)
                assertEquals(2.016, outline.bounds.ysize, 0.000001)
            }
        }
    }

    @Test
    fun `missing panels remain dashed holes inside the single screen outline`() {
        for (facing in listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            val origin = BlockPos(197, -58, 176)
            val rectangle = ConfiguratorOverlayScreen(id, "wall", origin, facing, 3, 3, listOf(origin))
            val hole = rectangle.position(1, 1)
            val screen = rectangle.copy(panels = rectangle.cells() - hole)
            val snapshot =
                ConfiguratorOverlaySnapshot(screen.panels.map { ConfiguratorOverlayDevice(it, "display", screen = id) }, listOf(screen))
            for (mode in 0..2) {
                val outlines = ConfiguratorOverlayGeometry.outlines(snapshot, mode, null, null, id)
                assertEquals(2, outlines.size)
                assertEquals(ConfiguratorOverlayGeometry.color(id), outlines.single { !it.dashed }.color)
                val missing = outlines.single { it.dashed }
                assertEquals(ConfiguratorOverlayGeometry.UNJOINED_COLOR, missing.color)
                assertTrue(outlines.all { it.strong })
                assertEquals(hole.y + 0.5, (missing.bounds.minY + missing.bounds.maxY) / 2, 0.000001)
            }
        }
    }

    @Test
    fun `screen rectangle retains network highlighting while individual devices remain visible`() {
        val network = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbc")
        val origin = BlockPos.ZERO
        val screen = ConfiguratorOverlayScreen(id, "wall", origin, Direction.NORTH, 2, 1, listOf(origin, origin.west()))
        val computer = ConfiguratorOverlayDevice(BlockPos(4, 0, 0), "computer", network)
        val unrelated = ConfiguratorOverlayDevice(BlockPos(6, 0, 0), "other")
        val devices = screen.panels.map { ConfiguratorOverlayDevice(it, "display", network, screen = id) } + computer + unrelated
        val snapshot = ConfiguratorOverlaySnapshot(devices, listOf(screen))
        for (mode in 0..1) {
            for (hover in listOf(computer.position, null)) {
                val outlines = ConfiguratorOverlayGeometry.outlines(snapshot, mode, hover, network.takeIf { hover == null }, null)
                assertEquals(3, outlines.size)
                val screenOutline = outlines.last()
                assertTrue(screenOutline.strong)
                assertEquals(ConfiguratorOverlayGeometry.color(network), screenOutline.color)
                assertTrue(outlines.first().strong)
                assertFalse(outlines[1].strong)
            }
        }
        val incomplete = ConfiguratorOverlaySnapshot(devices, emptyList(), true)
        assertEquals(devices.size, ConfiguratorOverlayGeometry.outlines(incomplete, 2, null, null, null).size)
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
