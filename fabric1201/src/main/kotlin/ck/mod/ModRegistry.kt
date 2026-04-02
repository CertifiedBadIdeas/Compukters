@file:Suppress("ktlint:standard:property-naming")
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
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import java.util.function.Supplier

object ModRegistry {
    object Names {
        const val COMPUTER_ADVANCED = "computer_advanced"
        const val COMPUTER = "computer"
    }

    private fun id(name: String): ResourceLocation = ResourceLocation(MOD_ID, name)

    object Blocks {
        private fun properties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2f)

        private fun noRedstoneConductor(): BlockBehaviour.Properties = properties().isRedstoneConductor { _, _, _ -> false }

        lateinit var COMPUTER_ADVANCED: ComputerBlock
            private set

        fun register() {
            COMPUTER_ADVANCED =
                Registry.register(
                    BuiltInRegistries.BLOCK,
                    id(Names.COMPUTER_ADVANCED),
                    ComputerBlock(Supplier { BlockEntities.COMPUTER_ADVANCED }, noRedstoneConductor().mapColor(MapColor.STONE)),
                )
        }
    }

    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    object BlockEntities {
        lateinit var COMPUTER_ADVANCED: BlockEntityType<ComputerBlockEntity>
            private set

        fun register() {
            COMPUTER_ADVANCED =
                Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    id(Names.COMPUTER_ADVANCED),
                    BlockEntityType(
                        { p: BlockPos, s: BlockState -> ComputerBlockEntity(COMPUTER_ADVANCED, p, s, ComputerFamily.ADVANCED) },
                        setOf(Blocks.COMPUTER_ADVANCED),
                        null,
                    ),
                )
        }
    }

    object Items {
        private fun properties(): Item.Properties = Item.Properties()

        lateinit var COMPUTER_ADVANCED: ComputerItem
            private set

        fun register() {
            COMPUTER_ADVANCED =
                Registry.register(
                    BuiltInRegistries.ITEM,
                    id(Names.COMPUTER_ADVANCED),
                    ComputerItem(Blocks.COMPUTER_ADVANCED, properties()),
                )
        }
    }

    object LootItemConditionTypes {
        lateinit var BLOCK_NAMED: LootItemConditionType
            private set
        lateinit var PLAYER_CREATIVE: LootItemConditionType
            private set
        lateinit var HAS_ID: LootItemConditionType
            private set

        fun register() {
            BLOCK_NAMED =
                Registry.register(
                    BuiltInRegistries.LOOT_CONDITION_TYPE,
                    id("block_named"),
                    ConstantLootConditionSerializer.type(BlockNamedEntityLootCondition),
                )
            PLAYER_CREATIVE =
                Registry.register(
                    BuiltInRegistries.LOOT_CONDITION_TYPE,
                    id("player_creative"),
                    ConstantLootConditionSerializer.type(PlayerCreativeLootCondition),
                )
            HAS_ID =
                Registry.register(
                    BuiltInRegistries.LOOT_CONDITION_TYPE,
                    id("has_id"),
                    ConstantLootConditionSerializer.type(HasComputerIdLootCondition),
                )
        }
    }

    object Menus {
        lateinit var COMPUTER: MenuType<ComputerMenuWithoutInventory>
            private set

        fun register() {
            COMPUTER =
                Registry.register(
                    BuiltInRegistries.MENU,
                    id(Names.COMPUTER),
                    ExtendedScreenHandlerType { syncId, playerInventory, data ->
                        ComputerMenuWithoutInventory(COMPUTER, syncId, playerInventory, ComputerContainerData(data))
                    },
                )
        }
    }

    object CreativeTabs {
        lateinit var TAB: CreativeModeTab
            private set

        fun register() {
            TAB =
                Registry.register(
                    BuiltInRegistries.CREATIVE_MODE_TAB,
                    id("tab"),
                    FabricItemGroup
                        .builder()
                        .icon { ItemStack(Items.COMPUTER_ADVANCED) }
                        .title(Component.translatable("itemGroup.compukterkraft"))
                        .displayItems { _, out ->
                            out.accept(ItemStack(Items.COMPUTER_ADVANCED))
                        }.build(),
                )
        }
    }

    fun register() {
        Blocks.register()
        BlockEntities.register()
        Items.register()
        Menus.register()
        LootItemConditionTypes.register()
        CreativeTabs.register()
    }
}
