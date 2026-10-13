/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.world.phys.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfiguratorOutlineProjectionTest {
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
