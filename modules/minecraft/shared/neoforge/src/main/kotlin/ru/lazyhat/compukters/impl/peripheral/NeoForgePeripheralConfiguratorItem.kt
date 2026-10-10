/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem

/** Configure a target before its default interaction opens a terminal or inventory. */
class NeoForgePeripheralConfiguratorItem(
    properties: Item.Properties,
) : PeripheralConfiguratorItem(properties) {
    override fun onItemUseFirst(
        stack: ItemStack,
        context: UseOnContext,
    ): InteractionResult = useOn(context)
}
