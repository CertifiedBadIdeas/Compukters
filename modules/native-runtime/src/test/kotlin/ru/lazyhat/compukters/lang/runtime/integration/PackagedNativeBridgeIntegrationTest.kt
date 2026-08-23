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

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntimeLoadResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs

class PackagedNativeBridgeIntegrationTest {
    @Test
    fun `packaged JNI resource loads and executes terminal fixture`() =
        runBlocking {
            assertIs<VmRuntimeLoadResult.Loaded>(VmRuntime.ensureLoaded())
            TerminalFixtureProgram.run(Path.of(requiredProperty("compukter.vm.terminalFixture")))
        }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }
}
