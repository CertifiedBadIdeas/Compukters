/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.program.integration

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.runtime.CompilerServiceConfiguration
import ru.lazyhat.compukters.compiler.runtime.ServerCompilerService
import ru.lazyhat.compukters.compiler.runtime.WorkerCompilerBackend
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ServerComputerCompiler
import ru.lazyhat.compukters.core.device.runtime.program.NativeProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmArtifactVerifier
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.platform.bundle.PackagedPlatformBundleLoader
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.worker.payload.PackagedToolingBundle
import ru.lazyhat.compukters.worker.value.Sha256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramRuntimeHostIntegrationTest {
    init {
        installFfmRuntime()
    }

    @Test
    fun `lexical Guest paths execute without filesystem access`() {
        val artifact = Path.of(requiredProperty("compukters.lexicalPathRuntime.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(artifact))
        ProgramRuntimeHost().use { host ->
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            advanceUntil(host) { it is ProgramRuntimeState.Halted }
        }
    }

    @Test
    fun `typed Guest compilation and process paths preserve diagnostics permissions and outputs`() {
        val artifact = Path.of(requiredProperty("compukters.compilationRuntime.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(artifact))
        val root = Files.createTempDirectory("compukters-typed-compilation-").toRealPath()
        try {
            TestCompilerService
                .open(
                    root.resolve("compiler"),
                    Path.of(requiredProperty("compukters.compilerWorker.manifest")),
                    Path.of(requiredProperty("compukters.compilerWorker.payload")),
                ).use { compiler ->
                    ProgramRuntimeHost(sessionFactory = NativeProgramVmSessionFactory(), compilerRouter = compiler.router).use { host ->
                        assertEquals(ProgramStartResult.Started, host.start(artifact))
                        advanceUntil(host) { it is ProgramRuntimeState.Halted }
                        assertEquals("42\n42\ncompiler and process Path API ok\n", terminalText(requireNotNull(host.terminalFullState())))
                    }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `portable Guest filesystem performs bounded atomic IO and typed failures`() {
        val artifact = Path.of(requiredProperty("compukters.filesystemRuntime.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(artifact))
        ProgramRuntimeHost().use { host ->
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            advanceUntil(host) { it is ProgramRuntimeState.Halted }
            assertEquals("filesystem API ok\n", terminalText(requireNotNull(host.terminalFullState())))
        }
    }

    @Test
    fun `headless benchmark preserves unsigned decimal argument validation`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.vmbenchAgentRuntime.artifact")).readBytes()
        for (input in listOf("+2", "-1", " 2", "0", "1000001", "2147483648", "2x", "00002")) {
            ProgramRuntimeHost().use { host ->
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
                assertTrue(host.sendTerminalText(input))
                advanceUntil(host) { it is ProgramRuntimeState.Halted }
                val expected =
                    if (input == "00002") {
                        "vmbench agent: rounds=2\nvmbench agent: checksum=-365826314\n"
                    } else {
                        "vmbench agent requires rounds 1..1000000\n"
                    }
                assertEquals(expected, terminalText(requireNotNull(host.terminalFullState())), input)
            }
        }
    }

    @Test
    fun `headless benchmark accepts rounds through terminal input and halts with deterministic checksum`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.vmbenchAgentRuntime.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(artifact))

        ProgramRuntimeHost().use { host ->
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            assertTrue(host.sendTerminalText("2"))
            advanceUntil(host) { it is ProgramRuntimeState.Halted }
            assertEquals(
                "vmbench agent: rounds=2\nvmbench agent: checksum=-365826314\n",
                terminalText(requireNotNull(host.terminalFullState())),
            )
        }
    }

    @Test
    fun `builtin editor save preserves the cursor and subsequent insertion position`() {
        withEditor { host ->
            submit(host, "first\nA😀BC")
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.RIGHT)
            val cursor = requireNotNull(host.terminalFullState()).cursor
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            assertEquals(cursor, requireNotNull(host.terminalFullState()).cursor, terminalText(requireNotNull(host.terminalFullState())))
            submit(host, "X")
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("A😀XBC"))
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            press(host, TerminalKey.X, setOf(TerminalModifier.CONTROL))
            submit(host, "edit regression.txt")
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("A😀XBC"))
        }
    }

    @Test
    fun `builtin editor rejects expanded paste atomically at buffer capacity`() {
        for ((length, paste) in listOf(4088 to "\tZ", 4088 to "\nZ", 4088 to "😀\t", 4080 to "\n \nZ")) {
            withEditor { host ->
                submit(host, "\t" + "x".repeat(length))
                val cursor = requireNotNull(host.terminalFullState()).cursor
                submit(host, paste)
                val screen = requireNotNull(host.terminalFullState())
                assertEquals(cursor, screen.cursor, paste)
                assertTrue(terminalText(screen).contains("Buffer full"), paste)
                // Rejection must leave all remaining code units available.
                submit(host, "x".repeat(4088 - length) + "ABCD")
                assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("ABCD"))
            }
        }
    }

    @Test
    fun `builtin editor accepts expanded paste exactly filling the buffer`() {
        for ((length, paste) in listOf(4087 to "\tZ", 4087 to "\n", 4079 to "\n \nZ")) {
            withEditor { host ->
                submit(host, "\t" + "x".repeat(length))
                submit(host, paste)
                val screen = requireNotNull(host.terminalFullState())
                assertTrue(!terminalText(screen).contains("Buffer full"))
                val cursor = screen.cursor
                val position = terminalText(screen).lines().first { it.startsWith("Ln ") }
                submit(host, "Q")
                assertEquals(
                    cursor,
                    requireNotNull(host.terminalFullState()).cursor,
                    terminalText(requireNotNull(host.terminalFullState())),
                )
                assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("Buffer full"))
                // Exercise navigation with a zero-sized gap, including overlapping self-copy.
                press(host, TerminalKey.LEFT)
                press(host, TerminalKey.RIGHT)
                assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains(position))
            }
        }
    }

    @Test
    fun `builtin editor saves its full Unicode buffer without moving the cursor`() {
        withEditor { host ->
            val content = "x".repeat(2047) + "😀" + "漢".repeat(2047)
            assertEquals(4096, content.length)
            submit(host, content)
            press(host, TerminalKey.LEFT)
            val cursor = requireNotNull(host.terminalFullState()).cursor
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            val screen = requireNotNull(host.terminalFullState())
            assertEquals(cursor, screen.cursor, terminalText(screen))
            assertTrue(terminalText(screen).contains("Saved"), terminalText(screen))
            val generation = requireNotNull(host.filesystemGeneration())
            val saved = requireNotNull(host.fileRead(VmVirtualPath.of("/home/regression.txt"), 0, 32768, generation))
            assertTrue(saved.eof)
            assertEquals(content, saved.bytes.decodeToString(throwOnInvalidSequence = true))
        }
    }

    @Test
    fun `builtin editor movement and deletion preserve Unicode scalar boundaries`() {
        withEditor { host ->
            submit(host, "A😀BC")
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.BACKSPACE)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("ABC"))
            press(host, TerminalKey.HOME)
            press(host, TerminalKey.RIGHT)
            press(host, TerminalKey.DELETE)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("AC"))
            press(host, TerminalKey.END)
            press(host, TerminalKey.BACKSPACE)
            submit(host, "😀")
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.DELETE)
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            press(host, TerminalKey.X, setOf(TerminalModifier.CONTROL))
            submit(host, "edit regression.txt")
            pressEnter(host)
            val screen = requireNotNull(host.terminalFullState())
            assertEquals(1, screen.cursor.x)
            assertEquals(1, screen.cursor.y)
            assertTrue(terminalText(screen).contains("\nA\n"))
        }
    }

    @Test
    fun `builtin shell manages quoted paths without replacing or recursively deleting entries`() =
        withShell { host ->
            shellCommand(host, "mkdir -p \"notes folder/child\"")
            assertTrue(shellCommand(host, "stat \"notes folder/./child\"").contains("directory:"))
            shellCommand(host, "mkdir destination")
            val collision = shellCommand(host, "mv \"notes folder/child\" destination")
            assertTrue(collision.contains("mv:"), collision)
            assertTrue(shellCommand(host, "stat \"notes folder/child\"").contains("directory:"))
            val nonempty = shellCommand(host, "rm \"notes folder\"")
            assertTrue(nonempty.contains("rm:"), nonempty)
            shellCommand(host, "mv \"notes folder/child\" \"notes folder/renamed\"")
            assertTrue(shellCommand(host, "ls \"notes folder\"").contains("renamed"))
            shellCommand(host, "rm \"notes folder/renamed\"")
            shellCommand(host, "rm \"notes folder\"")
            assertTrue(shellCommand(host, "stat destination").contains("directory:"))
            assertTrue(shellCommand(host, "stat \"notes folder\"").contains("not found"))
        }

    @Test
    fun `builtin shell cat preserves Unicode across output chunks and removes files`() =
        withShell { host ->
            shellCommand(host, "edit \"unicode note.txt\"")
            submit(host, "x".repeat(255) + "😀tail")
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            press(host, TerminalKey.X, setOf(TerminalModifier.CONTROL))
            val contents = shellCommand(host, "cat \"./unicode note.txt\"")
            assertTrue(contents.contains("😀tail"), contents)
            assertTrue(contents.endsWith("tail\n>\n"), contents)
            shellCommand(host, "mv \"unicode note.txt\" moved.txt")
            assertTrue(shellCommand(host, "cat moved.txt").contains("😀tail"))
            shellCommand(host, "rm moved.txt")
            val missing = shellCommand(host, "cat moved.txt")
            assertTrue(missing.contains("cat:"), missing)
            shellCommand(host, "edit empty.txt")
            press(host, TerminalKey.S, setOf(TerminalModifier.CONTROL))
            press(host, TerminalKey.X, setOf(TerminalModifier.CONTROL))
            assertTrue(shellCommand(host, "cat empty.txt").endsWith("cat empty.txt\n>\n"))
            assertTrue(shellCommand(host, "echo alive").contains("alive\n>"))
        }

    @Test
    fun `builtin shell reports usage invalid paths and immutable ROM errors and remains usable`() =
        withShell { host ->
            for (command in listOf("cat", "mkdir -p", "mv one", "rm \"\"")) {
                val output = shellCommand(host, command)
                assertTrue(output.contains("usage:"), output)
            }
            assertTrue(shellCommand(host, "cat /home//bad").contains("invalid path"))
            assertTrue(shellCommand(host, "ls /home//bad").contains("invalid path"))
            assertTrue(shellCommand(host, "/home//bad").contains("invalid path"))
            assertTrue(shellCommand(host, "rm ../rom/shell").contains("rm:"))
            assertTrue(shellCommand(host, "stat /rom/shell").contains("file:"))
            assertTrue(shellCommand(host, "cat /home").contains("is a directory"))
            assertTrue(shellCommand(host, "help mkdir").contains("mkdir [-p] <path>"))
            assertTrue(shellCommand(host, "echo alive").contains("alive\n>"))
        }

    @Test
    fun `builtin shell edits scalar boundaries and restores history drafts`() =
        withShell { host ->
            submit(host, "echo first")
            pressEnter(host)
            submit(host, "echo AB")
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.UP)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo first\n"))
            press(host, TerminalKey.DOWN)
            submit(host, "😀")
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo A😀B\nA😀B\n>\n"))

            submit(host, "echo 😀Z")
            press(host, TerminalKey.HOME)
            repeat(5) { press(host, TerminalKey.RIGHT) }
            press(host, TerminalKey.DELETE)
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo Z\nZ\n>\n"))

            submit(host, "echo A😀B")
            press(host, TerminalKey.LEFT)
            press(host, TerminalKey.BACKSPACE)
            submit(host, "λ")
            press(host, TerminalKey.END)
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo AλB\nAλB\n>\n"))

            submit(host, "echo A😀" + "z".repeat(48))
            pressEnter(host)
            submit(host, "echo " + "x".repeat(50))
            press(host, TerminalKey.UP)
            assertTrue(requireNotNull(host.terminalFullState()).cells.none { it.codePoint == 0xfffd })
            press(host, TerminalKey.DOWN)
            press(host, TerminalKey.U, setOf(TerminalModifier.CONTROL))

            submit(host, "echo " + "x".repeat(100) + "😀")
            val scrolled = requireNotNull(host.terminalFullState())
            assertTrue(scrolled.cursor.x in 2..50)
            assertTrue(scrolled.cells.any { it.codePoint == 0x1f600 })
            press(host, TerminalKey.HOME)
            assertEquals(2, requireNotNull(host.terminalFullState()).cursor.x)
            press(host, TerminalKey.U, setOf(TerminalModifier.CONTROL))
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith(">\n"))
            submit(host, "echo cancelled")
            press(host, TerminalKey.C, setOf(TerminalModifier.CONTROL))
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> ^C\n>\n"))
            assertTrue(host.sendTerminalKey(TerminalKey.C, TerminalKeyAction.REPEAT, setOf(TerminalModifier.CONTROL)))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            submit(host, "echo alive")
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("alive\n>\n"))
        }

    @Test
    fun `builtin shell bounds session history and retains the current draft`() =
        withShell { host ->
            repeat(36) {
                submit(host, "echo $it")
                pressEnter(host)
            }
            repeat(40) { press(host, TerminalKey.UP) }
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo 4\n"))
            repeat(40) { press(host, TerminalKey.DOWN) }
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith(">\n"))
        }

    @Test
    fun `IDE submits a complete command to the builtin shell`() {
        withShell { host ->
            assertTrue(host.submitCanonicalLine("echo launched-from-IDE".toCharArray()))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("launched-from-IDE\n>"))
            press(host, TerminalKey.UP)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo launched-from-IDE\n"))
            press(host, TerminalKey.C, setOf(TerminalModifier.CONTROL))
            assertTrue(host.submitCanonicalLine("echo again".toCharArray()))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("again\n>"))
            assertTrue(host.submitCanonicalLine("/home/hello".toCharArray()))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("nested child ran\n>"))
        }
    }

    @Test
    fun `IDE complete lines preserve drafts and reject busy children and queued input`() {
        withShell { host ->
            submit(host, "echo draft")
            val screen = requireNotNull(host.terminalFullState())
            val draft = assertFailsWith<VmCanonicalLineException> { host.submitCanonicalLine("echo injected".toCharArray()) }
            assertEquals(VmCanonicalLineFailure.PARTIAL_INPUT, draft.failure)
            assertEquals(screen, host.terminalFullState())
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("draft\n>"))

            assertTrue(host.submitCanonicalLine("edit note.txt".toCharArray()))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            val busy = assertFailsWith<VmCanonicalLineException> { host.submitCanonicalLine("echo injected".toCharArray()) }
            assertEquals(VmCanonicalLineFailure.NO_PENDING_READ, busy.failure)
            press(host, TerminalKey.C, setOf(TerminalModifier.CONTROL))

            for ((line, expected) in listOf(
                "x".repeat(257) to VmCanonicalLineFailure.LINE_TOO_LONG,
                "echo a\necho b" to VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT,
                "\uD800" to VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT,
            )) {
                val rejected = assertFailsWith<VmCanonicalLineException> { host.submitCanonicalLine(line.toCharArray()) }
                assertEquals(expected, rejected.failure)
            }
            assertTrue(host.sendTerminalText("echo queued"))
            val queued = assertFailsWith<VmCanonicalLineException> { host.submitCanonicalLine("echo injected".toCharArray()) }
            assertEquals(VmCanonicalLineFailure.INPUT_BUSY, queued.failure)
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            pressEnter(host)
            assertTrue(terminalText(requireNotNull(host.terminalFullState())).contains("queued\n>"))
        }
    }

    private fun shellCommand(
        host: ProgramRuntimeHost,
        command: String,
    ): String {
        submit(host, "clear")
        pressEnter(host)
        submit(host, command)
        pressEnter(host)
        return terminalText(requireNotNull(host.terminalFullState()))
    }

    private fun withEditor(block: (ProgramRuntimeHost) -> Unit) =
        withShell { host ->
            submit(host, "edit regression.txt")
            pressEnter(host)
            block(host)
        }

    private fun withShell(block: (ProgramRuntimeHost) -> Unit) {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val rom =
            rom(
                Path.of(requiredProperty("compukters.bootRuntime.artifact")).readBytes(),
                Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes(),
                Path.of(requiredProperty("compukters.kotlincRuntime.artifact")).readBytes(),
                Path.of(requiredProperty("compukters.editRuntime.artifact")).readBytes(),
                Path.of(requiredProperty("compukters.processTerminalChild.artifact")).readBytes(),
            )
        val installer = Path.of(requiredProperty("compukters.processInstallRomExecutable.artifact")).readBytes()
        val root = Files.createTempDirectory("compukters-editor-regression-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val computerId = ComputerId.fromLongs(3, 4)
                val generation =
                    VmSession.openInStore(installer, store, computerId, rom).use { session ->
                        assertEquals(VmOutcome.Halted(VmValue.I32(0)), advanceUntilHalted(session))
                        session.filesystemGeneration()
                    }
                store.flush(computerId, generation)
                ProgramRuntimeHost(store = store, computerId = computerId, romImage = rom).use { host ->
                    assertEquals(ProgramStartResult.Started, host.startBoot())
                    advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
                    block(host)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ROM boot runs a foreground child and reboot creates a fresh shell`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val boot = Path.of(requiredProperty("compukters.bootRuntime.artifact")).readBytes()
        val shell = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val kotlinc = Path.of(requiredProperty("compukters.kotlincRuntime.artifact")).readBytes()
        val edit = Path.of(requiredProperty("compukters.editRuntime.artifact")).readBytes()
        val child = Path.of(requiredProperty("compukters.processTerminalChild.artifact")).readBytes()
        val installer = Path.of(requiredProperty("compukters.processInstallRomExecutable.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(edit))
        val rom = rom(boot, shell, kotlinc, edit, child)
        val root = Files.createTempDirectory("compukters-foreground-process-").toRealPath()
        try {
            TestCompilerService
                .open(
                    root.resolve("compiler"),
                    Path.of(requiredProperty("compukters.compilerWorker.manifest")),
                    Path.of(requiredProperty("compukters.compilerWorker.payload")),
                ).use { compiler ->
                    WorldFileSystemStore.open(root).use { store ->
                        val computerId = ComputerId.fromLongs(1, 2)
                        val generation =
                            VmSession.openInStore(installer, store, computerId, rom).use { session ->
                                assertEquals(VmOutcome.Halted(VmValue.I32(0)), advanceUntilHalted(session))
                                session.filesystemGeneration()
                            }
                        store.flush(computerId, generation)
                        val computer =
                            ProgramRuntimeHost(
                                store = store,
                                computerId = computerId,
                                romImage = rom,
                                // Value-class constructor validation raises the editor minimum slice above 64.
                                tickBudget = ProgramTickBudget(128, 64, 4),
                                compilerRouter = compiler.router,
                            )
                        computer.use {
                            assertEquals(ProgramStartResult.Started, computer.startBoot())
                            advanceUntil(computer) { it == ProgramRuntimeState.WaitingForInput }
                            assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                            submit(computer, "hello")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> hello\nnested child ran\n>\n"),
                            )

                            submit(computer, "hello raw tail")
                            pressEnter(computer)
                            val argumentFailure = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(
                                argumentFailure.endsWith(
                                    "> hello raw tail\nprogram entry point does not accept arguments\n>\n",
                                ),
                                argumentFailure,
                            )

                            submit(computer, "kotlinc")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> kotlinc\nusage: kotlinc <source.kt> [-o output]\n>\n"),
                            )

                            for ((arguments, diagnostic) in listOf(
                                "a.kt -o b -o c" to "duplicate -o option",
                                "-o a.kt" to "source file must precede -o",
                                ".kt" to "source file must end in .kt",
                                "a.KT" to "source file must end in .kt",
                                "a.kt -o" to "missing output after -o",
                                "a.kt b.kt" to "kotlinc accepts exactly one source file",
                                "a.kt -o /home//bad" to "invalid compilation path",
                            )) {
                                submit(computer, "kotlinc $arguments")
                                pressEnter(computer)
                                val output = terminalText(requireNotNull(computer.terminalFullState()))
                                assertTrue(output.endsWith("> kotlinc $arguments\n$diagnostic\n>\n"), output)
                            }

                            submit(computer, "missing")
                            pressEnter(computer)
                            val missingOutput = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(
                                missingOutput.endsWith("> missing\nnot found: /rom/missing\n>\n"),
                                missingOutput,
                            )

                            // Native Ctrl+C must unwind an input-waiting child and leave the shell usable.
                            submit(computer, "edit interrupted.kt")
                            pressEnter(computer)
                            assertTrue(terminalText(requireNotNull(computer.terminalFullState())).startsWith("Compukters edit"))
                            press(computer, TerminalKey.C, setOf(TerminalModifier.CONTROL))
                            val terminatedScreen = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(terminatedScreen.contains("^S Save"), terminatedScreen)
                            assertTrue(terminatedScreen.endsWith("^C\n>\n"), terminatedScreen)
                            assertTrue(computer.sendTerminalKey(TerminalKey.C, TerminalKeyAction.REPEAT, setOf(TerminalModifier.CONTROL)))
                            advanceUntil(computer) { it == ProgramRuntimeState.WaitingForInput }
                            submit(computer, "echo survived")
                            pressEnter(computer)
                            assertTrue(terminalText(requireNotNull(computer.terminalFullState())).endsWith("survived\n>\n"))

                            submit(computer, "edit demo.kt")
                            pressEnter(computer)
                            val editorScreen = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(editorScreen.startsWith("Compukters edit"), editorScreen)
                            submit(
                                computer,
                                "import compukter.terminal.Terminal\nfun main() { Terminal.write(\"compiled editor loop\\n\") }",
                            )
                            press(computer, TerminalKey.S, setOf(TerminalModifier.CONTROL))
                            press(computer, TerminalKey.X, setOf(TerminalModifier.CONTROL))
                            submit(computer, "clear")
                            pressEnter(computer)
                            submit(computer, "stat demo.kt")
                            pressEnter(computer)
                            assertTrue(terminalText(requireNotNull(computer.terminalFullState())).contains("file: /home/demo.kt"))
                            submit(computer, "kotlinc demo.kt")
                            pressEnter(computer)
                            submit(computer, "demo")
                            pressEnter(computer)
                            val compiledOutput = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(compiledOutput.contains("compiled editor loop"), compiledOutput)

                            submit(computer, "kotlinc ./demo.kt -o /home/dir/../renamed")
                            pressEnter(computer)
                            val explicitOutput = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(explicitOutput.endsWith("compiled: /home/renamed\n>\n"), explicitOutput)
                            submit(computer, "/home/./dir/../renamed")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState())).endsWith("compiled editor loop\n>\n"),
                            )

                            submit(computer, "edit oom.kt")
                            pressEnter(computer)
                            submit(
                                computer,
                                "fun main() { outer() }\nfun outer() { Worker().allocate() }\nclass Worker { fun allocate() { val memory = ArrayList<Int>(1048576); println(memory.size) } }",
                            )
                            press(computer, TerminalKey.S, setOf(TerminalModifier.CONTROL))
                            press(computer, TerminalKey.X, setOf(TerminalModifier.CONTROL))
                            submit(computer, "clear")
                            pressEnter(computer)
                            submit(computer, "kotlinc oom.kt")
                            pressEnter(computer)
                            val oomCompilation = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(
                                oomCompilation.endsWith("compiled: /home/oom\n>\n"),
                                oomCompilation,
                            )
                            val executable = ByteArrayOutputStream()
                            val generation = requireNotNull(computer.filesystemGeneration())
                            var offset = 0L
                            do {
                                val chunk = requireNotNull(computer.fileRead(VmVirtualPath.of("/home/oom"), offset, 32768, generation))
                                executable.write(chunk.bytes)
                                offset = chunk.nextOffset
                            } while (!chunk.eof)
                            ProgramRuntimeHost().use { rootFailureHost ->
                                assertEquals(ProgramStartResult.Started, rootFailureHost.start(executable.toByteArray()))
                                advanceUntil(rootFailureHost) { it is ProgramRuntimeState.Failed }
                                val failure =
                                    assertIs<ProgramFailure.Allocation>(assertIs<ProgramRuntimeState.Failed>(rootFailureHost.state).failure)
                                val trace = requireNotNull(failure.diagnostic)
                                assertTrue(trace.contains("oom.kt:1:") && trace.contains("oom.kt:2:") && trace.contains("oom.kt:3:"), trace)
                            }
                            repeat(3) {
                                submit(computer, "oom")
                                pressEnter(computer)
                                val oomOutput = terminalText(requireNotNull(computer.terminalFullState()))
                                assertTrue(oomOutput.contains("/home/oom: OutOfMemory"), oomOutput)
                                assertTrue(oomOutput.contains("Heap limit: 256 KiB"), oomOutput)
                                assertTrue(oomOutput.contains("Allocation: ") && oomOutput.contains("(array)"), oomOutput)
                                val requestedBytes = oomOutput.substringAfter("Allocation: ").substringBefore(" bytes").toLong()
                                assertTrue(requestedBytes >= 4L * 1048576, oomOutput)
                                assertTrue(oomOutput.contains("oom.kt:1:"), oomOutput)
                                assertTrue(oomOutput.contains("oom.kt:2:"), oomOutput)
                                assertTrue(oomOutput.contains("oom.kt:3:"), oomOutput)
                                assertTrue(oomOutput.endsWith(">\n"), oomOutput)
                                submit(computer, "echo shell survived")
                                pressEnter(computer)
                                assertTrue(
                                    terminalText(requireNotNull(computer.terminalFullState())).endsWith("shell survived\n>\n"),
                                )
                            }

                            computer.shutdown()
                            assertEquals(ProgramStartResult.Started, computer.startBoot())
                            advanceUntil(computer) { it == ProgramRuntimeState.WaitingForInput }
                            assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                            submit(computer, "hello")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> hello\nnested child ran\n>\n"),
                            )
                        }
                    }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `compiled Kotlin shell edits Unicode and dispatches built-ins`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        // The expanded shell's minimum slice exceeds the former 64-instruction test budget.
        val host = ProgramRuntimeHost(ProgramTickBudget(128, 64, 4))

        assertEquals(ProgramStartResult.Started, host.start(artifact))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "help")
        pressEnter(host)
        val help = terminalText(requireNotNull(host.terminalFullState()))
        for (command in listOf("cat", "mkdir", "mv", "rm", "kotlinc", "edit", "vmbench")) {
            assertTrue(help.contains(command), help)
        }
        assertTrue(help.endsWith(">\n"), help)

        submit(host, "pwd")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> pwd\n/home\n>\n"))

        submit(host, "stat /home")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat /home\ndirectory: /home\n>\n"))

        submit(host, "stat /home/../escape")
        pressEnter(host)
        val traversalOutput = terminalText(requireNotNull(host.terminalFullState()))
        assertTrue(traversalOutput.endsWith("stat: permission denied: /home/../escape\n>\n"), traversalOutput)

        submit(host, "stat missing")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat missing\nstat: not found: /home/missing\n>\n"))

        submit(host, "ls")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> ls\n>\n"))

        submit(host, "ls a b")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> ls a b\nusage: ls [path]\n>\n"))

        submit(host, "stat")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat\nusage: stat <path>\n>\n"))

        submit(host, "missing")
        pressEnter(host)
        val missingOutput = terminalText(requireNotNull(host.terminalFullState()))
        assertTrue(missingOutput.endsWith("> missing\naccess denied: /rom/missing\n>\n"), missingOutput)

        submit(host, "echo λ😀")
        press(host, TerminalKey.BACKSPACE)
        submit(host, "😀")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo λ😀\nλ😀\n>\n"))

        submit(host, "wat argument")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("access denied: /rom/wat\n>\n"))

        submit(host, "clear")
        pressEnter(host)
        submit(host, "a".repeat(4095))
        submit(host, "😀")
        val bounded = requireNotNull(host.terminalFullState())
        assertTrue(bounded.cells.any { it.codePoint == 'a'.code })
        assertTrue(bounded.cells.none { it.codePoint == 0x1f600 })
        pressEnter(host)

        submit(host, "clear\u0000\r\n")
        val sanitized = terminalText(requireNotNull(host.terminalFullState()))
        assertTrue(sanitized.endsWith("> clear\n"), sanitized)
        pressEnter(host)
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))
        assertEquals(ProgramRuntimeState.WaitingForInput, host.state)
        host.close()
    }

    private fun submit(
        host: ProgramRuntimeHost,
        text: String,
    ) {
        assertTrue(host.sendTerminalText(text))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun pressEnter(host: ProgramRuntimeHost) = press(host, TerminalKey.ENTER)

    private fun press(
        host: ProgramRuntimeHost,
        key: TerminalKey,
        modifiers: Set<TerminalModifier>,
    ) {
        assertTrue(host.sendTerminalKey(key, TerminalKeyAction.PRESS, modifiers))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun press(
        host: ProgramRuntimeHost,
        key: TerminalKey,
    ) {
        assertTrue(host.sendTerminalKey(key, TerminalKeyAction.PRESS))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun advanceUntil(
        host: ProgramRuntimeHost,
        predicate: (ProgramRuntimeState) -> Boolean,
    ): ProgramRuntimeState {
        var remainingTicks = MAXIMUM_TICKS
        var compilerWaitStarted: Long? = null
        while (remainingTicks > 0) {
            val state = host.serverTick()
            if (predicate(state)) return state
            check(state == ProgramRuntimeState.Running || state == ProgramRuntimeState.WaitingForCompiler) {
                "program terminated before expected state: $state"
            }
            if (state == ProgramRuntimeState.WaitingForCompiler) {
                // Polling an external worker is not VM execution. Allow its startup and request deadlines.
                val started = compilerWaitStarted ?: System.nanoTime().also { compilerWaitStarted = it }
                check(System.nanoTime() - started < COMPILER_WAIT_TIMEOUT_NANOS) {
                    "compiler did not complete within its startup and compilation deadlines; last state was $state"
                }
                Thread.sleep(1)
            } else {
                remainingTicks--
                compilerWaitStarted = null
            }
        }
        error(
            "program did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${host.state}; resources=${host.resourceSnapshot()}",
        )
    }

    private fun advanceUntilHalted(session: VmSession): VmOutcome.Halted {
        repeat(MAXIMUM_TICKS) {
            when (val outcome = session.advance(64, 64, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return outcome
                else -> error("installer terminated unexpectedly: $outcome")
            }
        }
        error("installer did not halt within $MAXIMUM_TICKS slices")
    }

    private fun rom(
        boot: ByteArray,
        shell: ByteArray,
        kotlinc: ByteArray,
        edit: ByteArray,
        child: ByteArray,
    ): ByteArray {
        val programs =
            listOf(
                "/rom/boot" to boot,
                "/rom/edit" to edit,
                "/rom/hello" to child,
                "/rom/kotlinc" to kotlinc,
                "/rom/shell" to shell,
            )
        val size =
            programs.fold(16) { total, (path, artifact) ->
                Math.addExact(total, 16 + path.encodeToByteArray().size + artifact.size)
            }
        val payload =
            ByteBuffer
                .allocate(size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(programs.size)
                .also { buffer ->
                    programs.forEach { (pathText, artifact) ->
                        val path = pathText.encodeToByteArray()
                        buffer
                            .putInt(path.size)
                            .put(path)
                            .put(2.toByte())
                            .put(1.toByte())
                            .putShort(0.toShort())
                            .putLong(artifact.size.toLong())
                            .put(artifact)
                    }
                }.array()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun terminalText(state: TerminalState): String {
        val text = StringBuilder()
        repeat(state.height) { y ->
            val row = StringBuilder()
            repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            text.append(row.toString().trimEnd()).append('\n')
        }
        return text.toString().trimEnd('\n') + '\n'
    }

    private class TestCompilerService private constructor(
        val router: CompilerCompletionRouter,
        private val service: ServerCompilerService,
        private val executor: ExecutorService,
    ) : AutoCloseable {
        override fun close() {
            try {
                service.close()
            } finally {
                executor.shutdownNow()
            }
        }

        companion object {
            fun open(
                root: Path,
                bundleManifest: Path,
                archive: Path,
            ): TestCompilerService {
                val bundle =
                    Files.newInputStream(bundleManifest).use { manifest ->
                        Files.newInputStream(archive).use { input ->
                            PackagedToolingBundle.publish(manifest, input, root.resolve("payload"))
                        }
                    }
                val payload = WorkerPayloadLoader.loadToolingProfile(bundle.root)
                val limits = WorkerLimits()
                val temporary = root.resolve("temporary")
                Files.createDirectories(temporary)
                val launch =
                    WorkerLaunch(
                        javaExecutable = javaExecutable(),
                        maximumHeapMiB = 256,
                        maximumMetaspaceMiB = 256,
                        temporaryDirectory = temporary,
                        expectedIdentity = payload.manifest.identity,
                        maximumFrameBytes = limits.frameBytes,
                        maximumStderrBytes = limits.stderrBytes,
                    )
                val backend =
                    WorkerCompilerBackend(
                        CompilerWorkerController(payload, launch, limits, JdkWorkerProcessFactory()),
                    )
                val compilerIdentity = payload.manifest.identity
                val platform =
                    PackagedPlatformBundleLoader.load(
                        payload.classpath,
                        compilerIdentity.languageVersion,
                        Sha256.of(compilerIdentity.platformAbi.toByteArray()),
                    )
                val platformModules =
                    platform.modules.map { module ->
                        TrustedBundleIdentity.of(
                            module.id.toString(),
                            Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                        )
                    }
                val cache =
                    PersistentCompilationCache.open(
                        root.resolve("cache"),
                        verifier = ArtifactVerifier(VmArtifactVerifier::verify),
                    )
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val service =
                        ServerCompilerService(
                            cache,
                            backend,
                            CompilerServiceConfiguration(compilerIdentity, limits, platformModules = platformModules),
                            executor = executor,
                        )
                    return TestCompilerService(
                        CompilerCompletionRouter(ServerComputerCompiler(service, limits)),
                        service,
                        executor,
                    )
                } catch (error: Throwable) {
                    executor.shutdownNow()
                    runCatching(backend::close)
                    runCatching(cache::close)
                    throw error
                }
            }

            private fun javaExecutable(): Path {
                val executable = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
                return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize()
            }
        }
    }

    private companion object {
        const val MAXIMUM_TICKS = 10_000
        val COMPILER_WAIT_TIMEOUT_NANOS =
            CompilerWorkerPolicy().let { it.startupTimeoutNanos + it.compilationTimeoutNanos + it.terminationGraceMillis * 1_000_000 }
    }
}
