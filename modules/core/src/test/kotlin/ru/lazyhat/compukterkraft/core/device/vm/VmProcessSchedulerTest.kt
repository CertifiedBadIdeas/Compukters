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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VmProcessSchedulerTest {
    @Test
    fun tickWakesDueSleepersAndSelectsNextRunnablePid() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "child.ck", argument = "", workingDirectory = "")
        table.markSleeping(pid = 1, untilTick = 5)

        val scheduler = VmProcessScheduler(table)

        assertEquals(VmProcessSchedulerTick(currentTick = 4, wokenPids = emptyList(), selectedPid = 2), scheduler.tick(4))
        assertEquals(VmProcessSchedulerTick(currentTick = 5, wokenPids = listOf(1), selectedPid = 2), scheduler.tick(5))
        assertEquals(VmProcessSchedulerTick(currentTick = 6, wokenPids = emptyList(), selectedPid = 1), scheduler.tick(6))
    }

    @Test
    fun tickReturnsNoSelectionWhenNoProcessIsRunnable() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "child.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 3, parentPid = 1, programPath = "done.ck", argument = "", workingDirectory = "")
        table.markWaitingEvent(pid = 1, filter = null)
        table.markSleeping(pid = 2, untilTick = 10)
        table.markExited(pid = 3, exitCode = 0)

        val scheduler = VmProcessScheduler(table)

        val tick = scheduler.tick(currentTick = 9)
        assertEquals(emptyList(), tick.wokenPids)
        assertNull(tick.selectedPid)
        assertEquals(9, tick.currentTick)
    }

    @Test
    fun tickUsesRoundRobinSelectionOrder() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "a.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 3, parentPid = 1, programPath = "b.ck", argument = "", workingDirectory = "")

        val scheduler = VmProcessScheduler(table)

        assertEquals(1, scheduler.tick(currentTick = 1).selectedPid)
        assertEquals(2, scheduler.tick(currentTick = 2).selectedPid)
        assertEquals(3, scheduler.tick(currentTick = 3).selectedPid)
        assertEquals(1, scheduler.tick(currentTick = 4).selectedPid)
    }
}
