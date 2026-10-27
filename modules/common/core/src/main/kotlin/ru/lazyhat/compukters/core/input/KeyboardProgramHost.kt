/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.addon.ResourceCheckpointReader
import ru.lazyhat.compukters.api.addon.ResourceCheckpointWriter
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

interface KeyboardEndpoint {
    fun acquire(
        owner: Any,
        mask: Long,
    )

    fun snapshot(owner: Any): KeyboardFrame

    fun release(owner: Any)
}

class KeyboardUnavailable(
    message: String,
) : IllegalStateException(message)

/** A program owns its capture request; player consent and physical input remain world-owned. */
class KeyboardProgramHost(
    private val endpoint: KeyboardEndpoint,
) : ProgramAddonHost {
    override val capabilitySchemas = listOf(SCHEMA)
    private val owner = Any()
    private var nextHandle = 1
    private var handle = 0
    private var mask = 0L
    private var bound = false

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        if (request.capability != SCHEMA.identity || request.operation !in 0..2 || request.arguments.size != 1) {
            return ProgramAddonDispatch.Completed(HostResponse.Failure(HostFailureKind.OTHER, "Malformed keyboard request"))
        }
        val response =
            try {
                when (request.operation) {
                    0 -> {
                        val requested = (request.arguments.single() as? VmValue.I64)?.value
                        require(requested != null && requested != 0L) { "Choose at least one keyboard key" }
                        if (handle != 0) throw KeyboardUnavailable("This program already owns keyboard input")
                        check(nextHandle in 1 until Int.MAX_VALUE) { "Keyboard handle limit reached" }
                        endpoint.acquire(owner, requested)
                        bound = true
                        mask = requested
                        handle = nextHandle++
                        HostResponse.IntSuccess(handle)
                    }

                    else -> {
                        val requested = (request.arguments.single() as? VmValue.I32)?.value
                        if (requested == null || handle == 0 ||
                            requested != handle
                        ) {
                            throw KeyboardUnavailable("Keyboard handle is closed or stale")
                        }
                        if (request.operation == 1) {
                            if (!bound) {
                                endpoint.acquire(owner, mask)
                                bound = true
                            }
                            HostResponse.ByteArraySuccess(endpoint.snapshot(owner).encode())
                        } else {
                            reset()
                            HostResponse.UnitSuccess
                        }
                    }
                }
            } catch (failure: KeyboardUnavailable) {
                HostResponse.Failure(HostFailureKind.UNAVAILABLE, failure.message ?: "Keyboard unavailable")
            } catch (failure: IllegalArgumentException) {
                HostResponse.Failure(HostFailureKind.OTHER, failure.message ?: "Invalid keyboard input")
            }
        return ProgramAddonDispatch.Completed(response)
    }

    override fun checkpoint(): ByteArray =
        ResourceCheckpointWriter()
            .apply {
                int(1)
                int(nextHandle)
                int(handle)
                long(mask)
            }.finish()

    override fun restoreCheckpoint(state: ByteArray) {
        val reader = ResourceCheckpointReader(state)
        require(reader.int() == 1)
        val cursor = reader.int()
        val active = reader.int()
        val keys = reader.long()
        reader.finish()
        require(cursor > 0 && active in 0 until cursor && ((active == 0) == (keys == 0L)))
        reset()
        nextHandle = cursor
        handle = active
        mask = keys
        // A saved request survives; live player consent, held keys and edges never do.
    }

    override fun reset() {
        if (bound) endpoint.release(owner)
        bound = false
        handle = 0
        mask = 0L
    }

    override fun close() = reset()

    companion object {
        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "keyboard", 1, 0),
                listOf(
                    HostOperationSchema(listOf(HostValueType.I64), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.BYTE_ARRAY, true),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.UNIT, true),
                ),
            )
    }
}
