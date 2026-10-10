/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralRuntime

internal interface DisplayEndpoint {
    val identity: Any
    val checkpointIdentity: String? get() = null
    val canvas: ru.lazyhat.compukters.core.display.DisplayCanvas

    fun valid(): Boolean

    /** Keep private frame state until the asynchronous hibernation checkpoint has captured it. */
    fun leaseValid(): Boolean = valid()
}

internal sealed interface DisplayResolution {
    data class Found(
        val endpoint: DisplayEndpoint,
    ) : DisplayResolution

    data class Failed(
        val kind: HostFailureKind,
        val detail: String,
    ) : DisplayResolution
}

internal class DisplayHostState(
    private val resolveSide: (Int) -> DisplayEndpoint?,
    private val resolveName: (String) -> DisplayResolution,
    private val peripherals: ComputerPeripheralRuntime? = null,
) : ProgramAddonHost {
    override val capabilitySchemas: List<HostCapabilitySchema> = listOf(SCHEMA)

    private val owner = Any()
    private var dispatchTask = 0
    private val handles = linkedMapOf<Int, DisplayEndpoint>()
    private var nextHandle = 1
    private val accessed = linkedMapOf<Any, DisplayEndpoint>()
    private val frameTasks = linkedMapOf<ru.lazyhat.compukters.core.display.DisplayCanvas, Int>()
    private val accessedHandles = linkedMapOf<Int, DisplayEndpoint>()

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        dispatchTask = request.identity.taskId
        frameTasks.keys.removeIf { !it.owns(owner) }
        val response =
            if (request.capability.namespace != SCHEMA.identity.namespace || request.capability.name != SCHEMA.identity.name ||
                request.capability.abiMajor != 1 || request.capability.abiMinor !in 0..1
            ) {
                failure(HostFailureKind.OTHER, "Unexpected display capability")
            } else {
                when (request.operation) {
                    0 -> {
                        if (request.arguments.size == 1) request.intAt(0)?.let(::acquireSide) ?: malformed() else malformed()
                    }

                    1 -> {
                        if (request.arguments.size == 1) request.stringAt(0)?.let(::acquireNamed) ?: malformed() else malformed()
                    }

                    2 -> {
                        val handle = request.intAt(0)
                        val x = request.intAt(1)
                        val y = request.intAt(2)
                        val text = request.stringAt(3)
                        if (request.arguments.size != 4 || handle == null || x == null || y == null || text == null) {
                            malformed()
                        } else {
                            writeAt(handle, x, y, text)
                        }
                    }

                    3 -> {
                        if (request.arguments.size == 1) request.intAt(0)?.let(::clear) ?: malformed() else malformed()
                    }

                    else -> {
                        graphical(request)
                    }
                }
            }
        return ProgramAddonDispatch.Completed(response)
    }

    override fun checkpoint(): ByteArray {
        check(handles.isEmpty()) { "Legacy display handles have no portable location" }
        val writer =
            ru.lazyhat.compukters.api.addon
                .ResourceCheckpointWriter()
        writer.int(4)
        writer.int(nextHandle)
        val owned = accessedHandles.entries.distinctBy { it.value.identity }.filter { it.value.canvas.owns(owner) }
        writer.int(owned.size)
        owned.forEach { (handle, endpoint) ->
            writer.int(handle)
            val task = frameTasks[endpoint.canvas] ?: 0
            writer.int(task)
            if (task != 0) {
                val pending = requireNotNull(endpoint.canvas.frameSnapshot(owner))
                writer.int(pending.first)
                writer.long(endpoint.canvas.frameWorkUsed(owner))
                writer.bytes(pending.second, ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_PIXELS * 3)
            }
        }
        return writer.finish()
    }

    override fun restoreCheckpoint(state: ByteArray) {
        val reader =
            ru.lazyhat.compukters.api.addon
                .ResourceCheckpointReader(state)
        val version = reader.int()
        require(version in 2..4)
        val cursor = reader.int()
        require(cursor > 0)
        val owned = linkedSetOf<Int>()

        data class Pending(
            val task: Int,
            val mode: Int,
            val pixels: ByteArray,
            val work: Long,
        )
        val pending = linkedMapOf<Int, Pending>()
        var pendingBytes = 0
        repeat(reader.count(1024)) {
            val handle = reader.int()
            require(handle > 0 && owned.add(handle))
            if (version == 2) repeat(10) { reader.text(256) } // Never replay the old published image.
            if (version == 4) {
                val task = reader.int()
                require(task >= 0)
                if (task > 0) {
                    val mode = reader.int()
                    require(mode in 0..3)
                    val work = reader.long()
                    require(work in 0..ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_WORK)
                    val rgb = reader.bytes(ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_PIXELS * 3)
                    pendingBytes += rgb.size
                    require(pendingBytes <= ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_PIXELS * 3)
                    pending[handle] = Pending(task, mode, rgb, work)
                }
            }
        }
        reader.finish()
        reset()
        nextHandle = cursor
        require(owned.isEmpty() || peripherals != null)
        try {
            owned.forEach { handle ->
                val endpoint =
                    try {
                        endpoint(handle)
                    } catch (_: PeripheralFailure) {
                        return@forEach
                    }
                endpoint.canvas.acquire(owner, endpoint::leaseValid)
                accessed[endpoint.identity] = endpoint
                accessedHandles[handle] = endpoint
                pending[handle]?.let { draft ->
                    endpoint.canvas.restoreFrame(owner, draft.mode, draft.pixels, draft.work)
                    frameTasks[endpoint.canvas] = draft.task
                }
            }
        } catch (error: Exception) {
            reset()
            throw error
        }
    }

    override fun reset() {
        (handles.values + accessed.values).map(DisplayEndpoint::canvas).distinct().forEach { it.release(owner) }
        frameTasks.clear()
        handles.clear()
        accessed.clear()
        accessedHandles.clear()
        nextHandle = 1
    }

    private fun acquireSide(side: Int): HostResponse {
        if (side !in 0..5) return failure(HostFailureKind.OTHER, "Invalid display side")
        peripherals?.let { runtime ->
            return discover { runtime.session.at(DisplayPeripheralIntegration.contract.id, side) }
        }
        return resolveSide(side)?.let(::retain)
            ?: failure(HostFailureKind.UNAVAILABLE, "No display at that side")
    }

    private fun acquireNamed(name: String): HostResponse {
        peripherals?.let { runtime ->
            return discover { runtime.session.named(DisplayPeripheralIntegration.contract.id, name) }
        }
        return when (val result = resolveName(name)) {
            is DisplayResolution.Found -> retain(result.endpoint)
            is DisplayResolution.Failed -> failure(result.kind, result.detail)
        }
    }

    private fun discover(query: () -> Int): HostResponse =
        try {
            val handle = query()
            if (handle ==
                0
            ) {
                failure(HostFailureKind.UNAVAILABLE, "No reachable display satisfies the query")
            } else {
                HostResponse.IntSuccess(handle)
            }
        } catch (failure: PeripheralFailure) {
            failure(failure.kind, failure.message)
        }

    private fun retain(endpoint: DisplayEndpoint): HostResponse {
        if (!endpoint.valid()) return failure(HostFailureKind.UNAVAILABLE, "Display is unavailable")
        handles.entries.removeIf { (_, retained) ->
            (!retained.valid()).also { stale -> if (stale) retained.canvas.release(owner) }
        }
        handles.entries.firstOrNull { it.value.identity == endpoint.identity && it.value.valid() }?.let {
            return HostResponse.IntSuccess(it.key)
        }
        if (handles.size >= MAXIMUM_HANDLES) return failure(HostFailureKind.UNAVAILABLE, "Display handle limit reached")
        val handle = nextHandle++
        handles[handle] = endpoint
        return HostResponse.IntSuccess(handle)
    }

    private fun writeAt(
        handle: Int,
        x: Int,
        y: Int,
        text: String,
    ): HostResponse =
        withEndpoint(handle) { endpoint ->
            require(x in 0 until 20 && y in 0 until 10 && text.codePointCount(0, text.length) <= 20 - x)
            require(text.toByteArray(Charsets.UTF_8).size <= 256 && text.none(Character::isISOControl))
            require(text.codePoints().noneMatch { it in 0xD800..0xDFFF })
            endpoint.canvas.acquire(owner, endpoint::leaseValid)
            chooseMode(endpoint.canvas, 3)
            endpoint.canvas.fill(owner, x * 6, y * 12, text.codePointCount(0, text.length) * 6, 12, 0)
            DisplayRasterFont.draw(endpoint.canvas, owner, x * 6, y * 12, text, 0x9FE8C3, 1)
            HostResponse.UnitSuccess
        }

    private fun clear(handle: Int): HostResponse =
        withEndpoint(handle) { endpoint ->
            endpoint.canvas.acquire(owner, endpoint::leaseValid)
            endpoint.canvas.fill(owner, 0, 0, endpoint.canvas.activeWidth(owner), endpoint.canvas.activeHeight(owner), 0)
            HostResponse.UnitSuccess
        }

    private fun endpoint(handle: Int): DisplayEndpoint {
        val runtime =
            peripherals ?: return handles[handle] ?: throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Unknown display handle")
        return try {
            runtime.endpoint(DisplayPeripheralIntegration.graphicalContract, handle)
        } catch (failure: PeripheralFailure) {
            if (failure.kind != HostFailureKind.OTHER) throw failure
            runtime.endpoint(DisplayPeripheralIntegration.contract, handle)
        }
    }

    private fun withEndpoint(
        handle: Int,
        action: (DisplayEndpoint) -> HostResponse,
    ): HostResponse =
        try {
            val endpoint = endpoint(handle)
            if (!endpoint.valid()) {
                endpoint.canvas.release(owner)
                throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Display is disconnected")
            }
            check(frameTasks[endpoint.canvas] == null || frameTasks[endpoint.canvas] == dispatchTask) {
                "Another task has an open frame on this display"
            }
            accessed[endpoint.identity] = endpoint
            accessedHandles[handle] = endpoint
            action(endpoint)
        } catch (error: PeripheralFailure) {
            failure(error.kind, error.message)
        } catch (error: IllegalArgumentException) {
            failure(HostFailureKind.OTHER, error.message ?: "Invalid display arguments")
        } catch (error: IllegalStateException) {
            failure(HostFailureKind.UNAVAILABLE, error.message ?: "Display is busy")
        }

    private fun graphical(request: ProgramAddonRequest): HostResponse {
        val schema = SCHEMA.operations.getOrNull(request.operation) ?: return malformed()
        if (request.arguments.size != schema.arguments.size) return malformed()
        val handle = request.intAt(0) ?: return malformed()
        val values = request.arguments.drop(1)
        if (values.zip(schema.arguments.drop(1)).any { (value, type) ->
                if (type == HostValueType.STRING) value !is VmValue.StringValue else value !is VmValue.I32
            }
        ) {
            return malformed()
        }

        fun int(index: Int) = (values[index] as VmValue.I32).value

        fun string(index: Int) = (values[index] as VmValue.StringValue).value
        return withEndpoint(handle) { endpoint ->
            val canvas = endpoint.canvas
            val task = request.identity.taskId
            val frameTask = frameTasks[canvas]
            check(frameTask == null || frameTask == task) { "Another task has an open frame on this display" }
            when (request.operation) {
                4 -> {
                    HostResponse.IntSuccess(canvas.activeWidth(owner))
                }

                5 -> {
                    HostResponse.IntSuccess(canvas.activeHeight(owner))
                }

                6 -> {
                    HostResponse.IntSuccess(canvas.activeMode(owner))
                }

                else -> {
                    canvas.acquire(owner, endpoint::leaseValid)
                    when (request.operation) {
                        7 -> {
                            chooseMode(canvas, int(0))
                        }

                        8 -> {
                            canvas.fill(owner, 0, 0, canvas.activeWidth(owner), canvas.activeHeight(owner), int(0))
                        }

                        9 -> {
                            canvas.pixel(owner, int(0), int(1), int(2))
                        }

                        10 -> {
                            canvas.line(owner, int(0), int(1), int(2), int(3), int(4))
                        }

                        11 -> {
                            canvas.fill(owner, int(0), int(1), int(2), int(3), int(4))
                        }

                        12 -> {
                            DisplayRasterFont.draw(canvas, owner, int(0), int(1), string(2), int(3), int(4))
                        }

                        13 -> {
                            val encoded = string(2)
                            require(encoded.length <= 1536 && encoded.length % 6 == 0 && encoded.all { it in "0123456789abcdefABCDEF" })
                            val colors = IntArray(encoded.length / 6) { encoded.substring(it * 6, it * 6 + 6).toInt(16) }
                            canvas.imageRow(owner, int(0), int(1), colors)
                        }

                        14 -> {
                            val pendingPixels = frameTasks.keys.sumOf { it.activeWidth(owner) * it.activeHeight(owner) }
                            check(
                                pendingPixels + canvas.width * canvas.height <=
                                    ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_PIXELS,
                            ) {
                                "Program frame pixel limit exceeded"
                            }
                            canvas.begin(owner)
                            frameTasks[canvas] = task
                        }

                        15, 16 -> {
                            check(frameTask == task) { "This task has no frame on the display" }
                            canvas.finish(owner, request.operation == 15)
                            frameTasks.remove(canvas)
                        }

                        else -> {
                            error("Unknown display operation")
                        }
                    }
                    HostResponse.UnitSuccess
                }
            }
        }
    }

    private fun chooseMode(
        canvas: ru.lazyhat.compukters.core.display.DisplayCanvas,
        requested: Int,
    ) {
        require(requested in 0..3)
        if (canvas in frameTasks) {
            val otherPixels = frameTasks.keys.filter { it !== canvas }.sumOf { it.activeWidth(owner) * it.activeHeight(owner) }
            val density = ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES[requested]
            check(
                otherPixels + canvas.columns * canvas.rows * density * density <=
                    ru.lazyhat.compukters.core.display.DisplayCanvas.MAXIMUM_FRAME_PIXELS,
            ) { "Program frame pixel limit exceeded" }
        }
        canvas.setMode(owner, requested)
    }

    private fun ProgramAddonRequest.intAt(index: Int): Int? = (arguments.getOrNull(index) as? VmValue.I32)?.value

    private fun ProgramAddonRequest.stringAt(index: Int): String? = (arguments.getOrNull(index) as? VmValue.StringValue)?.value

    companion object {
        private const val MAXIMUM_HANDLES = 16

        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "display", 1, 1),
                listOf(
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.STRING), HostValueType.I32, true),
                    HostOperationSchema(
                        listOf(HostValueType.I32, HostValueType.I32, HostValueType.I32, HostValueType.STRING),
                        HostValueType.UNIT,
                        true,
                    ),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.UNIT, true),
                ) +
                    listOf(
                        operation(1, HostValueType.I32),
                        operation(1, HostValueType.I32),
                        operation(1, HostValueType.I32),
                        operation(2),
                        operation(2),
                        operation(4),
                        operation(6),
                        operation(6),
                        HostOperationSchema(
                            listOf(
                                HostValueType.I32,
                                HostValueType.I32,
                                HostValueType.I32,
                                HostValueType.STRING,
                                HostValueType.I32,
                                HostValueType.I32,
                            ),
                            HostValueType.UNIT,
                            true,
                        ),
                        HostOperationSchema(
                            listOf(HostValueType.I32, HostValueType.I32, HostValueType.I32, HostValueType.STRING),
                            HostValueType.UNIT,
                            true,
                        ),
                        operation(1),
                        operation(1),
                        operation(1),
                    ),
            )

        private fun operation(
            arguments: Int,
            result: HostValueType = HostValueType.UNIT,
        ) = HostOperationSchema(List(arguments) { HostValueType.I32 }, result, true)

        private fun malformed() = failure(HostFailureKind.OTHER, "Malformed display request")

        private fun failure(
            kind: HostFailureKind,
            detail: String,
        ) = HostResponse.Failure(kind, detail)
    }
}
