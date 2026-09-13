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

package ru.lazyhat.compukters.impl.computer

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import java.util.concurrent.CompletableFuture

internal object CreateKineticsGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(4, 3, 4)
        val leftPosition = computerPosition.west()
        val rightPosition = computerPosition.east()
        val frontPosition = computerPosition.north()
        val backPosition = computerPosition.south()
        val stressPosition = computerPosition.below()
        val controllerPosition = computerPosition.above()
        helper.setBlock(leftPosition, AllBlocks.SPEEDOMETER.get())
        helper.setBlock(rightPosition, AllBlocks.SPEEDOMETER.get())
        helper.setBlock(frontPosition, AllBlocks.SPEEDOMETER.get())
        helper.setBlock(backPosition, AllBlocks.SPEEDOMETER.get())
        helper.setBlock(stressPosition, AllBlocks.STRESSOMETER.get())
        helper.setBlock(controllerPosition, AllBlocks.ROTATION_SPEED_CONTROLLER.get())
        helper.setBlock(
            computerPosition,
            CompuktersRegistry.COMPUTER
                .get()
                .defaultBlockState()
                .setValue(ComputerBlock.FACING, Direction.NORTH),
        )
        val entity = helper.compuktersComputerBlockEntity(computerPosition)
        val left = helper.getBlockEntity<SpeedGaugeBlockEntity>(leftPosition)
        val right = helper.getBlockEntity<SpeedGaugeBlockEntity>(rightPosition)
        val back = helper.getBlockEntity<SpeedGaugeBlockEntity>(backPosition)
        val stressometer = helper.getBlockEntity<StressGaugeBlockEntity>(stressPosition)
        entity.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var terminal: CompletableFuture<TerminalState?>? = null

        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(
                    entity.runtimeState == ProgramComputerState.WaitingForInput,
                    "computer shell did not become ready for the Create kinetics program",
                )
            }.thenExecute {
                setup = deployAndRun(helper, entity)
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "Create kinetics program setup is still pending")
                setup!!.getNow(null)
            }.thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "\n256\n")
            .thenIdle(2)
            .thenExecute {
                left.setSpeed(12.5f)
            }.thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "\n12.5\n")
            .thenExecute {
                right.setSpeed(-7.25f)
            }.thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "\n-7.25\n")
            .thenExecute {
                back.setSpeed(21.75f)
            }.thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "\n21.75\n")
            .thenExecute {
                stressometer.updateFromNetwork(9.25f, 3.5f, 1)
            }.thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "\n3.5\n9.25\n")
            .thenWaitForOutput(helper, entity, terminal = { terminal }, update = { terminal = it }, expected = "waiting-disconnect\n")
            .thenExecute {
                helper.setBlock(frontPosition, Blocks.AIR)
                helper.setBlock(frontPosition, AllBlocks.SPEEDOMETER.get())
                helper.getBlockEntity<SpeedGaugeBlockEntity>(frontPosition).setSpeed(64f)
            }.thenWaitUntil {
                val output = terminalOutput(helper, entity, { terminal }) { terminal = it }
                helper.assertTrue(
                    entity.runtimeState == ProgramComputerState.WaitingForInput && output.endsWith(">\n"),
                    "disconnected Create handle did not fail back to the shell: state=${entity.runtimeState} output=$output",
                )
                helper.assertTrue("unexpected-rebind" !in output, "replaced Create block silently rebound the old handle")
            }.thenSucceed()
    }

    private fun deployAndRun(
        helper: GameTestHelper,
        entity: NeoForgeComputerBlockEntity,
    ): CompletableFuture<Void> =
        entity
            .verifyForDeployAsync(fixture())
            .thenCompose { candidate ->
                requireNotNull(candidate)
                entity.executableRevisionAsync("/home/create-kinetics").thenCompose { expected ->
                    requireNotNull(expected)
                    helper.assertTrue(expected == VmExecutableRevision.Absent, "Create kinetics executable already existed")
                    entity.deployAsync("/home/create-kinetics", expected, candidate)
                }
            }.thenCompose {
                entity.submitTerminalTextAsync("create-kinetics")
            }.thenCompose { accepted ->
                helper.assertTrue(accepted, "shell rejected the Create kinetics command")
                entity.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }.thenAccept { accepted ->
                helper.assertTrue(accepted, "shell rejected the Create kinetics command enter key")
            }

    private fun net.minecraft.gametest.framework.GameTestSequence.thenWaitForOutput(
        helper: GameTestHelper,
        entity: NeoForgeComputerBlockEntity,
        terminal: () -> CompletableFuture<TerminalState?>?,
        update: (CompletableFuture<TerminalState?>?) -> Unit,
        expected: String,
    ) = thenWaitUntil {
        val output = terminalOutput(helper, entity, terminal, update)
        helper.assertTrue(expected in output, "Create kinetics output is missing '$expected': $output")
    }

    private fun terminalOutput(
        helper: GameTestHelper,
        entity: NeoForgeComputerBlockEntity,
        terminal: () -> CompletableFuture<TerminalState?>?,
        update: (CompletableFuture<TerminalState?>?) -> Unit,
    ): String {
        var snapshot = terminal()
        if (snapshot == null) {
            snapshot = entity.terminalFullStateAsync()
            update(snapshot)
        }
        helper.assertTrue(
            snapshot.isDone,
            "Create kinetics terminal snapshot is still pending: " +
                "state=${entity.runtimeState} metrics=${NeoForgeVmActorServices.metrics(helper.level.server)}",
        )
        val state = snapshot.getNow(null)
        if (state == null) {
            update(null)
            helper.assertTrue(false, "Create kinetics terminal snapshot was unavailable")
        }
        val output = terminalText(requireNotNull(state))
        update(null)
        return output
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/create-kinetics.cpkt")) {
            "missing generated Create kinetics GameTest fixture"
        }.use { it.readAllBytes() }

    private fun terminalText(terminal: TerminalState): String {
        val output = StringBuilder()
        repeat(terminal.height) { y ->
            val row = StringBuilder()
            repeat(terminal.width) { x -> row.appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString().trimEnd('\n') + '\n'
    }
}
