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

interface DeviceProgram {
    suspend fun run(runtime: DeviceRuntime)
}

enum class VmSignalKind {
    HALT,
    PAUSE,
    YIELD,
    SLEEP,
    WAIT_EVENT,
    HOST_CALL,
}

enum class VmInstructionKind {
    PUSH_INT,
    PUSH_LONG,
    PUSH_STRING,
    PUSH_BOOL,
    PUSH_UNIT,
    PUSH_NULL,
    LOAD_LOCAL,
    STORE_LOCAL,
    POP,
    JUMP,
    JUMP_IF_FALSE,
    JUMP_IF_TRUE,
    CALL_FUNCTION,
    CALL_BUILTIN,
    GET_FIELD,
    SET_FIELD,
    CONSTRUCT_RECORD,
    CONSTRUCT_CLASS,
    CONSTRUCT_ARRAY,
    CONSTRUCT_LIST,
    CONSTRUCT_MAP,
    INDEX_GET,
    INDEX_SET,
    CALL_COLLECTION_METHOD,
    CALL_METHOD,
    CALL_STATIC_METHOD,
    BINARY,
    UNARY,
    RETURN,
}

interface DeviceRuntimeMetrics {
    val collectsDetailedMetrics: Boolean
        get() = true

    fun recordVmSignal(kind: VmSignalKind)

    fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    )
}

object NoopDeviceRuntimeMetrics : DeviceRuntimeMetrics {
    override val collectsDetailedMetrics: Boolean = false

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) = Unit
}

interface DeviceRuntime {
    val profile: DeviceProfile
    val metrics: DeviceRuntimeMetrics
        get() = NoopDeviceRuntimeMetrics
    val system: DeviceSystemApi
    val display: DeviceDisplayApi
        get() = NoopDeviceDisplayApi
    val filesystem: DeviceFileSystemApi
    val process: DeviceProcessApi
    val ipc: DeviceIpcApi
    val events: DeviceEventApi
    val redstone: DeviceRedstoneApi
    val peripherals: DevicePeripheralApi

    suspend fun pullEvent(filter: String? = null): VmEvent

    suspend fun tryPullEvent(filter: String? = null): VmEvent? = null

    suspend fun poll(channelId: Int): VmPollResult {
        while (true) {
            val text = ipc.tryRead(channelId)
            if (text.isNotEmpty()) {
                return VmPollResult(kind = "ipc", text = text)
            }
            val event = tryPullEvent()
            if (event != null) {
                return VmPollResult(kind = "event", event = event)
            }
            yield()
        }
    }

    suspend fun sleep(ticks: Long)

    suspend fun yield()
}

interface DeviceSystemApi {
    val deviceId: Int
    val label: String?
    val currentTick: Long

    fun queueEvent(
        name: String,
        arguments: List<Any?> = emptyList(),
    )

    fun shutdown()

    fun reboot()

    fun log(message: String)
}

interface DeviceDisplayApi {
    fun primary(): Int

    fun isAttached(displayId: Int): Boolean

    fun width(displayId: Int): Int

    fun height(displayId: Int): Int

    fun clear(
        displayId: Int,
        rgb565: Int,
    )

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    )

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    )

    fun copyRect(
        displayId: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    )

    fun blitMono(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    )

    fun blitMono5x7(
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
    )

    fun blitMono5x7Text(
        displayId: Int,
        x: Int,
        y: Int,
        text: String,
        foreground: Int,
        background: Int,
    )

    fun present(displayId: Int)
}

object NoopDeviceDisplayApi : DeviceDisplayApi {
    override fun primary(): Int = -1

    override fun isAttached(displayId: Int): Boolean = false

    override fun width(displayId: Int): Int = 0

    override fun height(displayId: Int): Int = 0

    override fun clear(
        displayId: Int,
        rgb565: Int,
    ) = Unit

    override fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) = Unit

    override fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) = Unit

    override fun copyRect(
        displayId: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    ) = Unit

    override fun blitMono(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) = Unit

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
    ) = Unit

    override fun blitMono5x7Text(
        displayId: Int,
        x: Int,
        y: Int,
        text: String,
        foreground: Int,
        background: Int,
    ) = Unit

    override fun present(displayId: Int) = Unit
}

interface DeviceFileSystemApi {
    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean

    suspend fun readText(path: String): String?

    suspend fun writeText(
        path: String,
        text: String,
    )

    suspend fun makeDirectory(path: String): Boolean

    suspend fun remove(path: String): Boolean

    suspend fun list(path: String = ""): List<DeviceWorkspaceEntry>
}

interface DeviceProcessApi {
    val workingDirectory: String
    val argument: String

    suspend fun changeDirectory(path: String): Boolean

    suspend fun spawn(path: String): Int = spawn(path, "")

    suspend fun spawn(
        path: String,
        argument: String,
    ): Int

    suspend fun wait(pid: Int): Int

    suspend fun run(path: String): Int = run(path, "")

    suspend fun run(
        path: String,
        argument: String,
    ): Int = wait(spawn(path, argument))
}

interface DeviceIpcApi {
    suspend fun open(): Int

    suspend fun write(
        channelId: Int,
        text: String,
    )

    suspend fun read(channelId: Int): String

    fun tryRead(channelId: Int): String

    fun close(channelId: Int)
}

object NoopDeviceIpcApi : DeviceIpcApi {
    override suspend fun open(): Int = 0

    override suspend fun write(
        channelId: Int,
        text: String,
    ) = Unit

    override suspend fun read(channelId: Int): String = ""

    override fun tryRead(channelId: Int): String = ""

    override fun close(channelId: Int) = Unit
}

interface DeviceEventApi {
    fun capture(arguments: List<Any?>): Pair<Int, Int>

    fun argCount(eventId: Int): Int

    fun argInt(
        eventId: Int,
        index: Int,
    ): Int

    fun argBool(
        eventId: Int,
        index: Int,
    ): Boolean

    fun argString(
        eventId: Int,
        index: Int,
    ): String
}

object NoopDeviceEventApi : DeviceEventApi {
    override fun capture(arguments: List<Any?>): Pair<Int, Int> = 0 to arguments.size

    override fun argCount(eventId: Int): Int = 0

    override fun argInt(
        eventId: Int,
        index: Int,
    ): Int = 0

    override fun argBool(
        eventId: Int,
        index: Int,
    ): Boolean = false

    override fun argString(
        eventId: Int,
        index: Int,
    ): String = ""
}

interface DeviceRedstoneApi

interface DevicePeripheralApi {
    fun monitorExists(): Boolean = false
}

object DeviceProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
    const val BOOT_SCRIPT_NAME = "boot.ck"
}
