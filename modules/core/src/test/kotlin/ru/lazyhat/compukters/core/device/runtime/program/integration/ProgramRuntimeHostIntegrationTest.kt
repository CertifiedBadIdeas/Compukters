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

import ru.lazyhat.compukters.core.device.computer.ProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramRuntimeHostIntegrationTest {
    @Test
    fun `ROM boot runs a foreground child and reboot creates a fresh shell`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val boot = Path.of(requiredProperty("compukters.bootRuntime.artifact")).readBytes()
        val shell = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val kotlinc = Path.of(requiredProperty("compukters.kotlincRuntime.artifact")).readBytes()
        val child = Path.of(requiredProperty("compukters.processTerminalChild.artifact")).readBytes()
        val installer = Path.of(requiredProperty("compukters.processInstallRomExecutable.artifact")).readBytes()
        val rom = rom(boot, shell, kotlinc, child)
        val root = Files.createTempDirectory("compukters-foreground-process-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val computerId = ComputerId.fromLongs(1, 2)
                val generation =
                    VmSession.openInStore(installer, store, computerId, rom).use { session ->
                        assertEquals(VmOutcome.Halted(VmValue.I32(0)), advanceUntilHalted(session))
                        session.filesystemGeneration()
                    }
                store.flush(computerId, generation)
                val computer =
                    ProgramComputer(
                        deviceId = 1,
                        stateSink = { _, _ -> },
                        store = store,
                        computerId = computerId,
                        romImage = rom,
                        tickBudget = ProgramTickBudget(64, 64, 4),
                    )
                computer.use {
                    assertEquals(ProgramComputerState.Running, computer.turnOn())
                    advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
                    assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                    submit(computer, "hello")
                    pressEnter(computer)
                    assertTrue(
                        terminalText(requireNotNull(computer.terminalFullState()))
                            .endsWith("> hello\nnested child ran\n>\n"),
                    )

                    submit(computer, "hello raw tail")
                    pressEnter(computer)
                    assertTrue(
                        terminalText(requireNotNull(computer.terminalFullState()))
                            .endsWith("> hello raw tail\nnested child ran\n>\n"),
                    )

                    submit(computer, "kotlinc")
                    pressEnter(computer)
                    assertTrue(
                        terminalText(requireNotNull(computer.terminalFullState()))
                            .endsWith("> kotlinc\nusage: kotlinc <source.kt> [-o output]\n>\n"),
                    )

                    submit(computer, "missing")
                    pressEnter(computer)
                    assertTrue(
                        terminalText(requireNotNull(computer.terminalFullState()))
                            .endsWith("> missing\ncommand not found: /rom/missing\n>\n"),
                    )

                    assertEquals(ProgramComputerState.Running, computer.reboot())
                    advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
                    assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                    submit(computer, "hello")
                    pressEnter(computer)
                    assertTrue(
                        terminalText(requireNotNull(computer.terminalFullState()))
                            .endsWith("> hello\nnested child ran\n>\n"),
                    )
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
        val host = ProgramRuntimeHost(ProgramTickBudget(64, 64, 4))

        assertEquals(ProgramStartResult.Started, host.start(artifact))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "help")
        pressEnter(host)
        assertEquals("> help\nhelp echo clear pwd ls stat kotlinc\n>\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "pwd")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> pwd\n/home\n>\n"))

        submit(host, "stat /home")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat /home\ndirectory: /home\n>\n"))

        submit(host, "stat missing")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat missing\nnot found: /home/missing\n>\n"))

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
        assertTrue(missingOutput.endsWith("> missing\npermission denied: /rom/missing\n>\n"), missingOutput)

        submit(host, "echo λ😀")
        press(host, TerminalKey.BACKSPACE)
        submit(host, "😀")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo λ😀\nλ😀\n>\n"))

        submit(host, "wat argument")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("permission denied: /rom/wat\n>\n"))

        submit(host, "clear")
        pressEnter(host)
        submit(host, "a".repeat(255) + "😀")
        val bounded = requireNotNull(host.terminalFullState())
        assertEquals(255, bounded.cells.count { it.codePoint == 'a'.code })
        assertTrue(bounded.cells.none { it.codePoint == 0x1f600 })
        pressEnter(host)

        submit(host, "clear\u0000\r\n")
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> clear\n"))
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

    private fun submit(
        computer: ProgramComputer,
        text: String,
    ) {
        assertTrue(computer.sendTerminalText(text))
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
    }

    private fun pressEnter(host: ProgramRuntimeHost) = press(host, TerminalKey.ENTER)

    private fun pressEnter(computer: ProgramComputer) {
        assertTrue(computer.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS))
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
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
        repeat(MAXIMUM_TICKS) {
            val state = host.serverTick()
            if (predicate(state)) return state
            check(state == ProgramRuntimeState.Running) { "program terminated before expected state: $state" }
        }
        error("program did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${host.state}")
    }

    private fun advanceUntil(
        computer: ProgramComputer,
        predicate: (ProgramComputerState) -> Boolean,
    ): ProgramComputerState {
        repeat(MAXIMUM_TICKS) {
            val state = computer.serverTick()
            if (predicate(state)) return state
            check(state == ProgramComputerState.Running) {
                "computer terminated before expected state: $state; terminal=${computer.terminalFullState()?.let(::terminalText)}"
            }
        }
        error("computer did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${computer.state}")
    }

    private fun advanceUntilHalted(session: VmSession): VmOutcome.Halted {
        repeat(MAXIMUM_TICKS) {
            when (val outcome = session.advance(64, 64)) {
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
        child: ByteArray,
    ): ByteArray {
        val programs = listOf("/rom/boot" to boot, "/rom/hello" to child, "/rom/kotlinc" to kotlinc, "/rom/shell" to shell)
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

    private companion object {
        const val MAXIMUM_TICKS = 10_000
    }
}
