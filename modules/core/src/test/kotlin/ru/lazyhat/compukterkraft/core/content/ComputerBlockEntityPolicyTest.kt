package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerBlockEntityPolicyTest {
    @Test
    fun updatesVisualStateOnlyWhenItChanges() {
        assertFalse(
            ComputerBlockEntityPolicy.shouldUpdateVisualState(
                current = ComputerVisualStateModel.OFF,
                next = ComputerVisualStateModel.OFF,
            ),
        )
        assertTrue(
            ComputerBlockEntityPolicy.shouldUpdateVisualState(
                current = ComputerVisualStateModel.OFF,
                next = ComputerVisualStateModel.ON,
            ),
        )
    }

    @Test
    fun onlyPersistsChangedNonNullIdentityValues() {
        assertFalse(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = "alpha"))
        assertFalse(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = null))
        assertTrue(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = "beta"))

        assertFalse(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = 1))
        assertFalse(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = null))
        assertTrue(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = 2))
    }

    @Test
    fun resolvesExistingIdWithoutAllocating() {
        assertEquals(7, ComputerBlockEntityPolicy.resolveComputerId(current = 7) { 99 })
        assertEquals(99, ComputerBlockEntityPolicy.resolveComputerId(current = null) { 99 })
    }

    @Test
    fun skipsServerTickWhenClientSideOrIdMissing() {
        assertFalse(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = true, computerId = 1))
        assertFalse(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = false, computerId = null))
        assertTrue(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = false, computerId = 1))
    }
}