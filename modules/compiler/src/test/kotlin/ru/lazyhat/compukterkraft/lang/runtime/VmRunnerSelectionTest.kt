/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmRunner
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VmRunnerSelectionTest {
    @Test
    fun nativeRunnerIsUnavailableWithoutExplicitLibraryPath() {
        withVmProperties(runner = null, libraryPath = null) {
            assertFalse(NativeVmRunner.isAvailable(System.getProperty("ckl.vm.native.library")))
        }
    }

    @Test
    fun defaultSelectionReturnsKotlinRunner() {
        withVmProperties(runner = null, libraryPath = null) {
            assertSame(KotlinVmRunner, VmRunnerFactory.fromSystemProperties())
        }
    }

    @Test
    fun rustSelectionRequiresExplicitLibraryPath() {
        withVmProperties(runner = "rust", libraryPath = null) {
            val error = assertFailsWith<IllegalStateException> {
                VmRunnerFactory.fromSystemProperties()
            }

            assertTrue(requireNotNull(error.message).contains("ckl.vm.native.library"))
        }
    }

    @Test
    fun rustSelectionReturnsNativeRunnerWhenLibraryPathIsConfigured() {
        withVmProperties(runner = "rust", libraryPath = "/tmp/libckl_vm.so") {
            assertTrue(VmRunnerFactory.fromSystemProperties() is NativeVmRunner)
        }
    }

    @Test
    fun bytecodeProgramStillUsesKotlinRunnerByDefault() {
        withVmProperties(runner = null, libraryPath = null) {
            val artifact = LanguageFrontend().compile("default.ck", "pub fun main() { return }")
            val program = BytecodeComputerProgram(requireNotNull(artifact.module))

            assertTrue(program.toString().isNotBlank())
        }
    }

    @Test
    fun bytecodeProgramUsesSystemPropertyRunnerSelectorByDefault() {
        withVmProperties(runner = "rust", libraryPath = null) {
            val artifact = LanguageFrontend().compile("rust.ck", "pub fun main() { return }")
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(RecordingRuntime()) }
            }

            assertTrue(requireNotNull(error.message).contains("ckl.vm.native.library"))
        }
    }

    private fun withVmProperties(
        runner: String?,
        libraryPath: String?,
        block: () -> Unit,
    ) {
        val oldRunner = System.getProperty("ckl.vm.runner")
        val oldLibraryPath = System.getProperty("ckl.vm.native.library")
        try {
            setOrClear("ckl.vm.runner", runner)
            setOrClear("ckl.vm.native.library", libraryPath)
            block()
        } finally {
            setOrClear("ckl.vm.runner", oldRunner)
            setOrClear("ckl.vm.native.library", oldLibraryPath)
        }
    }

    private fun setOrClear(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
    }
}
