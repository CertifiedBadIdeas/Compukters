/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

/** GLFW physical key codes in the stable Guest Key enum order; no Minecraft dependency. */
object KeyboardKeys {
    val codes: List<Int> =
        (65..90).toList() + (48..57).toList() +
            listOf(32, 257, 258, 259, 340, 341, 342, 265, 264, 263, 262) + (290..301).toList() + listOf(261, 268, 269, 266, 267)

    fun index(code: Int): Int =
        codes.indexOf(
            when (code) {
                344 -> 340
                345 -> 341
                346 -> 342
                else -> code
            },
        )
}
