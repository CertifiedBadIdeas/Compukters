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

import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension
import java.util.UUID

internal object PeripheralInstanceStamps {
    fun stamp(
        entity: BlockEntity,
        create: Boolean,
    ): UUID? {
        val data = (entity as IBlockEntityExtension).persistentData
        val value = data.getString("compukters_peripheral_instance").orElse("")
        runCatching { UUID.fromString(value) }.getOrNull()?.let { return it }
        if (!create) return null
        return UUID.randomUUID().also { id ->
            data.putString("compukters_peripheral_instance", id.toString())
            entity.setChanged()
        }
    }
}
