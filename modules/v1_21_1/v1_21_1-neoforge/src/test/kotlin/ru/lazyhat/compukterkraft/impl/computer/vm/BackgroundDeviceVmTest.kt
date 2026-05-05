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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundDeviceVmTest {
    private class StaticFirmwareLoader(
        private val source: String,
    ) : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource = LoadedFirmwareProgramSource(path, source)
    }

    private class ClasspathFirmwareLoader : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource {
            val source =
                BackgroundDeviceVmTest::class.java.classLoader
                    .getResourceAsStream("firmware/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("firmware/$path missing from classpath")
            return LoadedFirmwareProgramSource(path, source)
        }
    }

    private fun runVmTicks(
        vm: BackgroundDeviceVm,
        ticks: Int = 16,
        hostCallDispatcher: HostCallDispatcher? = null,
    ) = runBlocking {
        repeat(ticks) { tick ->
            vm.requestSlice(tick.toLong())
            hostCallDispatcher?.let { dispatcher ->
                val results = vm.drainHostCalls().map(dispatcher::dispatch)
                if (results.isNotEmpty()) {
                    vm.deliverHostResults(results)
                }
            }
            kotlinx.coroutines.delay(10)
        }
    }

    private fun ScreenBufferSnapshot.visibleText(): String =
        (0 until height)
            .joinToString("\n") { y ->
                (0 until width).joinToString("") { x -> charAt(x, y).toString() }.trimEnd()
            }.trim()

    private fun firmwareTestProfile(): DeviceProfile =
        DeviceProfile(
            id = "rom-terminal-test",
            displayName = "ROM Terminal Test",
            cpuBudgetNanosPerSlice = 5_000_000,
            maxEventQueueSize = 64,
            terminalWidth = 80,
            terminalHeight = 16,
            colorTerminal = true,
            allowedCapabilities =
                setOf(
                    DeviceCapability.TERMINAL,
                    DeviceCapability.DISPLAY,
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.IPC,
                ),
            resources =
                DeviceResources(
                    cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 5_000_000),
                    memory = DeviceMemoryResources(),
                    storage = DeviceStorageResources(programRomBytes = 128 * 1024, diskBytes = 1024 * 1024),
                    queues = DeviceQueueResources(eventQueueSlots = 64, hostCallQueueSlots = 64),
                ),
        )

    @Test
    fun bundledFirmwareBootsRomTerminalAndRendersShellOutput() {
        val root = createTempDirectory("compukterkraft-rom-terminal")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 80, hostCallDispatcher = HostCallDispatcher(1, workspace))

            val frames = vm.drainDisplayFrames()
            val rendered =
                assertNotNull(
                    frames.lastOrNull { frame ->
                        frame.tiles.any { tile ->
                            tile.payload.containsRgb565(0x0000) &&
                                tile.payload.containsRgb565(0x07E0)
                        }
                    },
                    "terminal frame missing; frames=${frames.size} state=${vm.snapshot().state} text=${vm.forceScreenSnapshot().visibleText()} logs=$logs",
                )
            assertTrue(rendered.tiles.isNotEmpty(), "terminal frame missing; frames=${frames.size} state=${vm.snapshot().state} logs=$logs")
            val text = vm.forceScreenSnapshot().visibleText()
            assertTrue(text.contains("Compukter Kraft shell"), text)
            assertTrue(text.contains("/ >"), text)
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledRomTerminalEchoesTypedInputAndSubmitsCommandsToShell() {
        val root = createTempDirectory("compukterkraft-rom-terminal-input")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val dispatcher = HostCallDispatcher(1, workspace)
            runVmTicks(vm, ticks = 80, hostCallDispatcher = dispatcher)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            runVmTicks(vm, ticks = 20, hostCallDispatcher = dispatcher)
            val typedText = vm.forceScreenSnapshot().visibleText()
            assertTrue(typedText.contains("/ > help"), typedText)

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runVmTicks(vm, ticks = 40, hostCallDispatcher = dispatcher)
            val submittedText = vm.forceScreenSnapshot().visibleText()
            assertTrue(submittedText.contains("Builtins: help cd pwd reboot shutdown"), submittedText)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledRomTerminalHandlesBackspaceWithoutFramebufferRedrawPerKeypress() {
        val root = createTempDirectory("compukterkraft-rom-terminal-backspace")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val dispatcher = HostCallDispatcher(1, workspace)
            runVmTicks(vm, ticks = 80, hostCallDispatcher = dispatcher)
            vm.drainDisplayFrames()

            "helx".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_BACKSPACE, false)))
            vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf('p'.code.toByte()))))
            runVmTicks(vm, ticks = 30, hostCallDispatcher = dispatcher)

            val typedText = vm.forceScreenSnapshot().visibleText()
            assertTrue(typedText.contains("/ > help"), typedText)
            assertTrue(vm.drainDisplayFrames().isEmpty(), "typing should not redraw the framebuffer every keypress")

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runVmTicks(vm, ticks = 40, hostCallDispatcher = dispatcher)
            val submittedText = vm.forceScreenSnapshot().visibleText()
            assertTrue(submittedText.contains("Builtins: help cd pwd reboot shutdown"), submittedText)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ByteArray.containsRgb565(value: Int): Boolean {
        val hi = (value ushr 8).toByte()
        val lo = value.toByte()
        var index = 0
        while (index + 1 < size) {
            if (this[index] == hi && this[index + 1] == lo) return true
            index += 2
        }
        return false
    }

    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "tiny-rom",
                    displayName = "Tiny ROM",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    terminalWidth = 16,
                    terminalHeight = 8,
                    colorTerminal = true,
                    allowedCapabilities = setOf(DeviceCapability.TERMINAL, DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 1, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
                )

            vm.boot()

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("ROM limit") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
