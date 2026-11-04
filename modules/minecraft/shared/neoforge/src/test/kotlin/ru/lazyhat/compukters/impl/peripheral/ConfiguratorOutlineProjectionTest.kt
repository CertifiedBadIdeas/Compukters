/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguratorOutlineProjectionTest {
    @Test
    fun `extracted frame follows the interpolated construction pose and retains it until submission`() {
        val plotOrigin = Vec3(24000000.0, 80.0, 24000000.0)
        val camera = Vec3(118.0, 51.0, -25.0)
        val outline = ConfiguratorOutline(AABB(plotOrigin, plotOrigin.add(1.0, 1.0, 1.0)), 0xA8E878, true)
        val local = ConfiguratorOutlineMesh.frame(listOf(outline), 0.0, Vec3.ZERO) { it.subtract(plotOrigin) }
        var worldOrigin = Vec3(120.0, 50.0, -30.0)

        fun frame() =
            ConfiguratorOutlineMesh.frame(listOf(outline), 0.0, camera) { point ->
                val offset = point.subtract(plotOrigin)
                // The construction's current interpolated rotation and translation.
                worldOrigin.add(-offset.z, offset.y, offset.x)
            }
        val extracted = frame()
        assertTrue(extracted.isNotEmpty())
        extracted.zip(local).forEach { (actual, vertex) ->
            assertEquals(worldOrigin.add(-vertex.point.z, vertex.point.y, vertex.point.x).subtract(camera), actual.point)
        }
        val saved = extracted.map { it.point }
        // A new physics/frame update before deferred submission cannot move the saved frame.
        worldOrigin = worldOrigin.add(4.0, 0.0, 0.0)
        assertEquals(saved, extracted.map { it.point })
        frame().zip(extracted).forEach { (next, previous) ->
            assertEquals(4.0, next.point.x - previous.point.x, 0.00000001)
            assertEquals(previous.point.y, next.point.y)
            assertEquals(previous.point.z, next.point.z)
        }
    }

    @Test
    fun `unavailable construction contributes no frame geometry`() {
        val outline = ConfiguratorOutline(AABB(24000000.0, 80.0, 24000000.0, 24000001.0, 81.0, 24000001.0), 0xA8E878, true)
        assertTrue(ConfiguratorOutlineMesh.frame(listOf(outline), 0.0, Vec3.ZERO) { null }.isEmpty())
    }

    @Test
    fun `construction lookup receives storage coordinates before camera subtraction`() {
        val plotOrigin = Vec3(24000000.0, 80.0, 24000000.0)
        val storage = plotOrigin.add(2.0, 3.0, 4.0)
        val worldOrigin = Vec3(120.0, 50.0, -30.0)
        val camera = Vec3(118.0, 51.0, -25.0)
        val result =
            ConfiguratorOutlineMesh.cameraRelative(storage, camera) { point ->
                assertEquals(storage, point)
                val local = point.subtract(plotOrigin)
                // A scaled quarter-turn around Y, followed by world translation.
                worldOrigin.add(-local.z * 2.0, local.y * 2.0, local.x * 2.0)
            }
        assertEquals(Vec3(-6.0, 5.0, -1.0), result)
    }

    @Test
    fun `ordinary world edges retain their width far from the origin`() {
        val camera = Vec3(24000000.0, 70.0, -24000000.0)
        val point = camera.add(0.009, 1.0, 0.018)
        val result = ConfiguratorOutlineMesh.cameraRelative(point, camera) { it }!!
        assertEquals(0.009, result.x, 0.00000001)
        assertEquals(1.0, result.y)
        assertEquals(0.018, result.z, 0.00000001)
    }

    @Test
    fun `unavailable construction does not fall back to plot coordinates`() {
        assertNull(ConfiguratorOutlineMesh.cameraRelative(Vec3(24000000.0, 80.0, 24000000.0), Vec3.ZERO) { null })
    }
}
