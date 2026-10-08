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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent
import ru.lazyhat.compukters.impl.benchmark.hasGameMasterPermission
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity

/** Explicit recovery preserves identity and files while discarding the stored execution state. */
internal object ComputerCommands {
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("compukters").requires(::hasGameMasterPermission).then(
                Commands.literal("reboot").then(
                    Commands.argument("position", BlockPosArgument.blockPos()).executes { context ->
                        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
                        val computer = context.source.level.getBlockEntity(position) as? ComputerBlockEntity
                        if (computer == null) {
                            context.source.sendFailure(Component.literal("No Compukter at this position"))
                            0
                        } else {
                            computer.reboot()
                            context.source.sendSuccess({ Component.literal("Compukter restart requested; /home files retained") }, true)
                            1
                        }
                    },
                ),
            ),
        )
    }
}
