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

import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaygroundOutcomeTest {
    @Test
    fun `in-game compilation requests fail explicitly in the standalone playground`() {
        val outcome =
            VmOutcome.CompilationRequested(
                VmCompilationRequest(1, listOf(VmCompilationSource("/home/main.kt", "fun main() {}".encodeToByteArray()))),
            )

        assertEquals(
            PlaygroundExecution.PlatformFailure("guest requested the unavailable in-game compiler service"),
            unsupportedPlaygroundOutcome(outcome),
        )
    }
}
