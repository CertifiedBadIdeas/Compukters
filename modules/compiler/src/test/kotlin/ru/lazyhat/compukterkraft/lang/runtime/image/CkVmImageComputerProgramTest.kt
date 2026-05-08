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

package ru.lazyhat.compukterkraft.lang.runtime.image

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageRuntimeRunner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CkVmImageComputerProgramTest {
    @Test
    fun imageProgramUsesInjectedRunnerFactory() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        var invoked = false
        val program =
            CkVmImageComputerProgram(
                image = image,
                runnerFactory = {
                    object : NativeImageRuntimeRunner {
                        override suspend fun run(
                            image: CkVmImage,
                            runtime: DeviceRuntime,
                        ) {
                            invoked = true
                        }
                    }
                },
            )

        runBlocking { program.run(RecordingRuntime()) }

        assertTrue(invoked)
    }
}
