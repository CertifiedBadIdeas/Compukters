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

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply

object IdePeripheralDiscovery {
    fun snapshot(
        level: ServerLevel,
        computer: BlockPos,
    ): IdeTargetReply.NamedPeripherals {
        val traversal = PeripheralWorldDiscovery.discover(level, computer)
        require(traversal is PeripheralCableTraversal.Complete) { "Peripheral discovery limit exceeded" }
        val names = PeripheralNetworkAccess.names(level, traversal.contacts)
        val named = traversal.contacts.mapNotNull { identity -> names.nameOf(identity)?.let { identity to it } }
        val counts = named.groupingBy { it.second }.eachCount()
        val entries =
            named
                .sortedWith(compareBy({ it.second }, { it.first.providerId }, { it.first.deviceKey }))
                .take(1024)
                .map { (identity, name) ->
                    val item = level.getBlockState(identity.anchor).block.asItem()
                    IdeNamedPeripheral(name, BuiltInRegistries.ITEM.getKey(item).toString(), counts.getValue(name) > 1)
                }
        return IdeTargetReply.NamedPeripherals(entries, named.size > entries.size)
    }
}
