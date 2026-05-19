package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.RuxRuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.input.KeyCodes
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
    fun bundledRuxLaptopFirmwareResourceExists() {
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-laptop.ruxi"),
                "firmware/rux-laptop.ruxi must be bundled",
            ).use { it.readBytes() }

        assertTrue(bytes.size > 8, "Rux laptop firmware image should not be empty")
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
            assertEquals("RUX READY\n", NativeVmBindings.drainRuxComputerDebugOutput(handle).decodeToString())
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
    fun bundledRuxEchoLiveFirmwarePrintsBootBannerWhenLibraryIsConfigured() {
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

            assertEquals("RUX READY\n", NativeVmBindings.drainRuxComputerDebugOutput(handle).decodeToString())
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }

    @Test
    fun bundledRuxEchoLiveFirmwareKeepsDebugSerialOutputSeparateFromDisplay0WhenLibraryIsConfigured() {
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
            assertTrue(device.serialOutputSnapshot().decodeToString().contains("RUX READY\nR"))
            assertTrue(displayNetwork.sentFrames.last().frame.tiles.single().payload.all { it == 0.toByte() })
        } finally {
            device.close()
        }
    }

    @Test
    fun bundledRuxEchoLiveFirmwareEchoesEnterAndBackspaceKeysThroughRuntimeDeviceWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-echo-live.ruxi"),
                "firmware/rux-echo-live.ruxi must be bundled",
            ).use { it.readBytes() }
        val device =
            RuxRuntimeDevice(
                deviceId = 43,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    RuxComputerRuntimeFactory.create(
                        image = bytes,
                        memorySize = 64 * 1024,
                        sliceBudgetNanos = 1_000,
                    )
                },
                stateSink = {},
            )

        try {
            device.turnOn()
            DeviceEvents.dispatch(device, KeyInputEvent.Character('A'.code.toByte()))
            DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_BACKSPACE, repeat = false))
            DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_ENTER, repeat = false))

            val expected = "RUX READY\nA\b\n"
            var attempts = 0
            while (device.serialOutputSnapshot().decodeToString() != expected && attempts < 32) {
                device.serverTick()
                attempts += 1
            }

            assertEquals(expected, device.serialOutputSnapshot().decodeToString())
        } finally {
            device.close()
        }
    }

    @Test
    fun bundledRuxLaptopFirmwareUpdatesDisplayAfterInputThroughRuntimeDeviceWhenLibraryIsConfigured() {
        System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-laptop.ruxi"),
                "firmware/rux-laptop.ruxi must be bundled",
            ).use { it.readBytes() }
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 44,
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
            device.attachDisplaySession(UUID.randomUUID(), containerId = 22, displayId = 1, width = 400, height = 200)
            device.turnOn()

            var attempts = 0
            while (displayNetwork.sentFrames.isEmpty() && attempts < 32) {
                device.serverTick()
                attempts += 1
            }
            assertTrue(displayNetwork.sentFrames.isNotEmpty(), "Rux laptop firmware should draw its boot prompt")
            val bootFrame = displayNetwork.sentFrames.last().frame

            DeviceEvents.dispatch(device, KeyInputEvent.Character('A'.code.toByte()))
            DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_BACKSPACE, repeat = false))
            DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_ENTER, repeat = false))

            while (displayNetwork.sentFrames.last().frame.sequence <= bootFrame.sequence && attempts < 64) {
                device.serverTick()
                attempts += 1
            }

            val inputFrame = displayNetwork.sentFrames.last().frame
            assertTrue(inputFrame.sequence > bootFrame.sequence, "Laptop input should advance display sequence")
            assertTrue(
                inputFrame.tiles.single().payload.any { it != 0.toByte() },
                "Laptop display frame should contain rendered glyph pixels",
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
