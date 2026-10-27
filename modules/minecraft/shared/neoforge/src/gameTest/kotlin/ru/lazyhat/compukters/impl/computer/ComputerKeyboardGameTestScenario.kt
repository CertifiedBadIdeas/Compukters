/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.minecraft.input.ComputerKeyboard
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Real compiled Guest consumer, real computer host and a registered mock network player. */
internal object ComputerKeyboardGameTestScenario {
    fun run(helper: GameTestHelper) {
        val pos = BlockPos(2, 2, 2)
        helper.setBlock(pos, CompuktersRegistry.COMPUTER.get())
        val entity = helper.compuktersComputerBlockEntity(pos)
        val player = helper.makeMockServerPlayerInLevel()
        val absolute = helper.absolutePos(pos)
        player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 2.0)
        entity.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var terminal: CompletableFuture<TerminalState?>? = null
        var token: UUID? = null
        var sequence = 0L
        var held = 0L
        var activated = false
        val previous = ComputerKeyboard.sendStatus
        ComputerKeyboard.sendStatus = { target, current, mask ->
            if (target === player) token = current else previous(target, current, mask)
        }

        fun cleanup() {
            ComputerKeyboard.disconnect(player)
            ComputerKeyboard.sendStatus = previous
        }
        helper.onEachTick {
            token?.let { ComputerKeyboard.heartbeat(player, it, sequence++, held, false) }
            if (helper.tick >= 4990) cleanup()
        }
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(entity.runtimeState == ProgramComputerState.WaitingForInput, "keyboard shell not ready")
            }.thenExecute {
                helper.assertFalse(ComputerKeyboard.toggle(player, absolute), "input consent accepted without a program request")
                val fixture = requireNotNull(javaClass.getResourceAsStream("/fixtures/keyboard.cpkt")).use { it.readAllBytes() }
                setup =
                    entity
                        .verifyForDeployAsync(fixture)
                        .thenCompose { candidate ->
                            entity.executableRevisionAsync("/home/keyboard").thenCompose { expected ->
                                entity.deployAsync("/home/keyboard", requireNotNull(expected), requireNotNull(candidate))
                            }
                        }.thenCompose { entity.submitTerminalTextAsync("keyboard") }
                        .thenCompose { entity.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS) }
                        .thenAccept { helper.assertTrue(it, "keyboard shell command rejected") }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "keyboard deploy pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                if (terminal == null) terminal = entity.terminalFullStateAsync()
                helper.assertTrue(terminal!!.isDone, "keyboard terminal pending")
                val output = terminal!!.getNow(null)?.let(::text).orEmpty()
                terminal = null
                if (!activated && "KEYBOARD-READY" in output) {
                    helper.assertTrue(token == null, "program captured player without consent")
                    helper.assertTrue(ComputerKeyboard.toggle(player, absolute), "explicit keyboard activation failed")
                    helper.assertTrue(token != null, "activation status was not sent")
                    activated = true
                }
                if ("KEYBOARD-CONNECTED" in output && "KEYBOARD-DOWN" !in output) held = (1L shl 22) or Long.MIN_VALUE
                if ("KEYBOARD-DOWN" in output) held = 0
                val done = "KEYBOARD-RELEASED" in output && "KEYBOARD-CLOSED" in output
                if (done) {
                    helper.assertTrue(token == null, "closed program retained player capture")
                    helper.assertFalse(ComputerKeyboard.toggle(player, absolute), "closed keyboard request was revived")
                    cleanup()
                }
                helper.assertTrue(
                    done && entity.runtimeState == ProgramComputerState.WaitingForInput,
                    "keyboard round trip pending: $output",
                )
            }.thenSucceed()
    }

    private fun text(state: TerminalState): String =
        (0 until state.height).joinToString("\n") { y ->
            buildString { repeat(state.width) { x -> appendCodePoint(state.cells[y * state.width + x].codePoint) } }.trimEnd()
        }
}
