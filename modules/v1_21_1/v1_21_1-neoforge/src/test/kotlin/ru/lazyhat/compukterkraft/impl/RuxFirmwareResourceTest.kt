package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.RuxRuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuxFirmwareResourceTest {
    @Test
    fun bundledRuxTerminalFirmwareResourceExists() {
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
                "firmware/rux-terminal.ruxi must be bundled",
            ).use { it.readBytes() }

        assertTrue(bytes.size > 8, "Rux firmware image should not be empty")
        assertTrue(
            bytes.copyOfRange(0, 4).contentEquals(
                byteArrayOf('R'.code.toByte(), 'U'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte()),
            ),
        )
    }

    @Test
    fun bundledRuxEchoLiveFirmwareResourceExists() {
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-echo-live.ruxi"),
                "firmware/rux-echo-live.ruxi must be bundled",
            ).use { it.readBytes() }

        assertTrue(bytes.size > 8, "Rux echo firmware image should not be empty")
        assertTrue(
            bytes.copyOfRange(0, 4).contentEquals(
                byteArrayOf('R'.code.toByte(), 'U'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte()),
            ),
        )
    }

    @Test
    fun bundledRuxTerminalFirmwareBootsOnNativeComputerWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
                "firmware/rux-terminal.ruxi must be bundled",
            ).use { it.readBytes() }

        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = bytes,
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000_000,
            )

        try {
            NativeVmBindings.runRuxComputerUntilSignal(handle)
            val output = NativeVmBindings.ruxComputerDebugOutput(handle).decodeToString()
            assertTrue(output.contains("RUX"), output)
            assertEquals(3, NativeVmBindings.ruxComputerControl(handle).status)
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }

    @Test
    fun bundledRuxEchoLiveFirmwareEchoesSerialInputWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-echo-live.ruxi"),
                "firmware/rux-echo-live.ruxi must be bundled",
            ).use { it.readBytes() }

        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = bytes,
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000,
            )

        try {
            NativeVmBindings.runRuxComputerUntilSignal(handle)
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
    fun bundledRuxEchoLiveFirmwareEchoesTerminalInputThroughRuntimeDisplayBridgeWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-echo-live.ruxi"),
                "firmware/rux-echo-live.ruxi must be bundled",
            ).use { it.readBytes() }
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 42,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    RuxComputerRuntimeFactory.create(
                        image = bytes,
                        memorySize = 64 * 1024,
                        sliceBudgetNanos = 1_000,
                    )
                },
                stateSink = {},
                displayNetwork = displayNetwork,
            )

        try {
            device.attachDisplaySession(UUID.randomUUID(), containerId = 21, displayId = 1, width = 36, height = 27)
            device.turnOn()
            DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))

            var attempts = 0
            while (displayNetwork.sentFrames.isEmpty() && attempts < 32) {
                device.serverTick()
                attempts += 1
            }

            assertTrue(displayNetwork.sentFrames.isNotEmpty(), "Rux echo firmware should produce a visible frame")
            assertTrue(
                displayNetwork.sentFrames.last().frame.tiles.single().payload.any { it != 0.toByte() },
                "visible frame should contain rendered glyph pixels",
            )
        } finally {
            device.close()
        }
    }

    private class RecordingDisplayNetworkBridge : DisplayNetworkBridge {
        val sentFrames = mutableListOf<SentFrame>()

        override fun isDisplaySessionStillBound(
            playerUuid: UUID,
            containerId: Int,
            deviceId: Int,
            displayId: Int,
        ): Boolean = true

        override fun sendDisplayFrame(
            playerUuid: UUID,
            containerId: Int,
            frame: DisplayFrameDelta,
        ) {
            sentFrames += SentFrame(playerUuid, containerId, frame)
        }
    }

    private data class SentFrame(
        val playerUuid: UUID,
        val containerId: Int,
        val frame: DisplayFrameDelta,
    )
}
