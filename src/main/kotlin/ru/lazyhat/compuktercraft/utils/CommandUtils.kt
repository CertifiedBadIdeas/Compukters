// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.utils

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
