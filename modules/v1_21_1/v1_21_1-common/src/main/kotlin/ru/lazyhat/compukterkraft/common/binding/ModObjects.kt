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

package ru.lazyhat.compukterkraft.common.binding

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compukterkraft.common.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.menu.ComputerMenuWithoutInventory

object ModObjects {
    lateinit var computerBlockEntityType: () -> BlockEntityType<ComputerBlockEntity>
    lateinit var computerMenuType: () -> MenuType<ComputerMenuWithoutInventory>
    lateinit var openComputerMenu: (ServerPlayer, AbstractComputerBlockEntity, ComputerContainerData) -> Unit
    lateinit var blockNamedEntityLootConditionType: () -> LootItemConditionType
    lateinit var hasComputerIdLootConditionType: () -> LootItemConditionType
    lateinit var playerCreativeLootConditionType: () -> LootItemConditionType
}
