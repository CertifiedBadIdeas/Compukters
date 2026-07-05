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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerTickResult
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16BusTraffic
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerSignal
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerStatsSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16MmioDeviceStats
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16RuntimeDeviceTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun runtimeDeviceUsesK16TypeName() {
        val k16RuntimeDevicePath =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "K16RuntimeDevice.kt",
                ),
            )
        val legacyRuntimeDevicePath =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "RuxRuntimeDevice.kt",
                ),
            )

        assertTrue(k16RuntimeDevicePath.exists())
        assertFalse(legacyRuntimeDevicePath.exists())

        val source = k16RuntimeDevicePath.readText()
        assertTrue(source.contains("class K16RuntimeDevice"))
        assertFalse(source.contains("class RuxRuntimeDevice"))
    }

    @Test
    fun runtimeDeviceDoesNotRenderDisplay0Snapshots() {
        val source =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "K16RuntimeDevice.kt",
                ),
            ).readText()

        assertFalse(source.contains("display0Snapshot"))
        assertFalse(source.contains("flushK16DisplaySnapshot"))
        assertFalse(source.contains("displaySnapshotRefreshDisplayIds"))
    }

    @Test
    fun runtimeDeviceDoesNotRenderSerialOutputAsDisplayFrames() {
        val source =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "K16RuntimeDevice.kt",
                ),
            ).readText()

        assertFalse(source.contains("flushSerialOutput"))
        assertFalse(source.contains("SerialTextDisplayRenderer"))
    }

    @Test
    fun ownsK16EndpointAndTicksItWhilePoweredOn() {
        val endpoint = RecordingK16Endpoint()
        val powerChanges = mutableListOf<Boolean>()
        val device =
            K16RuntimeDevice(
                deviceId = 7,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "K16"),
                endpointFactory = { endpoint },
                stateSink = { powerChanges += it },
            )

        device.turnOn()
        device.pushSerialInput(byteArrayOf('R'.code.toByte()))
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 && device.serialOutputSnapshot().decodeToString() == "R" }

        assertTrue(device.isOn)
        assertEquals(1, endpoint.tickCalls)
        assertEquals(listOf("R"), endpoint.inputs.map { it.decodeToString() })
        assertEquals("R", device.serialOutputSnapshot().decodeToString())
        assertEquals(listOf(true), powerChanges)

        device.shutdown()

        assertFalse(device.isOn)
        assertEquals(1, endpoint.closeCalls)
        assertEquals(listOf(true, false), powerChanges)
    }

    @Test
    fun rebootClosesRunningEndpointAndStartsReplacement() {
        val endpoints = mutableListOf<RecordingK16Endpoint>()
        val powerChanges = mutableListOf<Boolean>()
        val device =
            K16RuntimeDevice(
                deviceId = 31,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "K16"),
                endpointFactory = {
                    RecordingK16Endpoint().also { endpoints += it }
                },
                stateSink = { powerChanges += it },
            )

        device.turnOn()
        assertTrue(device.isOn)
        assertEquals(1, endpoints.size)

        device.reboot()

        assertTrue(device.isOn)
        assertEquals(2, endpoints.size)
        assertEquals(1, endpoints[0].closeCalls)
        assertEquals(0, endpoints[1].closeCalls)
        assertEquals(listOf(true, false, true), powerChanges)
    }

    @Test
    fun serverTickDoesNotExecuteK16EndpointOnCallerThread() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 19,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )
        val callerThreadId = Thread.currentThread().id

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }

        assertFalse(
            endpoint.tickThreadIds.contains(callerThreadId),
            "K16 endpoint execution must not run on the Minecraft server tick thread.",
        )
    }

    @Test
    fun overloadedK16EndpointDoesNotBlockServerTick() {
        val tickEntered = CountDownLatch(1)
        val releaseTick = CountDownLatch(1)
        val serverTickReturned = CountDownLatch(1)
        val endpoint =
            BlockingFirstTickK16Endpoint(
                tickEntered = tickEntered,
                releaseTick = releaseTick,
            )
        val device =
            K16RuntimeDevice(
                deviceId = 20,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        val callerThread =
            Thread(
                {
                    device.serverTick()
                    serverTickReturned.countDown()
                },
                "test-server-tick-caller",
            )

        callerThread.start()

        assertTrue(
            serverTickReturned.await(200, TimeUnit.MILLISECONDS),
            "K16 serverTick must return while worker execution is still overloaded.",
        )
        assertTrue(
            tickEntered.await(2, TimeUnit.SECONDS),
            "Expected worker to enter the overloaded K16 endpoint tick.",
        )

        releaseTick.countDown()
        callerThread.join(2_000)
        waitUntil { endpoint.tickCalls == 1 }
        device.shutdown()
    }

    @Test
    fun coalescesExecutionPermitWhileEndpointWorkerIsBusy() {
        val tickEntered = CountDownLatch(1)
        val releaseTick = CountDownLatch(1)
        val endpoint =
            BlockingFirstTickK16Endpoint(
                tickEntered = tickEntered,
                releaseTick = releaseTick,
            )
        val device =
            K16RuntimeDevice(
                deviceId = 22,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        device.serverTick()
        assertTrue(
            tickEntered.await(2, TimeUnit.SECONDS),
            "Expected worker to enter the first K16 endpoint tick.",
        )

        repeat(3) {
            device.serverTick()
        }

        releaseTick.countDown()

        waitUntil { endpoint.tickCalls >= 2 && endpoint.advancedGameTicks.sum() == 4L }

        assertEquals(2, endpoint.tickCalls)
        assertEquals(listOf(1L, 3L), endpoint.advancedGameTicks)
        device.shutdown()
    }

    @Test
    fun keyboardInputWakesEndpointAfterWaitSignalWithoutAdvancingGameTicks() {
        val endpoint = RecordingK16Endpoint()
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Wait,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
            )
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Yield,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
            )
        val device =
            K16RuntimeDevice(
                deviceId = 23,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }

        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        waitUntil { endpoint.tickCalls == 2 && endpoint.keyboardChars.isNotEmpty() }

        assertEquals(listOf(1L), endpoint.advancedGameTicks)
        assertEquals(listOf('R'.code.toByte()), endpoint.keyboardChars)
        device.shutdown()
    }

    @Test
    fun endpointWorkerUsesDetailedTickResultForRunnableYieldSignal() {
        val endpoint = RecordingK16Endpoint()
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Wait,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
                yieldSignals = 3,
            )
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 24,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                metricsCollector = metrics,
            )

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 && metrics.snapshot().vm.k16RunSlices == 1L }

        val snapshot = metrics.snapshot()
        assertEquals(1, endpoint.tickUntilSignalCalls)
        assertEquals(1, snapshot.vm.k16RunSlices)
        assertEquals(1, snapshot.vm.k16RunWaitSignals)
        assertEquals(3, snapshot.vm.k16RunYieldSignals)
        device.shutdown()
    }

    @Test
    fun recordsK16WaitEntriesAndWakeReasons() {
        val endpoint = RecordingK16Endpoint()
        val metrics = RecordingRuntimeMetricsCollector()
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Wait,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
            )
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Wait,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
            )
        endpoint.tickResults +=
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Yield,
                control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0),
            )
        val device =
            K16RuntimeDevice(
                deviceId = 25,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                metricsCollector = metrics,
            )

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }
        device.serverTick()
        waitUntil { endpoint.tickCalls == 2 }
        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        waitUntil { endpoint.tickCalls == 3 && endpoint.keyboardChars.isNotEmpty() }

        val snapshot = metrics.snapshot()
        assertEquals(3, snapshot.vm.k16RunSlices)
        assertEquals(2, snapshot.vm.k16RunWaitSignals)
        assertEquals(1, snapshot.vm.k16RunYieldSignals)
        assertEquals(0, snapshot.vm.k16RunHaltSignals)
        assertEquals(0, snapshot.vm.k16RunPauseSignals)
        assertEquals(2, snapshot.vm.k16WaitEntries)
        assertEquals(1, snapshot.vm.k16WaitTimerWakeups)
        assertEquals(1, snapshot.vm.k16WaitInputWakeups)
        assertEquals(0, snapshot.vm.k16WaitIdleSkips)
        device.shutdown()
    }

    @Test
    fun mapsPasteEventsToKeyboard0WithoutConsumingCallerBuffer() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 8,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )
        val paste = ByteBuffer.wrap("Hi".encodeToByteArray())

        device.turnOn()
        device.queueEvent("paste", arrayOf(paste))
        waitUntil { endpoint.keyboardPasteBytes.isNotEmpty() }

        assertEquals("Hi", endpoint.keyboardPasteBytes.single().decodeToString())
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
        assertEquals(0, paste.position())
    }

    @Test
    fun doesNotSendSerialOutputFrameToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 9,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 17, displayId = 1, width = 36, height = 27)
        device.turnOn()
        endpoint.injectOutput("K16!")
        device.serverTick()
        waitUntil { device.serialOutputSnapshot().decodeToString() == "K16!" }
        device.serverTick()

        assertEquals(0, displayNetwork.sentFrames.size)
    }

    @Test
    fun sendsFramebufferFramesToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 21,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 7,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 1,
                            payload = byteArrayOf(0xF8.toByte(), 0x00, 0x07, 0xE0.toByte()),
                        ),
                    ),
            )

        device.attachDisplaySession(playerUuid, containerId = 22, displayId = 1, width = 320, height = 200)
        device.turnOn()
        endpoint.enqueueFramebufferFrames(encodeDisplayFrames(listOf(frame)))
        device.serverTick()
        waitUntil {
            device.serverTick()
            displayNetwork.sentFrames.size == 1
        }

        assertEquals(1, displayNetwork.sentFrames.size)
        assertEquals(frame, displayNetwork.sentFrames.single().frame)
    }

    @Test
    fun coalescesFramebufferFramesBeforeSendingToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 22,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
                metricsCollector = metrics,
            )
        val playerUuid = UUID.randomUUID()
        val oldTile =
            DisplayTile(
                tileX = 0,
                tileY = 0,
                x = 0,
                y = 0,
                width = 1,
                height = 1,
                payload = byteArrayOf(0xF8.toByte(), 0x00),
            )
        val replacementTile =
            DisplayTile(
                tileX = 0,
                tileY = 0,
                x = 0,
                y = 0,
                width = 1,
                height = 1,
                payload = byteArrayOf(0x07, 0xE0.toByte()),
            )
        val secondTile =
            DisplayTile(
                tileX = 1,
                tileY = 0,
                x = 16,
                y = 0,
                width = 1,
                height = 1,
                payload = byteArrayOf(0x00, 0x1F),
            )
        val firstFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 7,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(oldTile),
            )
        val secondFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 8,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(replacementTile, secondTile),
            )

        device.attachDisplaySession(playerUuid, containerId = 23, displayId = 1, width = 320, height = 200)
        device.turnOn()
        endpoint.enqueueFramebufferFrames(encodeDisplayFrames(listOf(firstFrame, secondFrame)))
        device.serverTick()
        waitUntil {
            device.serverTick()
            displayNetwork.sentFrames.isNotEmpty()
        }

        assertEquals(1, displayNetwork.sentFrames.size)
        assertEquals(
            DisplayFrameDelta(
                displayId = 1,
                sequence = 8,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(replacementTile, secondTile),
            ),
            displayNetwork.sentFrames.single().frame,
        )
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.vm.k16GpuFramesDecoded)
        assertEquals(1, snapshot.vm.k16DisplayFramesSent)
        assertEquals(2, snapshot.vm.k16DisplayTilesSent)
        assertEquals(4, snapshot.vm.k16DisplayPayloadBytesSent)
    }

    @Test
    fun coalescesOperationOnlyDisplayFramesBeforeSendingToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 22,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
                metricsCollector = metrics,
            )
        val playerUuid = UUID.randomUUID()
        val firstFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 7,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = emptyList(),
                operations =
                    listOf(
                        DisplayFrameOperation.FillRect(
                            x = 0,
                            y = 192,
                            width = 320,
                            height = 8,
                            rgb565 = 0,
                        ),
                    ),
            )
        val secondFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 8,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = emptyList(),
                operations =
                    listOf(
                        DisplayFrameOperation.CopyRect(
                            srcX = 0,
                            srcY = 8,
                            width = 320,
                            height = 192,
                            dstX = 0,
                            dstY = 0,
                        ),
                    ),
            )

        device.attachDisplaySession(playerUuid, containerId = 23, displayId = 1, width = 320, height = 200)
        device.turnOn()
        endpoint.enqueueFramebufferFrames(encodeDisplayFrames(listOf(firstFrame, secondFrame)))
        device.serverTick()
        waitUntil {
            device.serverTick()
            displayNetwork.sentFrames.isNotEmpty()
        }

        assertEquals(1, displayNetwork.sentFrames.size)
        assertEquals(
            DisplayFrameDelta(
                displayId = 1,
                sequence = 8,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = emptyList(),
                operations = firstFrame.operations + secondFrame.operations,
            ),
            displayNetwork.sentFrames.single().frame,
        )
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.vm.k16DisplayFramesSent)
        assertEquals(0, snapshot.vm.k16DisplayTilesSent)
        assertEquals(0, snapshot.vm.k16DisplayPayloadBytesSent)
        assertEquals(2, snapshot.vm.k16DisplayOperationsSent)
    }

    @Test
    fun recordsK16OutputRefreshSerialAndGpuFrameCounters() {
        val endpoint = RecordingK16Endpoint()
        val metrics = RecordingRuntimeMetricsCollector()
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 11,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 1,
                            height = 1,
                            payload = byteArrayOf(0xF8.toByte(), 0x00),
                        ),
                    ),
            )
        val encodedFrame = encodeDisplayFrames(listOf(frame))
        val device =
            K16RuntimeDevice(
                deviceId = 26,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                metricsCollector = metrics,
            )

        device.turnOn()
        endpoint.injectOutput("K16!")
        endpoint.enqueueFramebufferFrames(encodedFrame)
        device.serverTick()
        waitUntil { metrics.snapshot().vm.k16GpuFrameBatches == 1L }

        val snapshot = metrics.snapshot()
        assertTrue(snapshot.vm.k16OutputRefreshes >= 2)
        assertTrue(snapshot.vm.k16OutputRefreshNanos >= 0)
        assertTrue(snapshot.vm.k16SerialOutputSnapshots >= 1)
        assertTrue(snapshot.vm.k16SerialOutputSnapshotBytes >= 4)
        assertEquals(1, snapshot.vm.k16GpuFrameBatches)
        assertEquals(encodedFrame.size.toLong(), snapshot.vm.k16GpuFrameBytes)
        assertEquals(1, snapshot.vm.k16GpuFramesDecoded)
        device.shutdown()
    }

    @Test
    fun recordsK16StatsSnapshotDuringOutputRefresh() {
        val endpoint = RecordingK16Endpoint()
        endpoint.statsSnapshot =
            NativeK16ComputerStatsSnapshot(
                ram = NativeK16BusTraffic(loads = 10, stores = 11, bytesRead = 12, bytesWritten = 13),
                mmio = NativeK16BusTraffic(loads = 20, stores = 21, bytesRead = 22, bytesWritten = 23),
                devices =
                    listOf(
                        NativeK16MmioDeviceStats(
                            deviceId = 3,
                            base = 0x2000,
                            size = 64,
                            traffic = NativeK16BusTraffic(loads = 4, stores = 5, bytesRead = 6, bytesWritten = 7),
                        ),
                    ),
            )
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 27,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                metricsCollector = metrics,
            )

        device.turnOn()
        waitUntil { metrics.snapshot().k16.ram.loads == 10L }

        val snapshot = metrics.snapshot()
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 10, stores = 11, bytesRead = 12, bytesWritten = 13), snapshot.k16.ram)
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 20, stores = 21, bytesRead = 22, bytesWritten = 23), snapshot.k16.mmio)
        assertEquals(1, snapshot.k16.devices.size)
        assertEquals(3, snapshot.k16.devices.single().deviceId)
        assertEquals(0x2000, snapshot.k16.devices.single().base)
        assertEquals(64, snapshot.k16.devices.single().size)
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 4, stores = 5, bytesRead = 6, bytesWritten = 7), snapshot.k16.devices.single().traffic)
        device.shutdown()
    }

    @Test
    fun skipsK16StatsSnapshotWhenCollectorIsNoOp() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 28,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }

        assertEquals(0, endpoint.statsSnapshotCalls)
        device.shutdown()
    }

    @Test
    fun keepsFramebufferFramesUntilDisplaySessionAttaches() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 31,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 1,
                            height = 1,
                            payload = byteArrayOf(0xF8.toByte(), 0x00),
                        ),
                    ),
            )

        device.turnOn()
        endpoint.enqueueFramebufferFrames(encodeDisplayFrames(listOf(frame)))
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }

        assertEquals(0, displayNetwork.sentFrames.size)

        device.attachDisplaySession(playerUuid, containerId = 32, displayId = 1, width = 320, height = 200)
        device.serverTick()

        assertEquals(1, displayNetwork.sentFrames.size)
        assertEquals(frame, displayNetwork.sentFrames.single().frame)
    }

    @Test
    fun stopsTickingEndpointAfterTerminalControlStatus() {
        val endpoint = RecordingK16Endpoint()
        endpoint.control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_HALTED, exitCode = 0, panicCode = 2)
        val device =
            K16RuntimeDevice(
                deviceId = 18,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }
        device.serverTick()

        assertEquals(1, endpoint.tickCalls)
    }

    @Test
    fun dispatchesCharacterInputThroughKeyboard0Endpoint() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 10,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 18, displayId = 1, width = 36, height = 27)
        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        waitUntil { endpoint.keyboardChars.isNotEmpty() }

        assertEquals(listOf('R'.code.toByte()), endpoint.keyboardChars)
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
        assertEquals(0, displayNetwork.sentFrames.size)
    }

    @Test
    fun dispatchesPasteInputThroughKeyboard0Endpoint() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 11,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )

        device.attachDisplaySession(UUID.randomUUID(), containerId = 19, displayId = 1, width = 36, height = 27)
        device.turnOn()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("K16".encodeToByteArray())))
        waitUntil { endpoint.keyboardPasteBytes.isNotEmpty() }

        assertEquals(listOf("K16"), endpoint.keyboardPasteBytes.map { it.decodeToString() })
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
        assertEquals(0, displayNetwork.sentFrames.size)
    }

    @Test
    fun recordsK16TextInputCountersForCharactersAndPaste() {
        val endpoint = RecordingK16Endpoint()
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 31,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                metricsCollector = metrics,
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("ux".encodeToByteArray())))
        waitUntil {
            endpoint.keyboardChars.size == 1 &&
                endpoint.keyboardPasteBytes.size == 1
        }

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.vm.k16TextInputEvents)
        assertEquals(3, snapshot.vm.k16TextInputBytes)
        assertTrue(snapshot.vm.k16TextInputNanos >= 0)
        device.shutdown()
    }

    @Test
    fun dispatchesKeyDownThroughKeyboard0Endpoint() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 12,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_ENTER, repeat = false))
        waitUntil { endpoint.keyboardKeyDowns.isNotEmpty() }

        assertEquals(listOf(KeyboardKeyDown(KeyCodes.KEY_ENTER, repeat = false)), endpoint.keyboardKeyDowns)
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
    }

    @Test
    fun dispatchesKeyUpThroughKeyboard0Endpoint() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 13,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Up(KeyCodes.KEY_BACKSPACE))
        waitUntil { endpoint.keyboardKeyUps.isNotEmpty() }

        assertEquals(listOf(KeyCodes.KEY_BACKSPACE), endpoint.keyboardKeyUps)
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
    }

    @Test
    fun exposesRunningEndpointSnapshotForPersistence() {
        val endpoint = RecordingK16Endpoint()
        endpoint.runtimeSnapshot = byteArrayOf(0x52, 0x55, 0x58)
        val device =
            K16RuntimeDevice(
                deviceId = 16,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        assertEquals(null, device.snapshotRuntimeState())

        device.turnOn()

        assertContentEquals(byteArrayOf(0x52, 0x55, 0x58), device.snapshotRuntimeState())

        device.shutdown()

        assertEquals(null, device.snapshotRuntimeState())
    }

    @Test
    fun failedEndpointStartupRecordsRuntimeFailureWithoutPoweringOn() {
        var endpointFactoryCalls = 0
        val powerChanges = mutableListOf<Boolean>()
        val device =
            K16RuntimeDevice(
                deviceId = 17,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    endpointFactoryCalls += 1
                    error("unsupported snapshot version")
                },
                stateSink = { powerChanges += it },
            )

        device.turnOn()

        assertFalse(device.isOn)
        assertEquals(1, endpointFactoryCalls)
        assertEquals(listOf(false), powerChanges)
        assertEquals("unsupported snapshot version", device.runtimeFailureMessage)
        assertEquals(null, device.snapshotRuntimeState())
    }

    @Test
    fun failedEndpointSnapshotRecordsRuntimeFailureWithoutBlockingCaller() {
        val endpoint = RecordingK16Endpoint()
        endpoint.snapshotFailure = IllegalStateException("snapshot encoder failed")
        val device =
            K16RuntimeDevice(
                deviceId = 18,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()

        val snapshot =
            CompletableFuture.supplyAsync {
                device.snapshotRuntimeState()
            }.get(2, TimeUnit.SECONDS)

        assertEquals(null, snapshot)
        assertEquals("snapshot encoder failed", device.runtimeFailureMessage)
    }

    private fun waitUntil(
        timeoutMillis: Long = 2_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(predicate(), "Condition was not met within ${timeoutMillis}ms.")
    }

    private open class RecordingK16Endpoint : K16ComputerEndpoint {
        val inputs: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val keyboardKeyDowns: MutableList<KeyboardKeyDown> = Collections.synchronizedList(mutableListOf())
        val keyboardKeyUps: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val keyboardChars: MutableList<Byte> = Collections.synchronizedList(mutableListOf())
        val keyboardPasteBytes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile
        var tickCalls = 0
            private set
        @Volatile
        var tickUntilSignalCalls = 0
            private set
        val advancedGameTicks: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val tickThreadIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val tickResults = ArrayDeque<K16ComputerTickResult>()
        @Volatile
        var closeCalls = 0
            private set
        @Volatile
        var statsSnapshotCalls = 0
            private set
        var runtimeSnapshot: ByteArray = ByteArray(0)
        var snapshotFailure: RuntimeException? = null
        var control: NativeK16ComputerControl = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0)
        var statsSnapshot: NativeK16ComputerStatsSnapshot = NativeK16ComputerStatsSnapshot()
        private val injectedOutput = StringBuilder()
        private val gpuFrameBatches = ArrayDeque<ByteArray>()

        override fun pushInput(bytes: ByteArray) {
            inputs += bytes.copyOf()
        }

        override fun pushKeyboardKeyDown(
            key: Int,
            repeat: Boolean,
            modifiers: Int,
        ) {
            keyboardKeyDowns += KeyboardKeyDown(key, repeat, modifiers)
        }

        override fun pushKeyboardKeyUp(
            key: Int,
            modifiers: Int,
        ) {
            keyboardKeyUps += key
        }

        override fun pushKeyboardChar(value: Byte) {
            keyboardChars += value
        }

        override fun pushKeyboardPasteBytes(bytes: ByteArray) {
            keyboardPasteBytes += bytes.copyOf()
        }

        override fun advanceGameTicks(ticks: Long) {
            advancedGameTicks += ticks
        }

        override open fun tick(maxTurns: Int): NativeK16ComputerControl {
            beforeTick()
            return nextTickResult().control
        }

        override fun tickUntilSignal(maxTurns: Int): K16ComputerTickResult {
            tickUntilSignalCalls += 1
            beforeTick()
            return nextTickResult()
        }

        protected open fun beforeTick() {
            tickCalls += 1
            tickThreadIds += Thread.currentThread().id
        }

        private fun nextTickResult(): K16ComputerTickResult =
            if (tickResults.isEmpty()) {
                K16ComputerTickResult(
                    signal = NativeK16ComputerSignal.Pause,
                    control = control,
                )
            } else {
                tickResults.removeFirst()
            }

        override fun outputSnapshot(): ByteArray =
            (inputs.fold(ByteArray(0)) { acc, bytes -> acc + bytes }.decodeToString() + injectedOutput)
                .encodeToByteArray()

        override fun drainGpu0Frames(): ByteArray =
            if (gpuFrameBatches.isEmpty()) {
                ByteArray(0)
            } else {
                gpuFrameBatches.removeFirst()
            }

        override fun clearOutput() = Unit

        override fun machineSnapshot(): ByteArray {
            snapshotFailure?.let { throw it }
            return runtimeSnapshot.copyOf()
        }

        override fun statsSnapshot(): NativeK16ComputerStatsSnapshot {
            statsSnapshotCalls += 1
            return statsSnapshot
        }

        override fun close() {
            closeCalls += 1
        }

        fun injectOutput(text: String) {
            injectedOutput.append(text)
        }

        fun enqueueFramebufferFrames(bytes: ByteArray) {
            gpuFrameBatches += bytes.copyOf()
        }
    }

    private data class KeyboardKeyDown(
        val key: Int,
        val repeat: Boolean,
        val modifiers: Int = 0,
    )

    private fun encodeDisplayFrames(frames: List<DisplayFrameDelta>): ByteArray {
        val payloadBytes = frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }
        val operationBytes =
            frames.sumOf { frame ->
                frame.operations.sumOf { operation ->
                    when (operation) {
                        is DisplayFrameOperation.FillRect -> 1 + 5 * 4
                        is DisplayFrameOperation.CopyRect -> 1 + 6 * 4
                    }
                }
            }
        val buffer =
            ByteBuffer
                .allocate(4 + frames.size * 35 + frames.sumOf { it.tiles.size * 28 } + payloadBytes + operationBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(frames.size)
        for (frame in frames) {
            buffer.putInt(frame.displayId)
            buffer.putLong(frame.sequence)
            buffer.putInt(frame.width)
            buffer.putInt(frame.height)
            buffer.put(
                when (frame.pixelFormat) {
                    DisplayPixelFormat.RGB565 -> 0
                },
            )
            buffer.put(if (frame.fullRefresh) 1 else 0)
            buffer.putInt(frame.tiles.size)
            for (tile in frame.tiles) {
                buffer.putInt(tile.tileX)
                buffer.putInt(tile.tileY)
                buffer.putInt(tile.x)
                buffer.putInt(tile.y)
                buffer.putInt(tile.width)
                buffer.putInt(tile.height)
                buffer.putInt(tile.payload.size)
                buffer.put(tile.payload)
            }
            buffer.putInt(frame.operations.size)
            for (operation in frame.operations) {
                when (operation) {
                    is DisplayFrameOperation.FillRect -> {
                        buffer.put(1)
                        buffer.putInt(operation.x)
                        buffer.putInt(operation.y)
                        buffer.putInt(operation.width)
                        buffer.putInt(operation.height)
                        buffer.putInt(operation.rgb565)
                    }
                    is DisplayFrameOperation.CopyRect -> {
                        buffer.put(2)
                        buffer.putInt(operation.srcX)
                        buffer.putInt(operation.srcY)
                        buffer.putInt(operation.width)
                        buffer.putInt(operation.height)
                        buffer.putInt(operation.dstX)
                        buffer.putInt(operation.dstY)
                    }
                }
            }
        }
        return buffer.array()
    }

    private class BlockingFirstTickK16Endpoint(
        private val tickEntered: CountDownLatch,
        private val releaseTick: CountDownLatch,
    ) : RecordingK16Endpoint() {
        private var firstTick = true

        override fun beforeTick() {
            if (firstTick) {
                firstTick = false
                tickEntered.countDown()
                releaseTick.await(2, TimeUnit.SECONDS)
            }
            super.beforeTick()
        }
    }

    private data class SentFrame(
        val playerUuid: UUID,
        val containerId: Int,
        val frame: DisplayFrameDelta,
    )

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
}
