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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.runtime.VmValue

data class NativeDeviceDaemonTickSummary(
    val serverTick: Long,
    val turns: Long,
    val remainingWallNanos: Long,
    val idle: Boolean,
    val halted: Long,
    val hostRequests: Long,
)

data class NativeDeviceDaemonBootSummary(
    val pid: Int,
    val imageAttached: Boolean,
)

data class NativeDeviceDaemonHostRequest(
    val requestId: Long,
    val pid: Int,
    val kind: String,
    val moduleName: String?,
    val functionName: String?,
    val arguments: List<VmValue>,
    val path: String?,
    val workingDirectory: String?,
)

data class NativeImageVmMetrics(
    val executedInstructions: Long = 0,
    val instructionClones: Long = 0,
    val valueClones: Long = 0,
    val registerReads: Long = 0,
    val registerWrites: Long = 0,
    val functionCalls: Long = 0,
    val functionReturns: Long = 0,
    val hostCallAttempts: Long = 0,
    val nativeHostCalls: Long = 0,
    val jvmHostCallSignals: Long = 0,
    val pauseSignals: Long = 0,
    val stringAllocations: Long = 0,
    val recordAllocations: Long = 0,
    val opcodeCounts: List<Long> = List(OPCODE_COUNT_SIZE) { 0L },
) {
    fun opcodeCount(opcode: Int): Long = opcodeCounts.getOrElse(opcode) { 0L }

    fun opcodeSummary(): String =
        opcodeCounts
            .mapIndexedNotNull { opcode, count ->
                if (count > 0) "${opcodeName(opcode)}=$count" else null
            }.joinToString(",")

    companion object {
        const val OPCODE_COUNT_SIZE: Int = 42

        val EMPTY = NativeImageVmMetrics()

        fun from(values: LongArray): NativeImageVmMetrics =
            NativeImageVmMetrics(
                executedInstructions = values.getOrElse(0) { 0L },
                instructionClones = values.getOrElse(1) { 0L },
                valueClones = values.getOrElse(2) { 0L },
                registerReads = values.getOrElse(3) { 0L },
                registerWrites = values.getOrElse(4) { 0L },
                functionCalls = values.getOrElse(5) { 0L },
                functionReturns = values.getOrElse(6) { 0L },
                hostCallAttempts = values.getOrElse(7) { 0L },
                nativeHostCalls = values.getOrElse(8) { 0L },
                jvmHostCallSignals = values.getOrElse(9) { 0L },
                pauseSignals = values.getOrElse(10) { 0L },
                stringAllocations = values.getOrElse(11) { 0L },
                recordAllocations = values.getOrElse(12) { 0L },
                opcodeCounts =
                    List(OPCODE_COUNT_SIZE) { index ->
                        values.getOrElse(OPCODE_OFFSET + index) { 0L }
                    },
            )

        private const val OPCODE_OFFSET: Int = 13

        private fun opcodeName(opcode: Int): String =
            when (opcode) {
                1 -> "I32Const"
                2 -> "I64Const"
                3 -> "BoolConst"
                4 -> "RefConst"
                5 -> "LoadUnit"
                6 -> "LoadNull"
                7 -> "I32Move"
                8 -> "I64Move"
                9 -> "BoolMove"
                10 -> "RefMove"
                11 -> "I32Add"
                12 -> "I32Sub"
                13 -> "I32Mul"
                14 -> "I32Div"
                15 -> "I32Neg"
                16 -> "I32BitAnd"
                17 -> "I32BitOr"
                18 -> "I32BitXor"
                19 -> "I32BitNot"
                20 -> "I32Shl"
                21 -> "I32Shr"
                22 -> "I32Eq"
                23 -> "I32Ne"
                24 -> "I32Lt"
                25 -> "I32Le"
                26 -> "I32Gt"
                27 -> "I32Ge"
                28 -> "BoolNot"
                29 -> "BoolAnd"
                30 -> "BoolOr"
                31 -> "Jump"
                32 -> "JumpIfFalse"
                33 -> "JumpIfTrue"
                34 -> "CallStatic"
                35 -> "Return"
                36 -> "ReturnUnit"
                37 -> "CallHost"
                38 -> "Yield"
                39 -> "Sleep"
                40 -> "ConstructRecord"
                41 -> "GetField"
                else -> "Opcode$opcode"
            }
    }
}

