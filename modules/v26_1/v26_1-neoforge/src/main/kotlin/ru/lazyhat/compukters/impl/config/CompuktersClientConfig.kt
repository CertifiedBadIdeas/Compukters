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
 */

package ru.lazyhat.compukters.impl.config

import net.neoforged.neoforge.common.ModConfigSpec
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import java.util.function.Predicate

object CompuktersClientConfig {
    private val builder = ModConfigSpec.Builder()

    @Suppress("UNCHECKED_CAST")
    private val terminalFontValidator =
        Predicate<Any?> { value ->
            value is String && TerminalFontProfile.ALL.any { it.id == value }
        } as Predicate<Any>

    internal val terminalFontId =
        builder
            .comment("Font used by the local terminal screen")
            .define("terminal.font", TerminalFontProfile.DEFAULT.id, terminalFontValidator)

    val SPEC: ModConfigSpec = builder.build()

    fun selectedFont(): TerminalFontProfile = TerminalFontProfile.fromId(terminalFontId.get())

    fun selectFont(profile: TerminalFontProfile) {
        terminalFontId.set(profile.id)
        terminalFontId.save()
    }
}
