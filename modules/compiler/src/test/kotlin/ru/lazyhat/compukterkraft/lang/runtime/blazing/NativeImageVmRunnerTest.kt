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

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceRuntimeMetrics
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeImageVmRunnerTest {
    @Test
    fun decodesNativeWaitPollSignal() {
        val signal = NativeVmSignal.decode(byteArrayOf(6, 7, 0, 0, 0, 42, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(NativeVmSignal.WaitPoll(channel = 7, wakeSequence = 42), signal)
    }

    @Test
    fun waitPollYieldsWithoutResumingImage() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val bindings =
            RecordingBindings(
                signals =
                    ArrayDeque(
                        listOf(
                            byteArrayOf(6, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                            byteArrayOf(0, 0),
                        ),
                    ),
            )
        val runtime = RecordingRuntime(metrics = NoopDeviceRuntimeMetrics)
        val runner = NativeImageVmRunner(libraryPath = "/unused/libckl_vm.so", bindings = bindings)

        runBlocking {
            runner.run(image, runtime)
        }

        assertEquals(1, runtime.yieldCalls)
        assertEquals(emptyList(), bindings.resumes)
    }

    @Test
    fun decodesNativeWaitProcessSignal() {
        val signal = NativeVmSignal.decode(byteArrayOf(7, 11, 0, 0, 0, 99, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(NativeVmSignal.WaitProcess(pid = 11, wakeSequence = 99), signal)
    }

    private class RecordingBindings : NativeVmBindingsFacade {
        constructor(
            signals: ArrayDeque<ByteArray> = ArrayDeque(listOf(byteArrayOf(0, 0))),
        ) {
            this.signals = signals
        }

        private val signals: ArrayDeque<ByteArray>
        val resumes = mutableListOf<Pair<Long, ByteArray>>()

        override fun createImage(
            libraryPath: String,
            image: ByteArray,
            sliceBudgetNanos: Long,
        ): Long = 11L

        override fun runImageUntilSignal(handle: Long): ByteArray = signals.removeFirst()

        override fun resumeImageWith(
            handle: Long,
            value: ByteArray,
        ) {
            resumes += handle to value
        }

        override fun freeImage(handle: Long) = Unit
    }
}