data class NativeLowImageVmMetrics(
    val runInvocations: Long = 0,
    val elapsedNanos: Long = 0,
    val pauseSignals: Long = 0,
) {
    companion object {
        val EMPTY = NativeLowImageVmMetrics()

        fun from(values: LongArray): NativeLowImageVmMetrics =
            NativeLowImageVmMetrics(
                runInvocations = values.getOrElse(0) { 0L },
                elapsedNanos = values.getOrElse(1) { 0L },
                pauseSignals = values.getOrElse(2) { 0L },
            )
    }
}

sealed interface NativeLowImageVmSignal {
    data object HaltUnit : NativeLowImageVmSignal

    data class HaltI32(val value: Int) : NativeLowImageVmSignal

    data class HaltI64(val value: Long) : NativeLowImageVmSignal

    data class HaltAddr(val value: UInt) : NativeLowImageVmSignal

    data class HaltBool(val value: Boolean) : NativeLowImageVmSignal

    data object Pause : NativeLowImageVmSignal

    companion object {
        fun from(values: LongArray): NativeLowImageVmSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> HaltUnit
                2L -> HaltI32(values.getOrElse(1) { 0L }.toInt())
                3L -> HaltI64(values.getOrElse(1) { 0L })
                4L -> HaltAddr(values.getOrElse(1) { 0L }.toUInt())
                5L -> HaltBool(values.getOrElse(1) { 0L } != 0L)
                6L -> Pause
                else -> error("Unknown native low image VM signal tag: $tag")
            }
    }
}

internal interface NativeVmBindingsFacade {
    fun createImage(
        libraryPath: String,
        image: ByteArray,
        sliceBudgetNanos: Long,
    ): Long

    fun runImageUntilSignal(handle: Long): ByteArray

    fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    )

    fun imageMetrics(handle: Long): NativeImageVmMetrics = NativeImageVmMetrics.EMPTY

    fun freeImage(handle: Long)
}

object NativeVmBindings : NativeVmBindingsFacade {
    private val lock = Any()
    private var loadedPath: String? = null

    override fun createImage(
        libraryPath: String,
        image: ByteArray,
        sliceBudgetNanos: Long,
    ): Long {
        load(libraryPath)
        val handle = createImageNative(image, sliceBudgetNanos.coerceAtLeast(1))
        check(handle != 0L) { "Native image VM create returned a zero handle" }
        return handle
    }

    override fun runImageUntilSignal(handle: Long): ByteArray {
        require(handle != 0L) { "Native image VM handle is zero" }
        return runImageUntilSignalForHandleNative(handle)
    }

