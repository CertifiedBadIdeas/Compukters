package ru.lazyhat.compuktercraft

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject
import ru.lazyhat.compuktercraft.block.ComputerBlock
import ru.lazyhat.compuktercraft.block.ComputerBlockEntity
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.item.ComputerItem
import ru.lazyhat.compuktercraft.loot.BlockNamedEntityLootCondition
import ru.lazyhat.compuktercraft.loot.ConstantLootConditionSerializer
import ru.lazyhat.compuktercraft.loot.HasComputerIdLootCondition
import ru.lazyhat.compuktercraft.loot.PlayerCreativeLootCondition
import thedarkcolour.kotlinforforge.forge.MOD_BUS

object ModRegistry {
    object Names {
        const val COMPUTER_ADVANCED = "computer_advanced"
    }

    object Blocks {
        val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, CompukterCraftMod.ID)

        private fun properties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2f)

        private fun noRedstoneConductor(): BlockBehaviour.Properties = properties().isRedstoneConductor { _, _, _ -> false }

        private fun turtleProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2.5f)

        private fun modemProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(1.5f)

        val COMPUTER_ADVANCED: RegistryObject<ComputerBlock> =
            REGISTRY
                .register(Names.COMPUTER_ADVANCED) {
                    ComputerBlock(BlockEntities.COMPUTER_ADVANCED, noRedstoneConductor().mapColor(MapColor.STONE))
                }
    }

    object BlockEntities {
        val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CompukterCraftMod.ID)

        private fun <T : BlockEntity, B : Block> ofBlock(
            block: RegistryObject<B>,
            name: String,
            factory: (BlockPos, BlockState) -> T,
        ): RegistryObject<BlockEntityType<T>> = REGISTRY.register(name) { BlockEntityType(factory, setOf(block.get()), null) }

        val COMPUTER_ADVANCED: RegistryObject<BlockEntityType<ComputerBlockEntity>> =
            ofBlock(
                Blocks.COMPUTER_ADVANCED,
                Names.COMPUTER_ADVANCED,
            ) { p, s -> ComputerBlockEntity(COMPUTER_ADVANCED.get(), p, s, ComputerFamily.ADVANCED) }
    }

    object Items {
        val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, CompukterCraftMod.ID)

        private fun properties(): Item.Properties = Item.Properties()

        private fun <I : Item, B : Block> ofBlock(
            parent: RegistryObject<B>,
            name: String,
            factory: (B, Item.Properties) -> I,
        ): RegistryObject<I> =
            REGISTRY.register(name) {
                factory(parent.get(), properties())
            }

        val COMPUTER_ADVANCED: RegistryObject<ComputerItem> =
            ofBlock(
                Blocks.COMPUTER_ADVANCED,
                Names.COMPUTER_ADVANCED,
            ) { block, properties -> ComputerItem(block, properties) }
    }

    object LootItemConditionTypes {
        val REGISTRY: DeferredRegister<LootItemConditionType> =
            DeferredRegister.create(
                Registries.LOOT_CONDITION_TYPE,
                CompukterCraftMod.ID,
            )

        val BLOCK_NAMED: RegistryObject<LootItemConditionType> =
            REGISTRY.register(
                "block_named",
                { ConstantLootConditionSerializer.type(BlockNamedEntityLootCondition) },
            )

        val PLAYER_CREATIVE: RegistryObject<LootItemConditionType> =
            REGISTRY.register(
                "player_creative",
                { ConstantLootConditionSerializer.type(PlayerCreativeLootCondition) },
            )

        val HAS_ID: RegistryObject<LootItemConditionType> =
            REGISTRY.register(
                "has_id",
                { ConstantLootConditionSerializer.type(HasComputerIdLootCondition) },
            )
    }

    object CreativeTabs {
        val REGISTRY: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CompukterCraftMod.ID)

        val TAB =
            REGISTRY
                .register("tab") {
                    CreativeModeTab
                        .builder()
                        .icon { ItemStack(Items.COMPUTER_ADVANCED.get()) }
                        .title(Component.translatable("itemGroup.compuktercraft"))
                        .displayItems { context, out ->
                            out.accept(ItemStack(Items.COMPUTER_ADVANCED.get()))
                        }.build()
                }
    }

    fun register() {
        Blocks.REGISTRY.register(MOD_BUS)
        BlockEntities.REGISTRY.register(MOD_BUS)
        Items.REGISTRY.register(MOD_BUS)
        LootItemConditionTypes.REGISTRY.register(MOD_BUS)
        CreativeTabs.REGISTRY.register(MOD_BUS)
    }
}
