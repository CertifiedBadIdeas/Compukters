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

package ru.lazyhat.compukterkraft.lang.runtime

internal data class CapturedMono5x7Blit(
    val displayId: Int,
    val x: Int,
    val y: Int,
    val rows: List<Int>,
    val foreground: Int,
    val background: Int,
)

internal class RecordingRuntime(
    private val argument: String = "",
    private val vmRamBytes: Long = 64 * 1024,
    private val monitorConnected: Boolean = false,
    private val queuedEvents: List<VmEvent> = listOf(VmEvent("boot")),
    private val spawnPid: Int = 1,
    private val waitCode: Int = 0,
    override val metrics: DeviceRuntimeMetrics = NoopDeviceRuntimeMetrics,
) : DeviceRuntime {
    val lines = mutableListOf<String>()
    val eventFilters = mutableListOf<String?>()
    val createdDirectories = mutableListOf<String>()
    val mono5x7Blits = mutableListOf<CapturedMono5x7Blit>()
    var sleepCalls = 0
    var yieldCalls = 0
    private var nextEventIndex = 0
    private val deferredEvents = ArrayDeque<VmEvent>()

    override val profile =
        DeviceProfile(
            id = "test",
            displayName = "Test Computer",
            cpuBudgetNanosPerSlice = 1_000_000,
            maxEventQueueSize = 16,
            allowedCapabilities =
                setOf(
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.IPC,
                    DeviceCapability.DISPLAY,
                    DeviceCapability.PERIPHERALS,
                ),
            resources =
                DeviceResources(
                    cpu =
                        DeviceCpuResources(
                            wallTimeGuardNanosPerSlice = 1_000_000,
                        ),
                    memory = DeviceMemoryResources(vmRamBytes = vmRamBytes),
                    storage =
                        DeviceStorageResources(
                            programRomBytes = 64 * 1024,
                            diskBytes = 256 * 1024,
                        ),
                    queues =
                        DeviceQueueResources(
                            eventQueueSlots = 16,
                            hostCallQueueSlots = 16,
                        ),
                ),
        )

    override val display: DeviceDisplayApi =
        object : DeviceDisplayApi by NoopDeviceDisplayApi {
            override fun blitMono5x7(
                displayId: Int,
                x: Int,
                y: Int,
                row0: Int,
                row1: Int,
                row2: Int,
                row3: Int,
                row4: Int,
                row5: Int,
                row6: Int,
                foreground: Int,
                background: Int,
            ) {
                mono5x7Blits +=
                    CapturedMono5x7Blit(
                        displayId = displayId,
                        x = x,
                        y = y,
                        rows = listOf(row0, row1, row2, row3, row4, row5, row6),
                        foreground = foreground,
                        background = background,
                    )
            }
        }

    override val system =
        object : DeviceSystemApi {
            override val deviceId: Int = 7
            override val label: String? = "Test"
            override val currentTick: Long = 42L

            override fun queueEvent(
                name: String,
                arguments: List<Any?>,
            ) = Unit

            override fun shutdown() = Unit

            override fun reboot() = Unit

            override fun log(message: String) {
                lines += message
            }
        }

    override val filesystem: DeviceFileSystemApi =
        object : DeviceFileSystemApi {
            override suspend fun exists(path: String): Boolean = path == "readme.txt" || path == "docs" || path == "tmp"

            override suspend fun isDirectory(path: String): Boolean = path == "docs" || path == "tmp"

            override suspend fun readText(path: String): String? = null

            override suspend fun writeText(
                path: String,
                text: String,
            ) = Unit

            override suspend fun makeDirectory(path: String): Boolean {
                createdDirectories += path
                return true
            }

            override suspend fun remove(path: String): Boolean = true

            override suspend fun list(path: String): List<DeviceWorkspaceEntry> =
                listOf(
                    DeviceWorkspaceEntry("docs", directory = true),
                    DeviceWorkspaceEntry("readme.txt", directory = false),
                )
        }

    override val process =
        object : DeviceProcessApi {
            private var currentDirectory = ""

            override val workingDirectory: String
                get() = currentDirectory

            override val argument: String
                get() = this@RecordingRuntime.argument

            override suspend fun changeDirectory(path: String): Boolean {
                currentDirectory = path
                return true
            }

            override suspend fun run(
                path: String,
                argument: String,
            ): Int = 0

            override suspend fun spawn(
                path: String,
                argument: String,
            ): Int = spawnPid

            override suspend fun wait(pid: Int): Int = waitCode
        }

    override val ipc: DeviceIpcApi =
        object : DeviceIpcApi {
            private var nextId = 1
            private val channels = mutableMapOf<Int, String>()

            override suspend fun open(): Int {
                val id = nextId++
                channels[id] = ""
                return id
            }

            override suspend fun write(
                channelId: Int,
                text: String,
            ) {
                channels[channelId] = channels[channelId].orEmpty() + text
            }

            override suspend fun read(channelId: Int): String = tryRead(channelId)

            override fun tryRead(channelId: Int): String {
                val text = channels[channelId].orEmpty()
                channels[channelId] = ""
                return text
            }

            override fun close(channelId: Int) {
                channels.remove(channelId)
            }
        }

    override val events: DeviceEventApi =
        object : DeviceEventApi {
            private var nextId = 1
            private val captured = mutableMapOf<Int, List<Any?>>()

            override fun capture(arguments: List<Any?>): Pair<Int, Int> {
                val id = nextId++
                captured[id] = arguments
                return id to arguments.size
            }

            override fun argCount(eventId: Int): Int = captured[eventId]?.size ?: 0

            override fun argInt(
                eventId: Int,
                index: Int,
            ): Int =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Boolean -> if (value) 1 else 0
                    is String -> value.toIntOrNull() ?: 0
                    else -> 0
                }

            override fun argBool(
                eventId: Int,
                index: Int,
            ): Boolean =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is Boolean -> value
                    is String -> value.equals("true", ignoreCase = true)
                    else -> false
                }

            override fun argString(
                eventId: Int,
                index: Int,
            ): String =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is String -> value
                    is ByteArray -> value.toString(Charsets.UTF_8)
                    is Int -> value.toString()
                    is Long -> value.toString()
                    is Boolean -> value.toString()
                    else -> ""
                }
        }

    override val redstone: DeviceRedstoneApi = object : DeviceRedstoneApi {}
    override val peripherals: DevicePeripheralApi =
        object : DevicePeripheralApi {
            override fun monitorExists(): Boolean = monitorConnected
        }

    override suspend fun pullEvent(filter: String?): VmEvent {
        eventFilters += filter
        val deferred = deferredEvents.removeFirstOrNull()
        if (deferred != null && (filter == null || deferred.name == filter)) {
            return deferred
        }
        if (deferred != null) {
            deferredEvents.addLast(deferred)
        }
        while (nextEventIndex < queuedEvents.size) {
            val event = queuedEvents[nextEventIndex++]
            if (filter == null || event.name == filter) {
                return event
            }
        }
        return VmEvent(filter ?: "boot")
    }

    override suspend fun tryPullEvent(filter: String?): VmEvent? {
        eventFilters += filter
        val deferred = deferredEvents.removeFirstOrNull()
        if (deferred != null) {
            if (filter == null || deferred.name == filter) {
                return deferred
            }
            deferredEvents.addLast(deferred)
            return null
        }
        if (nextEventIndex >= queuedEvents.size) return null
        val event = queuedEvents[nextEventIndex++]
        if (filter == null || event.name == filter) {
            return event
        }
        deferredEvents.addLast(event)
        return null
    }

    override suspend fun sleep(ticks: Long) {
        sleepCalls += ticks.toInt()
    }

    override suspend fun yield() {
        yieldCalls += 1
    }
}

