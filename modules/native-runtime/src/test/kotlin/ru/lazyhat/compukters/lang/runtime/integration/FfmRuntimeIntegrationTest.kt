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
import java.nio.file.Path
import kotlin.test.Test

class FfmRuntimeIntegrationTest {
    @Test
    fun `terminal artifact runs through Kotlin FFM and Rust VM`() =
        runBlocking {
            VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukter.ffi.library")))
            ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact")))
        }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }
}
