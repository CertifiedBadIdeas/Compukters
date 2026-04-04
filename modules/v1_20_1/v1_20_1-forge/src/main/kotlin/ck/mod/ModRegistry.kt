/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ck.mod

import ck.mod.block.ComputerBlock
import ck.mod.block.ComputerBlockEntity
import ck.mod.block.ComputerFamily
import ck.mod.data.ComputerContainerData
import ck.mod.item.ComputerItem
import ck.mod.loot.BlockNamedEntityLootCondition
import ck.mod.loot.ConstantLootConditionSerializer
import ck.mod.loot.HasComputerIdLootCondition
import ck.mod.loot.PlayerCreativeLootCondition
import ck.mod.menu.ComputerMenuWithoutInventory
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.MenuType
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
import net.minecraftforge.common.extensions.IForgeMenuType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject

object ModRegistry {
    object Names {
        const val COMPUTER_ADVANCED = "computer_advanced"
        const val COMPUTER = "computer"
    }

    object Blocks {
        val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, MOD_ID)

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
        val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID)

        @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
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
        val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, MOD_ID)

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
                MOD_ID,
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

    object Menus {
        val REGISTRY = DeferredRegister.create(Registries.MENU, MOD_ID)

        val COMPUTER: RegistryObject<MenuType<ComputerMenuWithoutInventory>> =
            REGISTRY.register(Names.COMPUTER) {
                IForgeMenuType.create { id, playerInventory, data ->
                    ComputerMenuWithoutInventory(COMPUTER.get(), id, playerInventory, ComputerContainerData(data)).also {
                        // LOGGER.info("ClientRegistry: ComputerMenuWithoutInventory from buffer created")
                    }
                }
            }
    }

    object CreativeTabs {
        val REGISTRY: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)

        val TAB =
            REGISTRY
                .register("tab") {
                    CreativeModeTab
                        .builder()
                        .icon { ItemStack(Items.COMPUTER_ADVANCED.get()) }
                        .title(Component.translatable("itemGroup.compukterkraft"))
                        .displayItems { context, out ->
                            out.accept(ItemStack(Items.COMPUTER_ADVANCED.get()))
                        }.build()
                }
    }

    fun register(modEventBus: IEventBus) {
        Blocks.REGISTRY.register(modEventBus)
        BlockEntities.REGISTRY.register(modEventBus)
        Items.REGISTRY.register(modEventBus)
        Menus.REGISTRY.register(modEventBus)
        LootItemConditionTypes.REGISTRY.register(modEventBus)
        CreativeTabs.REGISTRY.register(modEventBus)
    }
}
