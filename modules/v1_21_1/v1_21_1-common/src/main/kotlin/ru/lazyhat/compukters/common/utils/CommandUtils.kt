/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.common.utils

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.CompletableFuture
import java.util.function.Function

object CommandUtils {
    fun isPlayer(output: CommandSourceStack): Boolean {
        val player: ServerPlayer? = output.getPlayer()
        return player != null && !(player.connection == null || player::class.java != ServerPlayer::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    fun suggestOnServer(
        context: CommandContext<*>,
        supplier: Function<CommandContext<CommandSourceStack>, CompletableFuture<Suggestions>>,
    ): CompletableFuture<Suggestions> =
        when (val source = context.getSource()) {
            !is SharedSuggestionProvider -> {
                Suggestions.empty()
            }

            is CommandSourceStack -> {
                supplier.apply(context as CommandContext<CommandSourceStack>)
            }

            else -> {
                source.customSuggestion(context)
            }
        }

    fun <T> suggest(
        builder: SuggestionsBuilder,
        candidates: Iterable<T>,
        toString: Function<T, String>,
    ): CompletableFuture<Suggestions> {
        val remaining: String = builder.remaining.lowercase()
        for (choice in candidates) {
            val name = toString.apply(choice)
            if (!name.lowercase().startsWith(remaining)) continue
            builder.suggest(name)
        }

        return builder.buildFuture()
    }

    fun <T> suggest(
        builder: SuggestionsBuilder,
        candidates: Array<T>,
        toString: Function<T, String>,
    ): CompletableFuture<Suggestions> = suggest(builder, candidates.toList(), toString)
}
