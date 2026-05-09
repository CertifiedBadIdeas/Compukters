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
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.NativeDeviceKernelProvider
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceRuntimeMetrics
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeImageVmRunnerTest {
    @Test
    fun decodesNativeWaitPollSignal() {
        val signal = NativeVmSignal.decode(byteArrayOf(6, 7, 0, 0, 0))

        assertEquals(NativeVmSignal.WaitPoll(channel = 7), signal)
    }

    @Test
    fun attachesImageToNativeKernelWhenRuntimeProvidesKernelHandle() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val bindings = RecordingBindings()
        val runtime =
            KernelAwareRuntime(
                RecordingRuntime(metrics = NoopDeviceRuntimeMetrics),
                kernelHandle = 77L,
                nativeWorkingDirectory = "bin",
        )
        val runner = NativeImageVmRunner(libraryPath = "/unused/libckl_vm.so", bindings = bindings)

        runBlocking {
            runner.run(image, runtime)
        }

        assertEquals(listOf(11L to 77L), bindings.attachments)
        assertEquals(listOf(11L to "bin"), bindings.workingDirectories)
    }

    @Test
    fun waitPollYieldsWithoutResumingImage() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val bindings =
            RecordingBindings(
                signals =
                    ArrayDeque(
                        listOf(
                            byteArrayOf(6, 3, 0, 0, 0),
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

    private class KernelAwareRuntime(
        private val delegate: RecordingRuntime,
        kernelHandle: Long,
        override val nativeWorkingDirectory: String,
    ) : DeviceRuntime by delegate, NativeDeviceKernelProvider {
        override val nativeDeviceKernelHandle: Long = kernelHandle
    }

    private class RecordingBindings : NativeVmBindingsFacade {
        constructor(signals: ArrayDeque<ByteArray> = ArrayDeque(listOf(byteArrayOf(0, 0)))) {
            this.signals = signals
        }

        private val signals: ArrayDeque<ByteArray>
        val attachments = mutableListOf<Pair<Long, Long>>()
        val workingDirectories = mutableListOf<Pair<Long, String>>()
        val resumes = mutableListOf<Pair<Long, ByteArray>>()

        override fun createImage(
            libraryPath: String,
            image: ByteArray,
            instructionBudget: Int,
        ): Long = 11L

        override fun runImageUntilSignal(handle: Long): ByteArray = signals.removeFirst()

        override fun resumeImageWith(
            handle: Long,
            value: ByteArray,
        ) {
            resumes += handle to value
        }

        override fun freeImage(handle: Long) = Unit

        override fun attachImageToKernel(
            imageHandle: Long,
            kernelHandle: Long,
        ) {
            attachments += imageHandle to kernelHandle
        }

        override fun setImageWorkingDirectory(
            imageHandle: Long,
            workingDirectory: String,
        ) {
            workingDirectories += imageHandle to workingDirectory
        }
    }
}
