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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NativeImageVmBindingsJniTest {
    @Test
    fun imageRunnerHaltsForEmptyMainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 128)

        try {
            val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))
            val halt = assertIs<NativeVmSignal.Halt>(signal)

            assertEquals(NativeVmValue.UnitValue, halt.value)
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    @Test
    fun imageRunnerEmitsHostCallAndResumesWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 128)

        try {
            val signal = assertIs<NativeVmSignal.HostCall>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle)))
            assertEquals("system", signal.moduleName)
            assertEquals("log", signal.functionName)
            assertEquals(listOf(NativeVmValue.StringValue("hi")), signal.arguments)

            NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("system", "log"))

            val halt = assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle)))
            assertEquals(NativeVmValue.UnitValue, halt.value)
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }
}
