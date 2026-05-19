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
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmFunction
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmInstruction
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeImageVmBindingsJniTest {
    private fun runDaemonSlice(
        handle: Long,
        serverTick: Long,
        wallNanos: Long = 1_000_000,
    ): NativeDeviceDaemonTickSummary {
        NativeVmBindings.refillDeviceDaemonQuota(handle, wallNanos, serverTick)
        return NativeVmBindings.runDeviceDaemonReady(handle, maxTurns = 128)
    }

    @Test
    fun nativeDeviceDaemonMethodsExposeCompactAbi() {
        assertEquals(
            Long::class.javaPrimitiveType,
            NativeVmBindings::class.java
                .getDeclaredMethod(
                    "createDeviceDaemonNative",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).returnType,
        )
        assertEquals(
            Void.TYPE,
            NativeVmBindings::class.java
                .getDeclaredMethod(
                    "refillDeviceDaemonQuotaNative",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                ).returnType,
        )
        assertEquals(
            LongArray::class.java,
            NativeVmBindings::class.java
                .getDeclaredMethod(
                    "runDeviceDaemonReadyNative",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                ).returnType,
        )
    }

    @Test
    fun nativeDeviceDaemonDoesNotExposeTickShapedApi() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertFalse("tickDeviceDaemon" in memberNames)
        assertFalse("tickDeviceDaemonNative" in memberNames)
    }

    @Test
    fun nativeDeviceDaemonDisplayBindingsExposeLifecycleAndFrameDrain() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("attachDeviceDaemonDisplay" in memberNames)
        assertTrue("detachDeviceDaemonDisplay" in memberNames)
        assertTrue("drainDeviceDaemonDisplayFrames" in memberNames)
        assertTrue("attachDeviceDaemonFilesystem" in memberNames)
        assertTrue("completeDeviceDaemonCompileProgram" in memberNames)
    }

    @Test
    fun nativeDeviceDaemonDisplayBindingsExposeWakeWait() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("deviceDaemonDisplayWakeSequence" in memberNames)
        assertTrue("waitForDeviceDaemonDisplayWake" in memberNames)
    }

    @Test
    fun nativeLowImageVmBindingsExposeCompactAbi() {
        val memberNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("createLowImageNative" in memberNames)
        assertTrue("runLowImageUntilSignalNative" in memberNames)
        assertTrue("lowImageMetricsNative" in memberNames)
        assertTrue("freeLowImageNative" in memberNames)
    }

    @Test
    fun nativeDeviceDaemonCreateRefillRunReadyFreeRunsWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.refillDeviceDaemonQuota(handle, 1_000_000, 5)
            val summary = NativeVmBindings.runDeviceDaemonReady(handle, maxTurns = 128)
            assertEquals(
                NativeDeviceDaemonTickSummary(
                    serverTick = 5,
                    turns = 0,
                    remainingWallNanos = summary.remainingWallNanos,
                    idle = true,
                    halted = 0,
                    hostRequests = 0,
                ),
                summary,
            )
            assertTrue(summary.remainingWallNanos > 0)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonCanRefillQuotaAndRunReadyProcessesSeparately() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { yield(); }").image)
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/boot.ck", "", "")

            NativeVmBindings.refillDeviceDaemonQuota(handle, wallNanos = 1_000_000, serverTick = 91)
            val first = NativeVmBindings.runDeviceDaemonReady(handle, maxTurns = 8)

            assertEquals(91, first.serverTick)
            assertEquals(2, first.turns)
            assertEquals(1, first.halted)
            assertTrue(first.idle)
            assertTrue(first.remainingWallNanos >= 0)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonBootImageRunsWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            assertEquals(
                NativeDeviceDaemonBootSummary(pid = 1, imageAttached = true),
                NativeVmBindings.bootDeviceDaemon(
                    daemonHandle = handle,
                    image = CkVmImageAbi.encode(image),
                    programPath = "/rom/bios.ck",
                    argument = "",
                    workingDirectory = "",
                ),
            )
            assertEquals(1, runDaemonSlice(handle, serverTick = 1).halted)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonDisplayFramesDrainWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val displayId: Int = display::primary();
                            display::fillRect(displayId, 0, 0, 2, 2, 2016);
                            display::present(displayId);
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.attachDeviceDaemonDisplay(handle, displayId = 6, width = 18, height = 18)
            val initial = NativeVmBindings.drainDeviceDaemonDisplayFrames(handle)
            assertTrue(initial.isNotEmpty(), "daemon attach should queue a full refresh frame")

            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/display.ck", "", "")
            val tick = runDaemonSlice(handle, serverTick = 1)
            assertEquals(1, tick.halted)

            val dirty = NativeVmBindings.drainDeviceDaemonDisplayFrames(handle)
            assertTrue(dirty.isNotEmpty(), "daemon present should queue a dirty frame")
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonDisplayWaitReturnsAfterPresentWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val displayId: Int = display::primary();
                            display::fillRect(displayId, 0, 0, 2, 2, 2016);
                            display::present(displayId);
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.attachDeviceDaemonDisplay(handle, displayId = 6, width = 18, height = 18)
            NativeVmBindings.drainDeviceDaemonDisplayFrames(handle)
            val observed = NativeVmBindings.deviceDaemonDisplayWakeSequence(handle)

            val waiter =
                java.util.concurrent.CompletableFuture.supplyAsync {
                    NativeVmBindings.waitForDeviceDaemonDisplayWake(handle, observed, timeoutMillis = 500)
                }

            Thread.sleep(25)
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/display.ck", "", "")
            assertEquals(1, runDaemonSlice(handle, serverTick = 1).halted)

            assertTrue(waiter.get(1, java.util.concurrent.TimeUnit.SECONDS) > observed)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonHostRequestsRoundTripWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/host.ck", "", "")
            val first = runDaemonSlice(handle, serverTick = 1)
            val requests = NativeVmBindings.drainDeviceDaemonHostRequests(handle)
            assertEquals(1, first.hostRequests)
            assertEquals("system", requests.single().moduleName)
            assertEquals("log", requests.single().functionName)

            NativeVmBindings.completeDeviceDaemonHostRequest(
                handle,
                requests.single().requestId,
                VmValue.UnitValue.toNativeBytes("system", "log"),
            )
            assertEquals(1, runDaemonSlice(handle, serverTick = 2).halted)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonProcessArgumentStaysInDaemonWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage("main.ck", "pub fun main() { system::log(process::argument()); }")
                    .image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/argument.ck", "stdio-v1 1 2 3", "")

            val first = runDaemonSlice(handle, serverTick = 1)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()

            assertEquals(1, first.hostRequests)
            assertEquals("system", request.moduleName)
            assertEquals("log", request.functionName)
            assertEquals(listOf(VmValue.StringValue("stdio-v1 1 2 3")), request.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonCurrentDirectoryStaysInDaemonWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage("main.ck", "pub fun main() { system::log(process::currentDirectory()); }")
                    .image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/cwd.ck", "", "/rom")

            val first = runDaemonSlice(handle, serverTick = 1)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()

            assertEquals(1, first.hostRequests)
            assertEquals("system", request.moduleName)
            assertEquals("log", request.functionName)
            assertEquals(listOf(VmValue.StringValue("rom")), request.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonChangeDirectoryStaysInDaemonWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val root = createTempDirectory("ck-daemon-fs")
        root.resolve("rom").resolve("bin").createDirectories()
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val changed: Bool = process::changeDirectory("bin");
                            system::log("" + changed + ":" + process::currentDirectory());
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.attachDeviceDaemonFilesystem(
                handle,
                root.toAbsolutePath().normalize().toString(),
                quotaBytes = 1_048_576,
            )
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/cd.ck", "", "rom")

            val first = runDaemonSlice(handle, serverTick = 1)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()

            assertEquals(1, first.hostRequests)
            assertEquals("system", request.moduleName)
            assertEquals("log", request.functionName)
            assertEquals(listOf(VmValue.StringValue("true:rom/bin")), request.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonProcessSpawnRequestsCompilationWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val parent =
            assertNotNull(
                LanguageFrontend()
                    .compileImage("main.ck", "pub fun main() { process::spawn(\"child.ck\", \"arg\"); }")
                    .image,
            )
        val child = assertNotNull(LanguageFrontend().compileImage("child.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(parent), "/rom/parent.ck", "", "rom")

            val first = runDaemonSlice(handle, serverTick = 1)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()

            assertEquals(1, first.hostRequests)
            assertEquals("compileProgram", request.kind)
            assertEquals("process", request.moduleName)
            assertEquals("spawn", request.functionName)
            assertEquals("child.ck", request.path)
            assertEquals("rom", request.workingDirectory)
            assertEquals(listOf(VmValue.IntValue(2), VmValue.StringValue("arg")), request.arguments)

            assertTrue(
                NativeVmBindings.completeDeviceDaemonCompileProgram(
                    handle,
                    request.requestId,
                    CkVmImageAbi.encode(child),
                    exitCode = 0,
                ),
            )
            assertEquals(2, runDaemonSlice(handle, serverTick = 2).halted)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonProcessRunResumesParentWithExitCodeWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val parent =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        "pub fun main() { system::log(\"exit=\" + process::run(\"child.ck\", \"arg\")); }",
                    ).image,
            )
        val child = assertNotNull(LanguageFrontend().compileImage("child.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(parent), "/rom/parent.ck", "", "rom")

            runDaemonSlice(handle, serverTick = 1)
            val compileRequest = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()
            assertEquals("compileProgram", compileRequest.kind)
            assertEquals("run", compileRequest.functionName)
            assertTrue(
                NativeVmBindings.completeDeviceDaemonCompileProgram(
                    handle,
                    compileRequest.requestId,
                    CkVmImageAbi.encode(child),
                    exitCode = 0,
                ),
            )

            val parentResumed = runDaemonSlice(handle, serverTick = 2)
            val logRequest = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()

            assertEquals(1, parentResumed.hostRequests)
            assertEquals(1, parentResumed.halted)
            assertEquals("system", logRequest.moduleName)
            assertEquals("log", logRequest.functionName)
            assertEquals(listOf(VmValue.StringValue("exit=0")), logRequest.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonIpcReadWaitsForChildWriteWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val parent =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "parent.ck",
                        """
                        pub fun main() {
                            val channel: Int = ipc::open();
                            process::spawn("child.ck", "" + channel);
                            system::log(ipc::read(channel));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val child =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "child.ck",
                        """
                        pub fun main() {
                            ipc::write(strings::toInt(process::argument()), "ready\n");
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(parent), "/rom/parent.ck", "", "rom")
            var logRequest: NativeDeviceDaemonHostRequest? = null

            repeat(10) {
                runDaemonSlice(handle, serverTick = it.toLong() + 1)
                for (request in NativeVmBindings.drainDeviceDaemonHostRequests(handle)) {
                    when {
                        request.kind == "compileProgram" -> {
                            assertEquals("child.ck", request.path)
                            assertTrue(
                                NativeVmBindings.completeDeviceDaemonCompileProgram(
                                    handle,
                                    request.requestId,
                                    CkVmImageAbi.encode(child),
                                    exitCode = 0,
                                ),
                            )
                        }
                        request.moduleName == "system" && request.functionName == "log" -> {
                            logRequest = request
                        }
                        else -> {
                            assertTrue(
                                false,
                                "unexpected daemon host request while validating ipc.read: $request",
                            )
                        }
                    }
                }
            }

            val observed = assertNotNull(logRequest)
            assertEquals(listOf(VmValue.StringValue("ready\n")), observed.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonEventsWakeImagesWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val event: Event = events::tryPull("char");
                            system::log(event.name + ":" + events::argString(event, 0));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/events.ck", "", "")
            assertTrue(
                NativeVmBindings.enqueueDeviceDaemonEvent(
                    handle,
                    "char",
                    VmValue
                        .RecordValue(
                            typeName = "EventPayload",
                            fields = linkedMapOf("arg0" to VmValue.StringValue("x")),
                        ).toNativeBytes("events", "enqueue"),
                ),
            )

            val woke = runDaemonSlice(handle, serverTick = 1)
            assertEquals(1, woke.hostRequests)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()
            assertEquals("system", request.moduleName)
            assertEquals("log", request.functionName)
            assertEquals(listOf(VmValue.StringValue("char:x")), request.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun nativeDeviceDaemonPollWakesOnEventsWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val channel: Int = ipc::open();
                            val result: Poll = runtime::poll(channel);
                            system::log(result.kind + ":" + result.event.name + ":" + events::argString(result.event, 0));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 1_000_000)
        try {
            NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/poll-events.ck", "", "")
            val waiting = runDaemonSlice(handle, serverTick = 1)
            assertEquals(0, waiting.hostRequests)

            assertTrue(NativeVmBindings.enqueueDeviceDaemonEvent(handle, "char", listOf("x")))

            val woke = runDaemonSlice(handle, serverTick = 2)
            assertEquals(1, woke.hostRequests)
            val request = NativeVmBindings.drainDeviceDaemonHostRequests(handle).single()
            assertEquals("system", request.moduleName)
            assertEquals("log", request.functionName)
            assertEquals(listOf(VmValue.StringValue("event:char:x")), request.arguments)
        } finally {
            NativeVmBindings.freeDeviceDaemon(handle)
        }
    }

    @Test
    fun imageRunnerHaltsForEmptyMainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), sliceBudgetNanos = 1_000_000)

        try {
            val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))
            val halt = assertIs<NativeVmSignal.Halt>(signal)

            assertEquals(NativeVmValue.UnitValue, halt.value)
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    @Test
    fun lowImageRunnerHaltsWithI32WhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            RuxLowVmImage(
                memorySize = 1024u,
                entryFunctionIndex = 0,
                functions =
                    listOf(
                        RuxLowVmFunction(
                            name = "main",
                            registerCount = 3,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    RuxLowVmInstruction.I32Const(dst = 0, value = 40),
                                    RuxLowVmInstruction.I32Const(dst = 1, value = 2),
                                    RuxLowVmInstruction.I32Add(dst = 2, lhs = 0, rhs = 1),
                                    RuxLowVmInstruction.ReturnI32(2),
                                ),
                        ),
                    ),
            )
        val handle =
            NativeVmBindings.createLowImage(
                libraryPath = libraryPath,
                image = RuxLowVmImageAbi.encode(image),
                sliceBudgetNanos = 1_000_000,
            )

        try {
            assertEquals(NativeLowImageVmSignal.HaltI32(42), NativeVmBindings.runLowImageUntilSignal(handle))

            val metrics = NativeVmBindings.lowImageMetrics(handle)
            assertEquals(1, metrics.runInvocations)
            assertTrue(metrics.elapsedNanos > 0, metrics.toString())
            assertEquals(0, metrics.pauseSignals)
        } finally {
            NativeVmBindings.freeLowImage(handle)
        }
    }

    @Test
    fun imageRunnerExposesExecutionMetricsWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        fun add(a: Int, b: Int): Int {
                            return a + b;
                        }

                        pub fun main(): Int {
                            return add(2, 5);
                        }
                        """.trimIndent(),
                    ).image,
            )
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), sliceBudgetNanos = 1_000_000)

        try {
            val halt = assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle)))
            assertEquals(NativeVmValue.IntValue(7), halt.value)

            val metrics = NativeVmBindings.imageMetrics(handle)
            assertTrue(metrics.executedInstructions > 0, metrics.toString())
            assertEquals(1, metrics.opcodeCount(11))
            assertEquals(0, metrics.valueClones, metrics.toString())
            assertTrue(metrics.registerReads > 0, metrics.toString())
            assertTrue(metrics.registerWrites > 0, metrics.toString())
            assertTrue(metrics.functionCalls > 0, metrics.toString())
            assertTrue(metrics.functionReturns > 0, metrics.toString())
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    @Test
    fun imageRunnerEmitsHostCallAndResumesWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), sliceBudgetNanos = 1_000_000)

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

    @Test
    fun ruxComputerBootsLowImageAndExposesControlAndDebugWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            RuxLowVmImage(
                memorySize = 64u * 1024u,
                entryFunctionIndex = 0,
                functions =
                    listOf(
                        RuxLowVmFunction(
                            name = "main",
                            registerCount = 4,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0000u),
                                    RuxLowVmInstruction.I32Const(dst = 1, value = 2),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                    RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0100u),
                                    RuxLowVmInstruction.I32Const(dst = 1, value = 'R'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                    RuxLowVmInstruction.I32Const(dst = 1, value = 'U'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                    RuxLowVmInstruction.I32Const(dst = 1, value = 'X'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                    RuxLowVmInstruction.I32Const(dst = 2, value = 0),
                                    RuxLowVmInstruction.ReturnI32(2),
                                ),
                        ),
                    ),
            )
        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = RuxLowVmImageAbi.encode(image),
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000_000,
            )

        try {
            assertEquals(NativeLowImageVmSignal.HaltI32(0), NativeVmBindings.runRuxComputerUntilSignal(handle))
            assertEquals("RUX", NativeVmBindings.ruxComputerDebugOutput(handle).decodeToString())
            assertEquals(
                NativeRuxComputerControl(status = 3, exitCode = 0, panicCode = 0),
                NativeVmBindings.ruxComputerControl(handle),
            )
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }

    @Test
    fun ruxComputerSerialInputEchoesThroughDrainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = ruxSerialEchoPollingImage()
        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = RuxLowVmImageAbi.encode(image),
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000,
            )

        try {
            NativeVmBindings.runRuxComputerUntilSignal(handle)
            assertEquals("", NativeVmBindings.drainRuxComputerDebugOutput(handle).decodeToString())

            NativeVmBindings.pushRuxComputerSerialInput(handle, "Rux!".encodeToByteArray())

            val output = StringBuilder()
            repeat(16) {
                NativeVmBindings.runRuxComputerUntilSignal(handle)
                output.append(NativeVmBindings.drainRuxComputerDebugOutput(handle).decodeToString())
                if (output.toString() == "Rux!") {
                    return@repeat
                }
            }

            assertEquals("Rux!", output.toString())
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }

    @Test
    fun ruxComputerDisplaySnapshotExposesTextCellsWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            RuxLowVmImage(
                memorySize = 64u * 1024u,
                entryFunctionIndex = 0,
                functions =
                    listOf(
                        RuxLowVmFunction(
                            name = "main",
                            registerCount = 4,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0314u),
                                    RuxLowVmInstruction.AddrConst(dst = 1, value = 0x1000_0310u),
                                    RuxLowVmInstruction.I32Const(dst = 2, value = 'R'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 2),
                                    RuxLowVmInstruction.I32Const(dst = 3, value = 2),
                                    RuxLowVmInstruction.Store32(addr = 1, src = 3),
                                    RuxLowVmInstruction.I32Const(dst = 2, value = 'U'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 2),
                                    RuxLowVmInstruction.Store32(addr = 1, src = 3),
                                    RuxLowVmInstruction.I32Const(dst = 2, value = 'X'.code),
                                    RuxLowVmInstruction.Store32(addr = 0, src = 2),
                                    RuxLowVmInstruction.Store32(addr = 1, src = 3),
                                    RuxLowVmInstruction.I32Const(dst = 2, value = 0),
                                    RuxLowVmInstruction.ReturnI32(2),
                                ),
                        ),
                    ),
            )
        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = RuxLowVmImageAbi.encode(image),
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000_000,
            )

        try {
            assertEquals(NativeLowImageVmSignal.HaltI32(0), NativeVmBindings.runRuxComputerUntilSignal(handle))
            val snapshot = NativeVmBindings.ruxComputerDisplay0Snapshot(handle)
            requireNotNull(snapshot)
            assertEquals(80, snapshot.columns)
            assertEquals(25, snapshot.rows)
            assertEquals(3, snapshot.cursorX)
            assertEquals(0, snapshot.cursorY)
            assertEquals(3, snapshot.sequence)
            assertEquals("RUX", snapshot.cells.copyOfRange(0, 3).decodeToString())
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }

    private fun ruxSerialEchoPollingImage(): RuxLowVmImage =
        RuxLowVmImage(
            memorySize = 64u * 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 5,
                        parameters = emptyList(),
                        instructions =
                            listOf(
                                RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0200u),
                                RuxLowVmInstruction.AddrConst(dst = 1, value = 0x1000_0204u),
                                RuxLowVmInstruction.AddrConst(dst = 2, value = 0x1000_0100u),
                                RuxLowVmInstruction.Load32(dst = 3, addr = 0),
                                RuxLowVmInstruction.JumpIfFalse(cond = 3, target = 3),
                                RuxLowVmInstruction.Load32(dst = 4, addr = 1),
                                RuxLowVmInstruction.Store32(addr = 2, src = 4),
                                RuxLowVmInstruction.Jump(target = 3),
                            ),
                    ),
                ),
        )
}
