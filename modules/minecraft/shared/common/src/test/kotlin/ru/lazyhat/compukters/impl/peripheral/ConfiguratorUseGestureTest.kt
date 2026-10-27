/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguratorUseGestureTest {
    @Test fun `short click acts once while hold never acts on its target`() {
        val use = ConfiguratorUseGesture(100)
        use.press(0)
        assertFalse(use.open(99))
        assertTrue(use.release(99))
        assertFalse(use.release(100))
        use.press(200)
        assertTrue(use.open(300))
        assertFalse(use.open(400))
        assertFalse(use.release(401))
        assertFalse(use.active)
    }

    @Test fun `focus loss and holds released between ticks cannot turn into clicks`() {
        val use = ConfiguratorUseGesture(100)
        use.press(0)
        use.cancel()
        assertFalse(use.release(10))
        use.press(100)
        assertFalse(use.release(200))
        use.press(300)
        use.press(350)
        assertTrue(use.open(400))
    }

    @Test fun `radial labels use the same sectors as selection and cancellation`() {
        for (index in ConfiguratorRadialGeometry.modes.indices) {
            assertEquals(
                index,
                ConfiguratorRadialGeometry.select(
                    ConfiguratorRadialGeometry.labelX(index).toDouble(),
                    ConfiguratorRadialGeometry.labelY(index).toDouble(),
                ),
            )
        }
        assertNull(ConfiguratorRadialGeometry.select(0.0, 0.0))
        assertNull(ConfiguratorRadialGeometry.select(200.0, 0.0))
        assertNull(ConfiguratorRadialGeometry.select(Double.NaN, 0.0))
        assertEquals(0, ConfiguratorRadialGeometry.select(0.0, -60.0))
    }
}
