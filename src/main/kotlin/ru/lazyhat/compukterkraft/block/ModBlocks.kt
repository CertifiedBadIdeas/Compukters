package ru.lazyhat.compukterkraft.block

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import ru.lazyhat.compukterkraft.CompukterCraftMod
import ru.lazyhat.compukterkraft.item.ModItems
import thedarkcolour.kotlinforforge.forge.registerObject

object ModBlocks {
    val REGISTRY = DeferredRegister.create(ForgeRegistries.BLOCKS, CompukterCraftMod.ID)

    val STEEL_BLOCK by REGISTRY.registerObject("steel_block") {
        Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK))
    }

    val STEEL_BLOCK_ITEM by ModItems.REGISTRY.registerObject("steel_block") {
        BlockItem(STEEL_BLOCK, Item.Properties())
    }
}
