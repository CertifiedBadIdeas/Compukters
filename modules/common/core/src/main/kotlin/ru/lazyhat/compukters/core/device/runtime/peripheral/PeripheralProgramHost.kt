/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

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

/** The base capability transports discovery tokens; device operations stay in their owning addon capability. */
class PeripheralProgramHost(
    private val session: PeripheralSession<*>,
) : ProgramAddonHost {
    override val capabilitySchemas: List<HostCapabilitySchema> = listOf(SCHEMA)

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        val response =
            try {
                if (request.capability != SCHEMA.identity) {
                    malformed()
                } else {
                    when (request.operation) {
                        0 -> {
                            count(request, 1)
                            HostResponse.IntSuccess(session.openSnapshot(string(request, 0)))
                        }

                        1 -> {
                            count(request, 1)
                            HostResponse.IntSuccess(session.snapshotSize(int(request, 0)))
                        }

                        2 -> {
                            count(request, 2)
                            HostResponse.IntSuccess(session.snapshotGet(int(request, 0), int(request, 1)))
                        }

                        3 -> {
                            count(request, 1)
                            session.closeSnapshot(int(request, 0))
                            HostResponse.UnitSuccess
                        }

                        4 -> {
                            count(request, 2)
                            HostResponse.IntSuccess(session.at(string(request, 0), int(request, 1)))
                        }

                        5 -> {
                            count(request, 2)
                            HostResponse.IntSuccess(session.named(string(request, 0), string(request, 1)))
                        }

                        else -> {
                            malformed()
                        }
                    }
                }
            } catch (failure: PeripheralFailure) {
                HostResponse.Failure(failure.kind, failure.message)
            }
        return ProgramAddonDispatch.Completed(response)
    }

    override fun reset() = session.reset()

    private fun count(
        request: ProgramAddonRequest,
        expected: Int,
    ) {
        if (request.arguments.size != expected) malformed()
    }

    private fun int(
        request: ProgramAddonRequest,
        index: Int,
    ): Int = (request.arguments[index] as? VmValue.I32)?.value ?: malformed()

    private fun string(
        request: ProgramAddonRequest,
        index: Int,
    ): String = (request.arguments[index] as? VmValue.StringValue)?.value ?: malformed()

    private fun malformed(): Nothing = throw PeripheralFailure(HostFailureKind.OTHER, "Malformed peripheral discovery request")

    companion object {
        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "peripheral", 1, 0),
                listOf(
                    HostOperationSchema(listOf(HostValueType.STRING), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.I32, HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.UNIT, true),
                    HostOperationSchema(listOf(HostValueType.STRING, HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.STRING, HostValueType.STRING), HostValueType.I32, true),
                ),
            )
    }
}
