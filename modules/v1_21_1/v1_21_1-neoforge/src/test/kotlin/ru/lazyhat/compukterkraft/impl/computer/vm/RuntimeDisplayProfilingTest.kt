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

package ru.lazyhat.compukterkraft.impl.computer.vm

import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeDisplayProfilingTest {
    @Test
    fun bundledTerminalWorkloadProducesProfilingMetrics() {
        val run = RuntimeProfilingWorkload.runTerminalWorkload(delayMillis = 10, bootTicks = 80, inputTicks = 20, enterTicks = 40)
        val displaySnapshot = run.displayMetrics.snapshot()
        val clientSnapshot = run.clientMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()
        println(displaySnapshot.summary())
        println(clientSnapshot)
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(displaySnapshot.operations.fillRectCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.copyRectCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.fillRectCalls < 1000, displaySnapshot.summary())
        assertTrue(displaySnapshot.operations.presentCalls > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.frameCount > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.tileCount > 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frames.payloadBytes > 0, displaySnapshot.summary())
        assertTrue(clientSnapshot.framesApplied > 0, clientSnapshot.toString())
        assertTrue(clientSnapshot.applyNanos >= 0, clientSnapshot.toString())
        assertTrue(clientSnapshot.snapshotPixels > 0, clientSnapshot.toString())
        assertTrue(runtimeSnapshot.tick.serverTickCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.requestSliceCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostCallDrainCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostCallDispatchCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.hostResultDeliveryCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.tick.displayFrameDrainCalls > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.sliceRequests > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.slicePermitsReceived > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.executionWindowNanos > 0, runtimeSnapshot.summary())
        assertTrue(compilerSnapshot.compileCalls > 0, compilerSnapshot.summary())
        assertTrue(compilerSnapshot.compileNanos > 0, compilerSnapshot.summary())
    }

    @Test
    fun sustainedTerminalWorkloadProducesNoDelayProfilingMetrics() {
        val run = RuntimeProfilingWorkload.runTerminalWorkload(delayMillis = 0, bootTicks = 120, inputTicks = 40, enterTicks = 80)
        val displaySnapshot = run.displayMetrics.snapshot()
        val clientSnapshot = run.clientMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()

        println(displaySnapshot.summary())
        println(clientSnapshot)
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(displaySnapshot.operations.blitMonoNanos >= 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frameBuild.buildCalls > 0, displaySnapshot.summary())
        assertTrue(clientSnapshot.framesApplied > 0, clientSnapshot.toString())
        assertTrue(
            runtimeSnapshot.vm.pauseSignals + runtimeSnapshot.vm.yieldSignals + runtimeSnapshot.vm.hostCallSignals > 0,
            runtimeSnapshot.summary(),
        )
        assertTrue(runtimeSnapshot.vm.averageExecutionWindowNanos >= 0, runtimeSnapshot.summary())
        assertTrue(compilerSnapshot.compileCalls > 0, compilerSnapshot.summary())
        assertTrue(compilerSnapshot.compileNanos > 0, compilerSnapshot.summary())
    }

    @Test
    fun defaultSizeTerminalWorkloadUsesComputerTerminalResolution() {
        val displayWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH * TerminalFontConstants.FONT_WIDTH
        val displayHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT * TerminalFontConstants.FONT_HEIGHT
        val run =
            RuntimeProfilingWorkload.runTerminalWorkload(
                delayMillis = 10,
                bootTicks = 80,
                inputTicks = 20,
                enterTicks = 40,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        val clientSnapshot = run.clientMetrics.snapshot()

        assertTrue(clientSnapshot.framesApplied > 0, clientSnapshot.toString())
        assertTrue(clientSnapshot.snapshotPixels >= displayWidth.toLong() * displayHeight.toLong(), clientSnapshot.toString())
        assertTrue(run.pipeline?.enterClientFrames ?: 0 > 0, run.pipeline.toString())
    }

    @Test
    fun heldEnterWorkloadProducesBacklogProfilingMetrics() {
        val run = RuntimeProfilingWorkload.runHeldEnterWorkload(repeatEnterEvents = 120, settleTicks = 220)
        val displaySnapshot = run.profiling.displayMetrics.snapshot()
        val clientSnapshot = run.profiling.clientMetrics.snapshot()
        val runtimeSnapshot = run.profiling.runtimeMetrics.snapshot()
        val compilerSnapshot = run.profiling.compilerMetrics.snapshot()

        println(run.summary())
        println(displaySnapshot.summary())
        println(clientSnapshot)
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(run.enterEventsQueued == 120, run.summary())
        assertTrue(run.maxQueuedEvents > 0, run.summary())
        assertTrue(runtimeSnapshot.vm.hostCallSignals > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.nativeWaitSignals > 0, runtimeSnapshot.summary())
        assertTrue(
            runtimeSnapshot.hostCalls.none { it.moduleName == "events" && it.functionName == "tryPull" },
            runtimeSnapshot.summary(),
        )
        assertTrue(
            runtimeSnapshot.hostCalls.none { it.moduleName == "runtime" && it.functionName == "poll" },
            runtimeSnapshot.summary(),
        )
        assertTrue(
            runtimeSnapshot.hostCalls.none { it.moduleName == "ipc" && it.functionName == "tryRead" },
            runtimeSnapshot.summary(),
        )
        assertTrue(
            runtimeSnapshot.hostCalls.none { it.moduleName == "ipc" && it.functionName == "write" },
            runtimeSnapshot.summary(),
        )
        assertTrue(displaySnapshot.frames.frameCount > 0, displaySnapshot.summary())
        assertTrue(clientSnapshot.framesApplied > 0, clientSnapshot.toString())
    }
}
