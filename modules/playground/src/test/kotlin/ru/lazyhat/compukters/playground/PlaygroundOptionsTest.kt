/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.playground

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaygroundOptionsTest {
    @Test
    fun `parses project emit and debug in any order`() {
        assertEquals(
            PlaygroundOptions(Path.of("example"), Path.of("out.cpkt"), debug = true),
            PlaygroundOptions.parse(listOf("--debug", "example", "--emit", "out.cpkt")),
        )
    }

    @Test
    fun `rejects missing duplicate and unknown arguments`() {
        listOf(
            emptyList(),
            listOf("a", "b"),
            listOf("a", "--emit"),
            listOf("a", "--emit", "one", "--emit", "two"),
            listOf("a", "--debug", "--debug"),
            listOf("a", "--unknown"),
        ).forEach { arguments ->
            assertFailsWith<PlaygroundUsageException>(arguments.toString()) {
                PlaygroundOptions.parse(arguments)
            }
        }
    }
}