internal class RecordingDeviceRuntimeMetrics : DeviceRuntimeMetrics {
    override val collectsDetailedMetrics: Boolean = true

    val hostCallCounts = mutableMapOf<String, Long>()
    val hostCallNanos = mutableMapOf<String, Long>()
    val hostCallWaitNanos = mutableMapOf<String, Long>()
    val nativeWaitNanos = mutableMapOf<String, Long>()
    val nativeWaitWakeups = mutableMapOf<String, Long>()
    val nativeWaitTimeouts = mutableMapOf<String, Long>()
    val instructionCounts = mutableMapOf<VmInstructionKind, Long>()
    val instructionNanos = mutableMapOf<VmInstructionKind, Long>()

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        val key = "$moduleName.$functionName"
        hostCallCounts[key] = hostCallCounts.getOrDefault(key, 0) + 1
        hostCallNanos[key] = hostCallNanos.getOrDefault(key, 0) + nanos.coerceAtLeast(0)
    }

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        val key = "$moduleName.$functionName"
        hostCallWaitNanos[key] = hostCallWaitNanos.getOrDefault(key, 0) + nanos.coerceAtLeast(0)
    }

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) {
        instructionCounts[kind] = instructionCounts.getOrDefault(kind, 0) + 1
        instructionNanos[kind] = instructionNanos.getOrDefault(kind, 0) + nanos.coerceAtLeast(0)
    }

    override fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) {
        nativeWaitNanos[kind] = nativeWaitNanos.getOrDefault(kind, 0) + nanos.coerceAtLeast(0)
        if (woke) {
            nativeWaitWakeups[kind] = nativeWaitWakeups.getOrDefault(kind, 0) + 1
        } else {
            nativeWaitTimeouts[kind] = nativeWaitTimeouts.getOrDefault(kind, 0) + 1
        }
    }
}
