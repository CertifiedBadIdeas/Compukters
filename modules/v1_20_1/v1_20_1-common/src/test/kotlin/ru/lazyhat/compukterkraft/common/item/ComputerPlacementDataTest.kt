package ru.lazyhat.compukterkraft.common.item

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import ru.lazyhat.compukterkraft.core.content.ComputerItemDataPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerPlacementDataTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun createPlacementStack(data: ComputerItemData): ItemStack =
        ItemStack(Items.STONE).also {
            it.writeComputerItemData(data)
        }

    @Test
    fun storedPlacementDataPreservesExistingIdentityThroughPlacementResolution() {
        val stack = createPlacementStack(ComputerItemData(computerId = 7, label = "alpha"))
        val stored = stack.readComputerItemData()
        var allocationCalls = 0
        val resolved = ComputerItemDataPolicy.resolvePlacedData(stored) {
            allocationCalls += 1
            99
        }

        assertEquals(ComputerItemData(computerId = 7, label = "alpha"), stored)
        assertEquals(stored, resolved)
        assertEquals(0, allocationCalls)
    }

    @Test
    fun storedPlacementDataKeepsLabelWhenPlacementAllocatesMissingIdentity() {
        val stack = createPlacementStack(ComputerItemData(computerId = null, label = "alpha"))
        val stored = stack.readComputerItemData()
        var allocationCalls = 0
        val resolved = ComputerItemDataPolicy.resolvePlacedData(stored) {
            allocationCalls += 1
            11
        }

        assertEquals(ComputerItemData(computerId = null, label = "alpha"), stored)
        assertEquals(ComputerItemData(computerId = 11, label = "alpha"), resolved)
        assertEquals(1, allocationCalls)
    }
}