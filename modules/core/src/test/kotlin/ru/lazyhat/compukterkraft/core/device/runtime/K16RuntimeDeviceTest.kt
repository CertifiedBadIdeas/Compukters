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
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRetainedDisplayPayload
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerTickResult
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16BusTraffic
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerSignal
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerStatsSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16MmioDeviceStats
import java.nio.ByteBuffer
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
    fun productionDisplayPathKeepsNativeBatchesOpaque() {
        val runtimeSource =
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
        val bridgeSource =
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
                    "ports",
                    "DisplayNetworkBridge.kt",
                ),
            ).readText()

        assertFalse(runtimeSource.contains("decodeFrames("))
        assertFalse(runtimeSource.contains("DecodedPendingDisplayBatch"))
        assertFalse(runtimeSource.contains("coalesceDisplayFrames"))
        assertFalse(runtimeSource.contains("RetainedDisplayProtocol"))
        assertFalse(runtimeSource.contains("\"KDSP\""))
        assertTrue(runtimeSource.contains("sendRetainedDisplayPayload"))
        assertFalse(bridgeSource.contains("NativeDisplayFrameCodec"))
        assertFalse(bridgeSource.contains("decodeFrames("))
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
    fun ownsOneNativeRetainedViewerPerPlayerAndRoutesBytesByPlayer() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 73,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "K16"),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000073")
        val secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000074")
        val callerThreadId = Thread.currentThread().id
        endpoint.retainedPayloads += NativeRetainedDisplayPayload(1L, byteArrayOf(0x4b, 0x44, 0x53, 0x50))
        displayNetwork.authorizedPlayers += firstPlayer
        displayNetwork.authorizedPlayers += secondPlayer

        device.turnOn()

        assertTrue(device.attachRetainedDisplayViewer(firstPlayer))
        assertTrue(device.attachRetainedDisplayViewer(firstPlayer))
        assertTrue(device.attachRetainedDisplayViewer(secondPlayer))
        assertEquals(listOf(1L to 73, 2L to 73), endpoint.retainedViewerAttaches)
        assertFalse(endpoint.retainedCallThreadIds.contains(callerThreadId))
        assertEquals(
            listOf(SentNativePayload(firstPlayer, 73, byteArrayOf(0x4b, 0x44, 0x53, 0x50))),
            displayNetwork.sentRetainedPayloads,
        )

        val serverbound = byteArrayOf(1, 2, 3, 4)
        assertFalse(device.acceptRetainedDisplayServerbound(UUID.randomUUID(), serverbound))
        endpoint.retainedServerboundOutcome = 2
        endpoint.retainedPayloads += NativeRetainedDisplayPayload(1L, byteArrayOf(9, 8, 7))
        assertTrue(device.acceptRetainedDisplayServerbound(firstPlayer, serverbound))
        assertEquals(listOf(1L to serverbound.toList()), endpoint.retainedServerbound)
        assertEquals(
            SentNativePayload(firstPlayer, 73, byteArrayOf(9, 8, 7)),
            displayNetwork.sentRetainedPayloads.last(),
        )

        endpoint.retainedPayloads += NativeRetainedDisplayPayload(1L, byteArrayOf(6, 5, 4))
        val drainCallsBeforeTick = endpoint.retainedPayloadBatchDrainCalls
        device.serverTick()
        waitUntil { endpoint.retainedPayloadBatchDrainCalls > drainCallsBeforeTick }
        device.serverTick()
        assertEquals(
            SentNativePayload(firstPlayer, 73, byteArrayOf(6, 5, 4)),
            displayNetwork.sentRetainedPayloads.last(),
        )

        assertTrue(device.detachRetainedDisplayViewer(firstPlayer))
        assertFalse(device.detachRetainedDisplayViewer(firstPlayer))
        assertEquals(listOf(1L), endpoint.retainedViewerDetaches)
        device.shutdown()
        assertEquals(listOf(1L, 2L), endpoint.retainedViewerDetaches)
    }

    @Test
    fun serverTickPrunesLostAuthorizationAndTimedOutResyncReattaches() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 73,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "K16"),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val first = UUID.fromString("00000000-0000-0000-0000-000000000071")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000072")
        displayNetwork.authorizedPlayers += first
        displayNetwork.authorizedPlayers += second

        device.turnOn()
        assertTrue(device.attachRetainedDisplayViewer(first))
        assertTrue(device.attachRetainedDisplayViewer(second))
        endpoint.retainedPayloadBatchDrainCalls = 0

        displayNetwork.authorizedPlayers -= second
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }
        assertEquals(listOf(2L), endpoint.retainedViewerDetaches)
        assertEquals(1, endpoint.retainedPayloadBatchDrainCalls)
        assertFalse(device.acceptRetainedDisplayServerbound(second, byteArrayOf(1)))

        endpoint.retainedServerboundOutcome = 3
        endpoint.retainedPayloads += NativeRetainedDisplayPayload(1L, byteArrayOf(9, 8, 7))
        assertTrue(device.acceptRetainedDisplayServerbound(first, byteArrayOf(1, 2, 3)))
        assertEquals(listOf(1L to 73, 2L to 73, 1L to 73), endpoint.retainedViewerAttaches)
        assertEquals(SentNativePayload(first, 73, byteArrayOf(9, 8, 7)), displayNetwork.sentRetainedPayloads.last())
        device.shutdown()
        assertEquals(listOf(2L, 1L), endpoint.retainedViewerDetaches)
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
        val device =
            K16RuntimeDevice(
                deviceId = 10,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        waitUntil { endpoint.keyboardChars.isNotEmpty() }

        assertEquals(listOf('R'.code.toByte()), endpoint.keyboardChars)
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
    }

    @Test
    fun dispatchesPasteInputThroughKeyboard0Endpoint() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 11,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("K16".encodeToByteArray())))
        waitUntil { endpoint.keyboardPasteBytes.isNotEmpty() }

        assertEquals(listOf("K16"), endpoint.keyboardPasteBytes.map { it.decodeToString() })
        assertEquals(emptyList(), endpoint.inputs.map { it.decodeToString() })
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
        val retainedViewerAttaches: MutableList<Pair<Long, Int>> = Collections.synchronizedList(mutableListOf())
        val retainedViewerDetaches: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val retainedServerbound: MutableList<Pair<Long, List<Byte>>> = Collections.synchronizedList(mutableListOf())
        val retainedCallThreadIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val retainedPayloads = ArrayDeque<NativeRetainedDisplayPayload>()
        @Volatile
        var retainedPayloadBatchDrainCalls = 0
        var retainedServerboundOutcome = 1
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

        override fun attachRetainedDisplayViewer(
            viewerToken: Long,
            computerId: Int,
        ): Long {
            retainedViewerAttaches += viewerToken to computerId
            retainedCallThreadIds += Thread.currentThread().id
            return 1L
        }

        override fun detachRetainedDisplayViewer(viewerToken: Long): Boolean {
            retainedViewerDetaches += viewerToken
            retainedCallThreadIds += Thread.currentThread().id
            return true
        }

        override fun acceptRetainedDisplayServerbound(
            viewerToken: Long,
            payload: ByteArray,
        ): Int {
            retainedServerbound += viewerToken to payload.toList()
            retainedCallThreadIds += Thread.currentThread().id
            return retainedServerboundOutcome
        }

        override fun drainRetainedDisplayPayload(viewerToken: Long): ByteArray {
            retainedCallThreadIds += Thread.currentThread().id
            return retainedPayloads.firstOrNull { it.viewerToken == viewerToken }?.payload ?: ByteArray(0)
        }

        override fun drainRetainedDisplayPayloads(): List<NativeRetainedDisplayPayload> {
            retainedPayloadBatchDrainCalls += 1
            return buildList {
                while (retainedPayloads.isNotEmpty()) add(retainedPayloads.removeFirst())
            }
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

    }

    private data class KeyboardKeyDown(
        val key: Int,
        val repeat: Boolean,
        val modifiers: Int = 0,
    )

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

    private data class SentNativePayload(
        val playerUuid: UUID,
        val deviceId: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SentNativePayload &&
                playerUuid == other.playerUuid &&
                deviceId == other.deviceId &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var result = playerUuid.hashCode()
            result = 31 * result + deviceId
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private class RecordingDisplayNetworkBridge : DisplayNetworkBridge {
        val authorizedPlayers = linkedSetOf<UUID>()
        val sentRetainedPayloads = mutableListOf<SentNativePayload>()

        override fun isRetainedDisplayViewerAuthorized(
            playerUuid: UUID,
            deviceId: Int,
        ): Boolean = playerUuid in authorizedPlayers

        override fun sendRetainedDisplayPayload(
            playerUuid: UUID,
            deviceId: Int,
            payload: ByteArray,
        ) {
            sentRetainedPayloads += SentNativePayload(playerUuid, deviceId, payload)
        }
    }
}
