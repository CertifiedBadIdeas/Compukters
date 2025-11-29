package ru.lazyhat.compukterkraft.item

import net.minecraft.world.item.Item
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import ru.lazyhat.compukterkraft.CompukterCraftMod
import thedarkcolour.kotlinforforge.forge.registerObject

object ModItems {
    val REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, CompukterCraftMod.ID)

    val STEEL by REGISTRY.registerObject("steel") {
        Item(Item.Properties().stacksTo(32))
    }
}
