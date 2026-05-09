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
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeImageVmBindingsJniTest {
    @Test
    fun nativeKernelBindingsExposeDeviceKernelLifecycle() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue(
            "createDeviceKernel" in memberNames,
            "NativeVmBindings must expose native device-kernel lifecycle",
        )
        assertTrue(
            "freeDeviceKernel" in memberNames,
            "NativeVmBindings must expose native device-kernel lifecycle",
        )
        assertTrue(
            "enqueueDeviceEvent" in memberNames,
            "NativeVmBindings must expose native device-kernel lifecycle",
        )
        assertTrue(
            "writeDeviceIpc" in memberNames,
            "NativeVmBindings must expose native IPC writes for Kotlin-owned process code",
        )
        assertTrue(
            "deviceKernelWakeSequence" in memberNames,
            "NativeVmBindings must expose native device kernel wake sequence",
        )
        assertTrue(
            "waitForDeviceWake" in memberNames,
            "NativeVmBindings must expose native device kernel waits",
        )
        assertTrue(
            "attachImageToKernel" in memberNames,
            "NativeVmBindings must expose native device-kernel lifecycle",
        )
        assertTrue(
            "attachNativeFilesystem" in memberNames,
            "NativeVmBindings must expose native filesystem attachment",
        )
        assertTrue(
            "setImageWorkingDirectory" in memberNames,
            "NativeVmBindings must expose per-image working directory attachment",
        )
    }

    @Test
    fun nativeDeviceWakeWaitsForEventWhenLibraryIsConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)

        try {
            val initial = NativeVmBindings.deviceKernelWakeSequence(kernelHandle)
            assertEquals(initial, NativeVmBindings.waitForDeviceWake(kernelHandle, initial, timeoutMillis = 1))
            assertTrue(NativeVmBindings.enqueueDeviceEvent(kernelHandle, "key", listOf("x")))
            assertTrue(NativeVmBindings.waitForDeviceWake(kernelHandle, initial, timeoutMillis = 100) > initial)
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun jniCreatesAndFreesDeviceKernelWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val imageHandle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 128)

        try {
            val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
            assertTrue(kernelHandle != 0L)
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        } finally {
            NativeVmBindings.freeImage(imageHandle)
        }
    }

    @Test
    fun nativeDisplayBindingsExposeLifecycleAndFrameDrain() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("attachNativeDisplay" in memberNames)
        assertTrue("detachNativeDisplay" in memberNames)
        assertTrue("nativeDisplayFillRect" in memberNames)
        assertTrue("nativeDisplayPresent" in memberNames)
        assertTrue("drainNativeDisplayFrames" in memberNames)
    }

    @Test
    fun nativeDisplayAttachPresentAndDrainWhenLibraryIsConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)

        try {
            NativeVmBindings.attachNativeDisplay(kernelHandle, displayId = 3, width = 18, height = 18)
            val initial = NativeVmBindings.drainNativeDisplayFrames(kernelHandle)
            assertTrue(initial.isNotEmpty(), "attach should queue a full refresh frame")

            NativeVmBindings.nativeDisplayFillRect(
                kernelHandle,
                displayId = 3,
                x = 0,
                y = 0,
                width = 2,
                height = 2,
                rgb565 = 0x07E0,
            )
            NativeVmBindings.nativeDisplayPresent(kernelHandle, displayId = 3)
            val dirty = NativeVmBindings.drainNativeDisplayFrames(kernelHandle)

            assertTrue(dirty.isNotEmpty(), "present should queue a dirty frame")
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun imageRunnerHandlesFilesystemInRustWhenKernelFilesystemIsAttached() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val workspaceRoot = createTempDirectory("ckl-native-fs")
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main(): String {
                            filesystem::makeDir("dir");
                            filesystem::writeText("dir/note.txt", "hello");
                            val body: String = filesystem::readText("dir/note.txt");
                            val listed: String = filesystem::list("dir");
                            val removed: Bool = filesystem::remove("dir/note.txt");
                            if (body == "hello" && listed == "note.txt" && removed && !filesystem::exists("dir/note.txt")) {
                                return "ok";
                            }
                            return body + "|" + listed;
                        }
                        """.trimIndent(),
                    ).image,
            )
        val kernelHandle =
            NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
        val imageHandle =
            NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

        try {
            NativeVmBindings.attachNativeFilesystem(kernelHandle, workspaceRoot.toString(), 1024L * 1024L)
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

            val halt =
                assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(NativeVmValue.StringValue("ok"), halt.value)
            assertTrue(!workspaceRoot.resolve("dir").resolve("note.txt").exists())
        } finally {
            NativeVmBindings.freeImage(imageHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeFilesystemEnforcesWorkspaceRootWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val workspaceRoot = createTempDirectory("ckl-native-fs-root")
        val outside = createTempDirectory("ckl-native-fs-outside")
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main(): String {
                            filesystem::writeText("../escape.txt", "nope");
                            return filesystem::readText("escape.txt");
                        }
                        """.trimIndent(),
                    ).image,
            )
        val kernelHandle =
            NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
        val imageHandle =
            NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

        try {
            NativeVmBindings.attachNativeFilesystem(kernelHandle, workspaceRoot.toString(), 1024L * 1024L)
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

            val halt =
                assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(NativeVmValue.StringValue("nope"), halt.value)
            assertTrue(!outside.resolve("escape.txt").exists())
            assertTrue(workspaceRoot.resolve("escape.txt").exists())
        } finally {
            NativeVmBindings.freeImage(imageHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeFilesystemUsesImageWorkingDirectoryWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val workspaceRoot = createTempDirectory("ckl-native-fs-cwd")
        workspaceRoot.resolve("sub").createDirectories()
        workspaceRoot.resolve("sub").resolve("note.txt").writeText("cwd")
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main(): String {
                            return filesystem::readText("note.txt");
                        }
                        """.trimIndent(),
                    ).image,
            )
        val kernelHandle =
            NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
        val imageHandle =
            NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

        try {
            NativeVmBindings.attachNativeFilesystem(kernelHandle, workspaceRoot.toString(), 1024L * 1024L)
            NativeVmBindings.setImageWorkingDirectory(imageHandle, "sub")
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

            val halt =
                assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(NativeVmValue.StringValue("cwd"), halt.value)
        } finally {
            NativeVmBindings.freeImage(imageHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeEventsCarryArgumentsWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main(): String {
                            val event: Event = events::tryPull();
                            return event.name + ":" + events::argString(event, 0);
                        }
                        """.trimIndent(),
                    ).image,
            )
        val kernelHandle =
            NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
        val imageHandle =
            NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

        try {
            NativeVmBindings.enqueueDeviceEvent(
                kernelHandle,
                "char",
                VmValue
                    .RecordValue(
                        typeName = "EventPayload",
                        fields = linkedMapOf("arg0" to VmValue.StringValue("x")),
                    ).toNativeBytes("events", "enqueue"),
            )
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

            val halt =
                assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(NativeVmValue.StringValue("char:x"), halt.value)
        } finally {
            NativeVmBindings.freeImage(imageHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeIpcWriteWakesWaitPollWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main(): String {
                            val channel: Int = ipc::open();
                            val result: Poll = runtime::poll(channel);
                            return result.kind + ":" + result.text;
                        }
                        """.trimIndent(),
                    ).image,
            )
        val kernelHandle =
            NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
        val imageHandle =
            NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

        try {
            NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

            val wait =
                assertIs<NativeVmSignal.WaitPoll>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(1, wait.channel)
            assertTrue(NativeVmBindings.writeDeviceIpc(kernelHandle, wait.channel, "hello"))

            val halt =
                assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
            assertEquals(NativeVmValue.StringValue("ipc:hello"), halt.value)
        } finally {
            NativeVmBindings.freeImage(imageHandle)
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

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