    override fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    ) {
        require(handle != 0L) { "Native image VM handle is zero" }
        resumeImageWithNative(handle, value)
    }

    override fun imageMetrics(handle: Long): NativeImageVmMetrics {
        require(handle != 0L) { "Native image VM handle is zero" }
        return NativeImageVmMetrics.from(imageMetricsNative(handle))
    }

    override fun freeImage(handle: Long) {
        if (handle != 0L) {
            freeImageNative(handle)
        }
    }

    fun createLowImage(
        libraryPath: String,
        image: ByteArray,
        sliceBudgetNanos: Int,
    ): Long {
        load(libraryPath)
        val handle = createLowImageNative(image, sliceBudgetNanos.coerceAtLeast(1))
        check(handle != 0L) { "Native low image VM create returned a zero handle" }
        return handle
    }

    fun runLowImageUntilSignal(handle: Long): NativeLowImageVmSignal {
        require(handle != 0L) { "Native low image VM handle is zero" }
        return NativeLowImageVmSignal.from(runLowImageUntilSignalNative(handle))
    }

    fun lowImageMetrics(handle: Long): NativeLowImageVmMetrics {
        require(handle != 0L) { "Native low image VM handle is zero" }
        return NativeLowImageVmMetrics.from(lowImageMetricsNative(handle))
    }

    fun freeLowImage(handle: Long) {
        if (handle != 0L) {
            freeLowImageNative(handle)
        }
    }

    fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        imageSliceBudgetNanos: Long,
        memoryQuotaBytes: Long = Long.MAX_VALUE,
        deviceId: Int = 0,
        profileName: String = "",
    ): Long {
        load(NativeLibraryLocator.requireLibraryPath())
        val handle =
            createDeviceDaemonNative(
                maxEventQueueSize.coerceAtLeast(1),
                maxBufferedBytesPerChannel.coerceAtLeast(1),
                imageSliceBudgetNanos.coerceAtLeast(1),
                memoryQuotaBytes.coerceAtLeast(0),
                deviceId,
                profileName,
            )
        check(handle != 0L) { "Native device daemon create returned a zero handle" }
        return handle
    }

    fun freeDeviceDaemon(handle: Long) {
        if (handle != 0L) {
            freeDeviceDaemonNative(handle)
        }
    }

    fun refillDeviceDaemonQuota(
        daemonHandle: Long,
        wallNanos: Long,
        serverTick: Long,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        refillDeviceDaemonQuotaNative(
            daemonHandle,
            wallNanos,
            serverTick,
        )
    }

    fun runDeviceDaemonReady(
        daemonHandle: Long,
        maxTurns: Long,
    ): NativeDeviceDaemonTickSummary {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return runDeviceDaemonReadyNative(
            daemonHandle,
            maxTurns.coerceAtLeast(1),
        ).toNativeDeviceDaemonTickSummary()
    }

    fun bootDeviceDaemon(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return bootDeviceDaemonNative(
            daemonHandle,
            image,
            programPath,
            argument,
            workingDirectory,
        ).toNativeDeviceDaemonBootSummary()
    }

    fun drainDeviceDaemonHostRequests(daemonHandle: Long): List<NativeDeviceDaemonHostRequest> {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return drainDeviceDaemonHostRequestsNative(daemonHandle).toNativeDeviceDaemonHostRequests()
    }

    fun completeDeviceDaemonHostRequest(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return completeDeviceDaemonHostRequestNative(daemonHandle, requestId, value)
    }

    fun completeDeviceDaemonCompileProgram(
        daemonHandle: Long,
        requestId: Long,
        image: ByteArray?,
        exitCode: Int,
    ): Boolean {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return completeDeviceDaemonCompileProgramNative(daemonHandle, requestId, image ?: ByteArray(0), exitCode)
    }

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        arguments: List<Any?>,
    ): Boolean = enqueueDeviceDaemonEvent(daemonHandle, eventName, nativeEventPayload(arguments))

    fun enqueueDeviceDaemonEvent(
        daemonHandle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return enqueueDeviceDaemonEventNative(daemonHandle, eventName, payload)
    }

    fun attachDeviceDaemonFilesystem(
        daemonHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        attachDeviceDaemonFilesystemNative(daemonHandle, rootPath, quotaBytes)
    }

    fun attachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        attachDeviceDaemonDisplayNative(daemonHandle, displayId, width, height)
    }

    fun detachDeviceDaemonDisplay(
        daemonHandle: Long,
        displayId: Int,
    ) {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        detachDeviceDaemonDisplayNative(daemonHandle, displayId)
    }

    fun drainDeviceDaemonDisplayFrames(daemonHandle: Long): ByteArray {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return drainDeviceDaemonDisplayFramesNative(daemonHandle)
    }

    fun deviceDaemonDisplayWakeSequence(daemonHandle: Long): Long {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return deviceDaemonDisplayWakeSequenceNative(daemonHandle)
    }

    fun waitForDeviceDaemonDisplayWake(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long {
        require(daemonHandle != 0L) { "Native device daemon handle is zero" }
        return waitForDeviceDaemonDisplayWakeNative(daemonHandle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
    }

    private fun load(libraryPath: String) {
        synchronized(lock) {
            val current = loadedPath
            if (current == libraryPath) {
                return
            }
            require(current == null) {
                "Native VM library already loaded from $current; cannot load $libraryPath in the same JVM"
            }
            System.load(libraryPath)
            loadedPath = libraryPath
        }
    }

    private fun nativeEventPayload(arguments: List<Any?>): ByteArray =
        VmValue
            .RecordValue(
                typeName = "EventPayload",
                fields = arguments.toNativeEventFields(),
            ).toNativeBytes("events", "enqueue")

    private fun List<Any?>.toNativeEventFields(): LinkedHashMap<String, VmValue> {
        val fields = LinkedHashMap<String, VmValue>()
        forEachIndexed { index, value ->
            fields["arg$index"] = value.toNativeEventValue()
        }
        return fields
    }

    private fun Any?.toNativeEventValue(): VmValue =
        when (this) {
            null -> VmValue.NullValue
            is Int -> VmValue.IntValue(this)
            is Boolean -> VmValue.BoolValue(this)
            is String -> VmValue.StringValue(this)
            is ByteArray -> VmValue.StringValue(decodeToString())
            else -> VmValue.StringValue(toString())
        }

    private fun LongArray.toNativeDeviceDaemonTickSummary(): NativeDeviceDaemonTickSummary =
        NativeDeviceDaemonTickSummary(
            serverTick = getOrElse(0) { 0L },
            turns = getOrElse(1) { 0L },
            remainingWallNanos = getOrElse(2) { 0L },
            idle = getOrElse(3) { 0L } != 0L,
            halted = getOrElse(4) { 0L },
            hostRequests = getOrElse(5) { 0L },
        )

    private fun LongArray.toNativeDeviceDaemonBootSummary(): NativeDeviceDaemonBootSummary =
        NativeDeviceDaemonBootSummary(
            pid =
                getOrElse(0) { 0L }
                    .toInt(),
            imageAttached = getOrElse(1) { 0L } != 0L,
        )

    private fun ByteArray.toNativeDeviceDaemonHostRequests(): List<NativeDeviceDaemonHostRequest> {
        val reader = NativeDeviceDaemonHostRequestReader(this)
        return List(reader.i32()) {
            val requestId = reader.i64()
            val pid = reader.i32()
            val kind =
                when (reader.u8()) {
                    0 -> "hostCall"
                    1 -> "compileProgram"
                    2 -> "crash"
                    else -> "unknown"
                }
            val moduleName = reader.string().takeIf { it.isNotEmpty() }
            val functionName = reader.string().takeIf { it.isNotEmpty() }
            val arguments =
                List(reader.i32()) {
                    reader.value().toVmValue(moduleName.orEmpty(), functionName.orEmpty())
                }
            val path = reader.string().takeIf { it.isNotEmpty() }
            val workingDirectory = reader.string().takeIf { it.isNotEmpty() }
            NativeDeviceDaemonHostRequest(
                requestId = requestId,
                pid = pid,
                kind = kind,
                moduleName = moduleName,
                functionName = functionName,
                arguments = arguments,
                path = path,
                workingDirectory = workingDirectory,
            )
        }
    }

    @JvmStatic
    private external fun createImageNative(
        image: ByteArray,
        sliceBudgetNanos: Long,
    ): Long

    @JvmStatic
    private external fun runImageUntilSignalForHandleNative(handle: Long): ByteArray

    @JvmStatic
    private external fun resumeImageWithNative(
        handle: Long,
        value: ByteArray,
    )

    @JvmStatic
    private external fun imageMetricsNative(handle: Long): LongArray

    @JvmStatic
    private external fun freeImageNative(handle: Long)

    @JvmStatic
    private external fun createLowImageNative(
        image: ByteArray,
        sliceBudgetNanos: Int,
    ): Long

    @JvmStatic
    private external fun runLowImageUntilSignalNative(handle: Long): LongArray

    @JvmStatic
    private external fun lowImageMetricsNative(handle: Long): LongArray

    @JvmStatic
    private external fun freeLowImageNative(handle: Long)

    @JvmStatic
    private external fun createDeviceDaemonNative(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        imageSliceBudgetNanos: Long,
        memoryQuotaBytes: Long,
        deviceId: Int,
        profileName: String,
    ): Long

    @JvmStatic
    private external fun freeDeviceDaemonNative(handle: Long)

    @JvmStatic
    private external fun refillDeviceDaemonQuotaNative(
        daemonHandle: Long,
        wallNanos: Long,
        serverTick: Long,
    )

    @JvmStatic
    private external fun runDeviceDaemonReadyNative(
        daemonHandle: Long,
        maxTurns: Long,
    ): LongArray

    @JvmStatic
    private external fun bootDeviceDaemonNative(
        daemonHandle: Long,
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): LongArray

    @JvmStatic
    private external fun drainDeviceDaemonHostRequestsNative(daemonHandle: Long): ByteArray

    @JvmStatic
    private external fun completeDeviceDaemonHostRequestNative(
        daemonHandle: Long,
        requestId: Long,
        value: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun completeDeviceDaemonCompileProgramNative(
        daemonHandle: Long,
        requestId: Long,
        image: ByteArray,
        exitCode: Int,
    ): Boolean

    @JvmStatic
    private external fun enqueueDeviceDaemonEventNative(
        daemonHandle: Long,
        eventName: String,
        payload: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun attachDeviceDaemonFilesystemNative(
        daemonHandle: Long,
        rootPath: String,
        quotaBytes: Long,
    )

    @JvmStatic
    private external fun attachDeviceDaemonDisplayNative(
        daemonHandle: Long,
        displayId: Int,
        width: Int,
        height: Int,
    )

    @JvmStatic
    private external fun detachDeviceDaemonDisplayNative(
        daemonHandle: Long,
        displayId: Int,
    )

    @JvmStatic
    private external fun drainDeviceDaemonDisplayFramesNative(daemonHandle: Long): ByteArray

    @JvmStatic
    private external fun deviceDaemonDisplayWakeSequenceNative(daemonHandle: Long): Long

    @JvmStatic
    private external fun waitForDeviceDaemonDisplayWakeNative(
        daemonHandle: Long,
        observedWakeSequence: Long,
        timeoutMillis: Long,
    ): Long

}

private class NativeDeviceDaemonHostRequestReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun u8(): Int {
        require(offset < bytes.size) { "Unexpected end of native device daemon host request payload" }
        return bytes[offset++].toInt() and 0xff
    }

    fun i32(): Int {
        val b0 = u8()
        val b1 = u8()
        val b2 = u8()
        val b3 = u8()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun i64(): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((u8().toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    fun string(): String {
        val length = i32()
        require(length >= 0) { "Negative native device daemon string length $length" }
        require(offset + length <= bytes.size) { "Unexpected end of native device daemon string" }
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }

    fun value(): NativeVmValue =
        when (val tag = u8()) {
            0 -> NativeVmValue.UnitValue
            1 -> NativeVmValue.NullValue
            2 -> NativeVmValue.BoolValue(u8() != 0)
            3 -> NativeVmValue.IntValue(i32())
            4 -> NativeVmValue.LongValue(i64())
            5 -> NativeVmValue.StringValue(string())
            6 -> {
                val typeName = string()
                val fieldCount = i32()
                require(fieldCount >= 0) { "Negative native VM record field count $fieldCount" }
                NativeVmValue.RecordValue(
                    typeName = typeName,
                    fields =
                        LinkedHashMap<String, NativeVmValue>().apply {
                            repeat(fieldCount) {
                                this[string()] = value()
                            }
                        },
                )
            }
            else -> error("Unknown native VM value tag $tag")
        }
}
