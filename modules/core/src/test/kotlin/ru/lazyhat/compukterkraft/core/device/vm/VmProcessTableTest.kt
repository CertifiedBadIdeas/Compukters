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
import kotlin.test.assertIs
import kotlin.test.assertNull

class VmProcessTableTest {
    @Test
    fun registerProcessStoresMetadataAndRunnableState() {
        val table = VmProcessTable()

        table.registerProcess(pid = 2, parentPid = 1, programPath = "child.ck", argument = "arg", workingDirectory = "bin")

        val record = table.snapshot(2)
        assertEquals(2, record?.pid)
        assertEquals(1, record?.parentPid)
        assertEquals("child.ck", record?.programPath)
        assertEquals("arg", record?.argument)
        assertEquals("bin", record?.workingDirectory)
        assertEquals(VmProcessState.Runnable, record?.state)
    }

    @Test
    fun registeredRunnableProcessesRotateRoundRobin() {
        val table = VmProcessTable()
        table.registerProcess(pid = 2, parentPid = 1, programPath = "a.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 3, parentPid = 1, programPath = "b.ck", argument = "", workingDirectory = "")

        assertEquals(listOf(2, 3), table.runnableSnapshot())
        assertEquals(2, table.nextRunnablePid())
        assertEquals(listOf(3, 2), table.runnableSnapshot())
        assertEquals(3, table.nextRunnablePid())
        assertEquals(listOf(2, 3), table.runnableSnapshot())
    }

    @Test
    fun nonRunnableStatesRemoveProcessesFromRunnableQueue() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "child.ck", argument = "", workingDirectory = "")

        table.markWaitingEvent(pid = 1, filter = null)
        assertEquals(listOf(2), table.runnableSnapshot())

        table.markSleeping(pid = 2, untilTick = 12)
        assertEquals(emptyList(), table.runnableSnapshot())
        assertNull(table.nextRunnablePid())

        table.markRunnable(pid = 1)
        table.markRunnable(pid = 2)
        assertEquals(listOf(1, 2), table.runnableSnapshot())

        table.markExited(pid = 1, exitCode = 0)
        table.markCrashed(pid = 2, message = "boom")
        assertEquals(emptyList(), table.runnableSnapshot())
    }

    @Test
    fun markRunnableRequeuesExistingProcessOnce() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")

        table.markWaitingIpc(pid = 1, channelId = 7)
        table.markRunnable(pid = 1)
        table.markRunnable(pid = 1)

        assertEquals(listOf(1), table.runnableSnapshot())
        assertEquals(1, table.nextRunnablePid())
        assertEquals(listOf(1), table.runnableSnapshot())
    }

    @Test
    fun processCanMoveThroughWaitingAndExitedStates() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")

        table.markWaitingEvent(pid = 1, filter = "key")
        assertEquals(VmProcessState.WaitingEvent("key"), table.snapshot(1)?.state)

        table.markWaitingIpc(pid = 1, channelId = 7)
        assertEquals(VmProcessState.WaitingIpc(7), table.snapshot(1)?.state)

        table.markWaitingProcess(pid = 1, targetPid = 2)
        assertEquals(VmProcessState.WaitingProcess(2), table.snapshot(1)?.state)

        table.markSleeping(pid = 1, untilTick = 42)
        assertEquals(VmProcessState.Sleeping(42), table.snapshot(1)?.state)

        table.markRunnable(pid = 1)
        assertEquals(VmProcessState.Runnable, table.snapshot(1)?.state)

        table.markCrashed(pid = 1, message = "boom")
        assertEquals(VmProcessState.Crashed("boom"), table.snapshot(1)?.state)

        table.markExited(pid = 1, exitCode = 7)
        assertEquals(VmProcessState.Exited(7), table.snapshot(1)?.state)
    }

    @Test
    fun unknownProcessTransitionsAreIgnored() {
        val table = VmProcessTable()

        table.markWaitingEvent(pid = 99, filter = null)
        table.markWaitingIpc(pid = 99, channelId = 1)
        table.markWaitingProcess(pid = 99, targetPid = 2)
        table.markSleeping(pid = 99, untilTick = 1)
        table.markRunnable(pid = 99)
        table.markCrashed(pid = 99, message = "boom")
        table.markExited(pid = 99, exitCode = 1)

        assertNull(table.snapshot(99))
        assertEquals(emptyList(), table.runnableSnapshot())
        assertNull(table.nextRunnablePid())
    }

    @Test
    fun snapshotListIsSortedByPid() {
        val table = VmProcessTable()
        table.registerProcess(pid = 3, parentPid = 1, programPath = "b.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "a.ck", argument = "", workingDirectory = "")

        assertEquals(listOf(2, 3), table.snapshot().map { it.pid })
        assertIs<VmProcessState.Runnable>(table.snapshot().first().state)
    }
}
