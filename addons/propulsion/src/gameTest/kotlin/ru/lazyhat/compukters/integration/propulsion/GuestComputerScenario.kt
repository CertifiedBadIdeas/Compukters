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

package ru.lazyhat.compukters.integration.propulsion

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestSequence
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.concurrent.CompletableFuture

/** Uses the public computer input boundary, actual Guest editor, compiler worker and addon-enabled VM. */
internal class GuestComputerScenario(
    private val helper: GameTestHelper,
    position: BlockPos,
    private val resolvePosition: () -> BlockPos = { helper.absolutePos(position) },
) {
    private val computer get() = helper.level.getBlockEntity(resolvePosition()) as ComputerBlockEntity
    private var operation: CompletableFuture<*>? = null
    private var snapshot: CompletableFuture<TerminalState?>? = null
    private var lastObservation = "no terminal snapshot completed"

    fun prepare(
        sequence: GameTestSequence,
        source: String,
        addons: List<String> = listOf("propulsion"),
    ) {
        sequence.thenExecute { computer.prepareTerminalAsync() }
        awaitText(sequence, ">\n")
        val addonNames = addons.joinToString(", ") { "\"$it\"" }
        writeFile(sequence, "compukter.toml", "format = 3\nname = \"propulsion-gametest\"\naddons = [$addonNames]\n")
        val preparedSource = source.lineSequence().joinToString("\n") { it.trimStart() }
        writeFile(sequence, "main.kt", preparedSource)
        sequence.thenExecute {
            val path = VmVirtualPath.of("/home/main.kt")
            operation =
                computer.fileStatAsync(path).thenCompose { stat ->
                    computer.fileReadAsync(path, 0, preparedSource.encodeToByteArray().size + 1, checkNotNull(stat).metadata.generation)
                }
        }
        awaitOperation(sequence)
        sequence.thenExecute {
            val actual = (operation!!.join() as VmFileChunk).bytes.decodeToString()
            helper.assertTrue(
                actual == preparedSource,
                "Guest editor changed the source: expected=${preparedSource.length}, actual=${actual.length}; actual=$actual",
            )
        }
        sequence.thenExecute { operation = command("kotlinc main.kt -o scenario") }
        awaitOperation(sequence)
        awaitText(sequence, "compiled: /home/scenario")
        sequence.thenExecute { operation = command("scenario") }
        awaitOperation(sequence)
    }

    fun awaitMarker(
        sequence: GameTestSequence,
        marker: String,
    ) = awaitText(sequence, marker, linePrefix = true)

    fun inspectTerminal(
        sequence: GameTestSequence,
        inspect: (String) -> Unit,
    ) {
        sequence.thenExecute { snapshot = computer.terminalFullStateAsync() }
        sequence.thenWaitUntil {
            helper.assertTrue(snapshot?.isDone == true, "terminal inspection is pending")
            val text = terminalText(snapshot!!.join())
            inspect(text)
            snapshot = null
        }
    }

    fun terminate(sequence: GameTestSequence) {
        sequence.thenExecute {
            operation = computer.submitTerminalKeyAsync(TerminalKey.T, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL))
        }
        awaitOperation(sequence)
    }

    fun resume(sequence: GameTestSequence) {
        sequence.thenExecute { operation = computer.submitCanonicalLineAsync("continue".toCharArray()) }
        awaitOperation(sequence)
    }

    private fun writeFile(
        sequence: GameTestSequence,
        path: String,
        text: String,
    ) {
        sequence.thenExecute { operation = command("edit $path") }
        awaitOperation(sequence)
        awaitText(sequence, "Compukters edit")
        // The Guest editor copies indentation after Enter; pasted source must not add it again.
        val input = text.lineSequence().joinToString("\n") { it.trimStart() }
        // Keep each editor input event within the ordinary child-execution quota.
        input.chunked(256).forEach { chunk ->
            sequence.thenExecute { operation = computer.submitTerminalTextAsync(chunk) }
            awaitOperation(sequence)
        }
        sequence.thenExecute {
            operation =
                computer.submitTerminalKeyAsync(
                    TerminalKey.S,
                    TerminalKeyAction.PRESS,
                    setOf(TerminalModifier.CONTROL),
                )
        }
        awaitOperation(sequence)
        sequence.thenExecute {
            operation =
                computer.submitTerminalKeyAsync(
                    TerminalKey.X,
                    TerminalKeyAction.PRESS,
                    setOf(TerminalModifier.CONTROL),
                )
        }
        awaitOperation(sequence)
        awaitText(sequence, ">\n")
    }

    private fun command(value: String): CompletableFuture<Boolean> =
        computer.submitTerminalTextAsync(value).thenCompose { accepted ->
            check(accepted) { "computer rejected command text: $value" }
            computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
        }

    private fun awaitOperation(sequence: GameTestSequence) {
        sequence.thenWaitUntil {
            helper.assertTrue(operation?.isDone == true, "computer input operation is pending")
            val result = operation!!.join()
            helper.assertTrue(result != false, "computer rejected the input operation")
        }
    }

    private fun awaitText(
        sequence: GameTestSequence,
        expected: String,
        linePrefix: Boolean = false,
    ) {
        sequence.thenExecute { LogUtils.getLogger().info("Propulsion GameTest awaiting terminal checkpoint: {}", expected.trim()) }
        sequence.thenWaitUntil {
            if (snapshot == null) snapshot = computer.terminalFullStateAsync()
            helper.assertTrue(snapshot!!.isDone, "terminal snapshot is pending; expected $expected; $lastObservation")
            val state = snapshot!!.join()
            snapshot = null
            val text = terminalText(state)
            lastObservation = "state=${computer.runtimeState}; terminal=$text"
            helper.assertTrue(
                computer.runtimeState == ProgramComputerState.WaitingForInput &&
                    (if (linePrefix) text.lineSequence().any { it.startsWith(expected) } else text.contains(expected)),
                "expected $expected; state=${computer.runtimeState}; terminal=$text",
            )
        }
    }

    private fun terminalText(state: TerminalState?): String {
        if (state == null) return "<no terminal>"
        return (0 until state.height).joinToString("\n", postfix = "\n") { y ->
            buildString {
                repeat(state.width) { x -> appendCodePoint(state.cells[y * state.width + x].codePoint) }
            }.trimEnd()
        }
    }
}
