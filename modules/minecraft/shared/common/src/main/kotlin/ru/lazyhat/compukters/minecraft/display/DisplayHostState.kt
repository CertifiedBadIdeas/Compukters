/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

internal interface DisplayEndpoint {
    val identity: Any
    val buffer: DisplayBuffer

    fun valid(): Boolean
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
) : ProgramAddonHost {
    override val capabilitySchemas: List<HostCapabilitySchema> = listOf(SCHEMA)

    private val owner = Any()
    private val handles = linkedMapOf<Int, DisplayEndpoint>()
    private var nextHandle = 1

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        val response =
            if (request.capability != SCHEMA.identity) {
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
                        malformed()
                    }
                }
            }
        return ProgramAddonDispatch.Completed(response)
    }

    override fun reset() {
        handles.values
            .map(DisplayEndpoint::buffer)
            .distinct()
            .forEach { it.release(owner) }
        handles.clear()
        nextHandle = 1
    }

    private fun acquireSide(side: Int): HostResponse {
        if (side !in 0..5) return failure(HostFailureKind.OTHER, "Invalid display side")
        return resolveSide(side)?.let(::retain)
            ?: failure(HostFailureKind.UNAVAILABLE, "No display at that side")
    }

    private fun acquireNamed(name: String): HostResponse =
        when (val result = resolveName(name)) {
            is DisplayResolution.Found -> retain(result.endpoint)
            is DisplayResolution.Failed -> failure(result.kind, result.detail)
        }

    private fun retain(endpoint: DisplayEndpoint): HostResponse {
        if (!endpoint.valid()) return failure(HostFailureKind.UNAVAILABLE, "Display is unavailable")
        handles.entries.removeIf { (_, retained) ->
            (!retained.valid()).also { stale -> if (stale) retained.buffer.release(owner) }
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
            endpoint.buffer.writeAt(owner, endpoint::valid, x, y, text).toResponse()
        }

    private fun clear(handle: Int): HostResponse =
        withEndpoint(handle) { endpoint ->
            endpoint.buffer.clear(owner, endpoint::valid).toResponse()
        }

    private fun withEndpoint(
        handle: Int,
        action: (DisplayEndpoint) -> HostResponse,
    ): HostResponse {
        val endpoint = handles[handle] ?: return failure(HostFailureKind.INPUT_OUTPUT, "Unknown display handle")
        if (!endpoint.valid()) {
            handles.remove(handle)
            endpoint.buffer.release(owner)
            return failure(HostFailureKind.INPUT_OUTPUT, "Display handle is stale or disconnected")
        }
        return action(endpoint)
    }

    private fun DisplayWriteResult.toResponse(): HostResponse =
        when (this) {
            DisplayWriteResult.SUCCESS -> HostResponse.UnitSuccess
            DisplayWriteResult.BUSY -> failure(HostFailureKind.UNAVAILABLE, "Another computer is writing this display")
            DisplayWriteResult.DISCONNECTED -> failure(HostFailureKind.INPUT_OUTPUT, "Display is disconnected")
            DisplayWriteResult.INVALID -> failure(HostFailureKind.OTHER, "Display write is outside the grid or contains invalid text")
        }

    private fun ProgramAddonRequest.intAt(index: Int): Int? = (arguments.getOrNull(index) as? VmValue.I32)?.value

    private fun ProgramAddonRequest.stringAt(index: Int): String? = (arguments.getOrNull(index) as? VmValue.StringValue)?.value

    companion object {
        private const val MAXIMUM_HANDLES = 16

        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "display", 1, 0),
                listOf(
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.STRING), HostValueType.I32, true),
                    HostOperationSchema(
                        listOf(HostValueType.I32, HostValueType.I32, HostValueType.I32, HostValueType.STRING),
                        HostValueType.UNIT,
                        true,
                    ),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.UNIT, true),
                ),
            )

        private fun malformed() = failure(HostFailureKind.OTHER, "Malformed display request")

        private fun failure(
            kind: HostFailureKind,
            detail: String,
        ) = HostResponse.Failure(kind, detail)
    }
}
