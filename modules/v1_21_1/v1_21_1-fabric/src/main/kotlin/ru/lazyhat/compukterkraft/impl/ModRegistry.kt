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

package ru.lazyhat.compukterkraft.impl

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compukterkraft.common.asResource
import ru.lazyhat.compukterkraft.common.block.ComputerBlock
import ru.lazyhat.compukterkraft.common.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.item.ComputerItem
import ru.lazyhat.compukterkraft.common.loot.BlockNamedEntityLootCondition
import ru.lazyhat.compukterkraft.common.loot.ConstantLootConditionSerializer
import ru.lazyhat.compukterkraft.common.loot.HasComputerIdLootCondition
import ru.lazyhat.compukterkraft.common.loot.PlayerCreativeLootCondition
import ru.lazyhat.compukterkraft.common.menu.ComputerMenuWithoutInventory
import ru.lazyhat.compukterkraft.core.block.ComputerFamily

object ModRegistry {
    object Names {
        const val COMPUTER_ADVANCED = "computer_advanced"
        const val COMPUTER = "computer"
    }

    private fun id(name: String): ResourceLocation = name.asResource()

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
                    ComputerBlock(noRedstoneConductor().mapColor(MapColor.STONE)),
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
                    BlockEntityType.Builder
                        .of(
                            { p: BlockPos, s: BlockState ->
                                ComputerBlockEntity(
                                    COMPUTER_ADVANCED,
                                    p,
                                    s,
                                    ComputerFamily.ADVANCED,
                                )
                            },
                            Blocks.COMPUTER_ADVANCED,
                        ).build(null),
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
                    ExtendedScreenHandlerType(
                        { syncId, playerInventory, data ->
                            ComputerMenuWithoutInventory(COMPUTER, syncId, playerInventory, data)
                        },
                        StreamCodec.of(
                            { buf: RegistryFriendlyByteBuf, data: ComputerContainerData -> data.toBytes(buf) },
                            { buf: RegistryFriendlyByteBuf -> ComputerContainerData(buf) },
                        ),
                    ),
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
