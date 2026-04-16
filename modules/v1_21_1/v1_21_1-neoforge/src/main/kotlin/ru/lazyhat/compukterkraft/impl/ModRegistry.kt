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
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.computer.item.ComputerItem
import ru.lazyhat.compukterkraft.common.computer.loot.BlockNamedEntityLootCondition
import ru.lazyhat.compukterkraft.common.computer.loot.ConstantLootConditionSerializer
import ru.lazyhat.compukterkraft.common.computer.loot.HasComputerIdLootCondition
import ru.lazyhat.compukterkraft.common.computer.loot.PlayerCreativeLootCondition
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenuWithoutInventory
import ru.lazyhat.compukterkraft.common.workbench.block.WorkbenchBlock
import ru.lazyhat.compukterkraft.common.workbench.block.WorkbenchBlockEntity
import ru.lazyhat.compukterkraft.common.workbench.data.WorkbenchContainerData
import ru.lazyhat.compukterkraft.common.workbench.item.WorkbenchItem
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuWithoutInventory
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.impl.computer.block.NeoForgeComputerBlockEntity
import java.util.function.Supplier

object ModRegistry {
    object Names {
        const val COMPUTER_ADVANCED = "computer_advanced"
        const val COMPUTER = "computer"
        const val WORKBENCH = "workbench"
    }

    object Blocks {
        val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, MOD_ID)

        private fun properties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2f)

        private fun noRedstoneConductor(): BlockBehaviour.Properties = properties().isRedstoneConductor { _, _, _ -> false }

        private fun turtleProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2.5f)

        private fun modemProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(1.5f)

        val COMPUTER_ADVANCED: DeferredHolder<Block, ComputerBlock> =
            REGISTRY
                .register(
                    Names.COMPUTER_ADVANCED,
                    Supplier {
                        ComputerBlock(noRedstoneConductor().mapColor(MapColor.STONE))
                    },
                )

        val WORKBENCH: DeferredHolder<Block, WorkbenchBlock> =
            REGISTRY.register(
                Names.WORKBENCH,
                Supplier {
                    WorkbenchBlock(noRedstoneConductor().mapColor(MapColor.WOOD))
                },
            )
    }

    object BlockEntities {
        val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID)

        @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        private fun <T : BlockEntity, B : Block> ofBlock(
            block: DeferredHolder<Block, B>,
            name: String,
            factory: (BlockPos, BlockState) -> T,
        ): DeferredHolder<BlockEntityType<*>, BlockEntityType<T>> =
            REGISTRY.register(
                name,
                Supplier {
                    BlockEntityType.Builder.of(factory, block.get()).build(null)
                },
            )

        val COMPUTER_ADVANCED: DeferredHolder<BlockEntityType<*>, BlockEntityType<ComputerBlockEntity>> =
            ofBlock(
                Blocks.COMPUTER_ADVANCED,
                Names.COMPUTER_ADVANCED,
            ) { p, s -> NeoForgeComputerBlockEntity(COMPUTER_ADVANCED.get(), p, s, ComputerFamily.ADVANCED) }

        val WORKBENCH: DeferredHolder<BlockEntityType<*>, BlockEntityType<WorkbenchBlockEntity>> =
            ofBlock(
                Blocks.WORKBENCH,
                Names.WORKBENCH,
            ) { p, s -> WorkbenchBlockEntity(p, s) }
    }

    object Items {
        val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, MOD_ID)

        private fun properties(): Item.Properties = Item.Properties()

        private fun <I : Item, B : Block> ofBlock(
            parent: DeferredHolder<Block, B>,
            name: String,
            factory: (B, Item.Properties) -> I,
        ): DeferredHolder<Item, I> =
            REGISTRY.register(
                name,
                Supplier {
                    factory(parent.get(), properties())
                },
            )

        val COMPUTER_ADVANCED: DeferredHolder<Item, ComputerItem> =
            ofBlock(
                Blocks.COMPUTER_ADVANCED,
                Names.COMPUTER_ADVANCED,
            ) { block, properties -> ComputerItem(block, properties) }

        val WORKBENCH: DeferredHolder<Item, WorkbenchItem> =
            ofBlock(
                Blocks.WORKBENCH,
                Names.WORKBENCH,
            ) { block, properties -> WorkbenchItem(block, properties) }
    }

    object LootItemConditionTypes {
        val REGISTRY: DeferredRegister<LootItemConditionType> =
            DeferredRegister.create(
                Registries.LOOT_CONDITION_TYPE,
                MOD_ID,
            )

        val BLOCK_NAMED: DeferredHolder<LootItemConditionType, LootItemConditionType> =
            REGISTRY.register(
                "block_named",
                Supplier { ConstantLootConditionSerializer.type(BlockNamedEntityLootCondition) },
            )

        val PLAYER_CREATIVE: DeferredHolder<LootItemConditionType, LootItemConditionType> =
            REGISTRY.register(
                "player_creative",
                Supplier { ConstantLootConditionSerializer.type(PlayerCreativeLootCondition) },
            )

        val HAS_ID: DeferredHolder<LootItemConditionType, LootItemConditionType> =
            REGISTRY.register(
                "has_id",
                Supplier { ConstantLootConditionSerializer.type(HasComputerIdLootCondition) },
            )
    }

    object Menus {
        val REGISTRY = DeferredRegister.create(Registries.MENU, MOD_ID)

        val COMPUTER: DeferredHolder<MenuType<*>, MenuType<ComputerMenuWithoutInventory>> =
            REGISTRY.register(
                Names.COMPUTER,
                Supplier {
                    IMenuTypeExtension.create { id, playerInventory, data ->
                        ComputerMenuWithoutInventory(
                            COMPUTER.get(),
                            id,
                            playerInventory,
                            ComputerContainerData(data),
                        ).also {
                            LOGGER.info { "ClientRegistry: ComputerMenuWithoutInventory from buffer created" }
                        }
                    }
                },
            )

        val WORKBENCH: DeferredHolder<MenuType<*>, MenuType<WorkbenchMenuWithoutInventory>> =
            REGISTRY.register(
                Names.WORKBENCH,
                Supplier {
                    IMenuTypeExtension.create { id, playerInventory, data ->
                        WorkbenchMenuWithoutInventory(
                            WORKBENCH.get(),
                            id,
                            playerInventory,
                            WorkbenchContainerData(data),
                        )
                    }
                },
            )
    }

    object CreativeTabs {
        val REGISTRY: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)

        val TAB =
            REGISTRY
                .register(
                    "tab",
                    Supplier {
                        CreativeModeTab
                            .builder()
                            .icon { ItemStack(Items.COMPUTER_ADVANCED.get()) }
                            .title(Component.translatable("itemGroup.compukterkraft"))
                            .displayItems { _, out ->
                                out.accept(ItemStack(Items.COMPUTER_ADVANCED.get()))
                                out.accept(ItemStack(Items.WORKBENCH.get()))
                            }.build()
                    },
                )
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
