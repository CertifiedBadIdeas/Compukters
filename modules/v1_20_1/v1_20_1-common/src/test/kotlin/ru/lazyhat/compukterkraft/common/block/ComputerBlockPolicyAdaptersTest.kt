package ru.lazyhat.compukterkraft.common.block

import net.minecraft.core.Direction
import ru.lazyhat.compukterkraft.core.content.ComputerVisualStateModel
import ru.lazyhat.compukterkraft.core.content.HorizontalFacingModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComputerBlockPolicyAdaptersTest {
    @Test
    fun facingRoundTripPreservesAllHorizontalDirections() {
        HorizontalFacingModel.entries.forEach { facing ->
            assertEquals(facing, facing.toMinecraftDirection().toFacingModel())
        }
    }

    @Test
    fun visualStateRoundTripPreservesAllStates() {
        ComputerVisualStateModel.entries.forEach { state ->
            assertEquals(state, state.toMinecraftState().toStateModel())
        }
    }

    @Test
    fun rejectsVerticalDirections() {
        assertFailsWith<IllegalStateException> { Direction.UP.toFacingModel() }
        assertFailsWith<IllegalStateException> { Direction.DOWN.toFacingModel() }
    }
}