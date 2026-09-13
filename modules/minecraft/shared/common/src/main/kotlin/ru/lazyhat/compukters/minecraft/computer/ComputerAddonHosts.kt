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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.program.programAddonHostOf
import java.util.concurrent.CopyOnWriteArrayList

fun interface ComputerAddonHostFactory {
    fun create(
        level: ServerLevel,
        position: BlockPos,
        state: BlockState,
    ): ProgramAddonHost?
}

object ComputerAddonHosts {
    private val factories = CopyOnWriteArrayList<ComputerAddonHostFactory>()

    fun register(factory: ComputerAddonHostFactory) {
        require(factories.addIfAbsent(factory)) { "computer addon host factory is already registered" }
    }

    internal fun create(
        level: ServerLevel,
        position: BlockPos,
        state: BlockState,
    ): ProgramAddonHost {
        val hosts = factories.mapNotNull { it.create(level, position, state) }
        return try {
            programAddonHostOf(hosts)
        } catch (failure: Throwable) {
            hosts.asReversed().forEach { host -> runCatching(host::close).onFailure(failure::addSuppressed) }
            throw failure
        }
    }
}
