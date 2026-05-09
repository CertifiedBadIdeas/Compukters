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
        val signal = NativeVmSignal.decode(byteArrayOf(6, 7, 0, 0, 0, 42, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(NativeVmSignal.WaitPoll(channel = 7, wakeSequence = 42), signal)
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
    fun waitPollParksOnNativeKernelWakeWithoutResumingImage() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val bindings =
            RecordingBindings(
                signals =
                    ArrayDeque(
                        listOf(
                            byteArrayOf(6, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0),
                            byteArrayOf(0, 0),
                        ),
                    ),
                waitForDeviceWakeResult = 5,
            )
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

        assertEquals(listOf(WaitCall(77L, 4L, 50L)), bindings.waitForDeviceWakeCalls)
        assertEquals(0, runtime.delegate.yieldCalls)
        assertEquals(emptyList(), bindings.resumes)
    }

    @Test
    fun waitPollYieldsAfterNativeKernelWaitTimeout() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val bindings =
            RecordingBindings(
                signals =
                    ArrayDeque(
                        listOf(
                            byteArrayOf(6, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0),
                            byteArrayOf(0, 0),
                        ),
                    ),
                waitForDeviceWakeResult = 4,
            )
        val delegate = RecordingRuntime(metrics = NoopDeviceRuntimeMetrics)
        val runtime =
            KernelAwareRuntime(
                delegate,
                kernelHandle = 77L,
                nativeWorkingDirectory = "bin",
            )
        val runner = NativeImageVmRunner(libraryPath = "/unused/libckl_vm.so", bindings = bindings)

        runBlocking {
            runner.run(image, runtime)
        }

        assertEquals(listOf(WaitCall(77L, 4L, 50L)), bindings.waitForDeviceWakeCalls)
        assertEquals(1, delegate.yieldCalls)
        assertEquals(emptyList(), bindings.resumes)
    }

    @Test
    fun decodesNativeWaitProcessSignal() {
        val signal = NativeVmSignal.decode(byteArrayOf(7, 11, 0, 0, 0, 99, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(NativeVmSignal.WaitProcess(pid = 11, wakeSequence = 99), signal)
    }

    private class KernelAwareRuntime(
        val delegate: RecordingRuntime,
        kernelHandle: Long,
        override val nativeWorkingDirectory: String,
    ) : DeviceRuntime by delegate, NativeDeviceKernelProvider {
        override val nativeDeviceKernelHandle: Long = kernelHandle
    }

    private data class WaitCall(
        val handle: Long,
        val observedWakeSequence: Long,
        val timeoutMillis: Long,
    )

    private data class WaitProcessCall(
        val handle: Long,
        val pid: Int,
        val observedWakeSequence: Long,
        val timeoutMillis: Long,
    )

    private data class ProcessRegistration(
        val kernelHandle: Long,
        val pid: Int,
        val parentPid: Int,
        val programPath: String,
    )

    private data class ProcessCompletion(
        val kernelHandle: Long,
        val pid: Int,
        val exitCode: Int,
    )

    private class RecordingBindings : NativeVmBindingsFacade {
        constructor(
            signals: ArrayDeque<ByteArray> = ArrayDeque(listOf(byteArrayOf(0, 0))),
            waitForDeviceWakeResult: Long = 0,
            waitForProcessWakeResult: Long = 0,
        ) {
            this.signals = signals
            this.waitForDeviceWakeResult = waitForDeviceWakeResult
            this.waitForProcessWakeResult = waitForProcessWakeResult
        }

        private val signals: ArrayDeque<ByteArray>
        private val waitForDeviceWakeResult: Long
        private val waitForProcessWakeResult: Long
        val attachments = mutableListOf<Pair<Long, Long>>()
        val workingDirectories = mutableListOf<Pair<Long, String>>()
        val resumes = mutableListOf<Pair<Long, ByteArray>>()
        val waitForDeviceWakeCalls = mutableListOf<WaitCall>()
        val waitForProcessWakeCalls = mutableListOf<WaitProcessCall>()
        val registerProcessCalls = mutableListOf<ProcessRegistration>()
        val completeProcessCalls = mutableListOf<ProcessCompletion>()

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

        override fun waitForDeviceWake(
            handle: Long,
            observedWakeSequence: Long,
            timeoutMillis: Long,
        ): Long {
            waitForDeviceWakeCalls += WaitCall(handle, observedWakeSequence, timeoutMillis)
            return waitForDeviceWakeResult
        }

        override fun waitForProcessWake(
            handle: Long,
            pid: Int,
            observedWakeSequence: Long,
            timeoutMillis: Long,
        ): Long {
            waitForProcessWakeCalls += WaitProcessCall(handle, pid, observedWakeSequence, timeoutMillis)
            return waitForProcessWakeResult
        }

        override fun registerProcess(
            kernelHandle: Long,
            pid: Int,
            parentPid: Int,
            programPath: String,
        ) {
            registerProcessCalls += ProcessRegistration(kernelHandle, pid, parentPid, programPath)
        }

        override fun completeProcess(
            kernelHandle: Long,
            pid: Int,
            exitCode: Int,
        ) {
            completeProcessCalls += ProcessCompletion(kernelHandle, pid, exitCode)
        }
    }
}
