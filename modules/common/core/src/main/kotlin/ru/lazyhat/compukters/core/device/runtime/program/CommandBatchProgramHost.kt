/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.api.commands.ProgramCommandProvider
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

/** Install inside a program's addon scope. Each packet is one immediate server-thread delivery. */
fun withCommandBatches(delegate: ProgramAddonHost): ProgramAddonHost = CommandBatchProgramHost(delegate)

class CommandBatchProgramHost internal constructor(
    private val delegate: ProgramAddonHost,
) : ProgramAddonHost by delegate {
    private val bindings = (delegate as? ProgramCommandProvider)?.commandBindings.orEmpty().toList()
    private val routes = bindings.groupBy { it.symbol }
    override val capabilitySchemas = delegate.capabilitySchemas + SCHEMA

    init {
        require(delegate.capabilitySchemas.none { it.identity == SCHEMA.identity }) { "Duplicate command capability" }
        require(bindings.distinctBy { it.symbol to it.arguments }.size == bindings.size) { "Duplicate command binding" }
        bindings.forEach { binding ->
            val schema = delegate.capabilitySchemas.singleOrNull { it.identity == binding.capability }
            val operation = schema?.operations?.getOrNull(binding.operation)
            require(operation?.result == HostValueType.UNIT && operation.arguments == binding.arguments) {
                "Command binding does not match its registered capability"
            }
        }
    }

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        if (request.capability != SCHEMA.identity) return delegate.dispatch(request)
        val commands =
            try {
                require(request.operation == 0 && request.arguments.size == 1)
                CommandBatchCodec.decode((request.arguments.single() as VmValue.ByteArrayValue).value)
            } catch (_: IllegalArgumentException) {
                return failure("Malformed command batch")
            } catch (_: ClassCastException) {
                return failure("Malformed command batch")
            }
        val results =
            commands.map { command ->
                val types = command.arguments.map(CommandBatchCodec::typeOf)
                val binding = routes[command.symbol]?.singleOrNull { it.arguments == types }
                if (binding == null) {
                    HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Command binding is unavailable")
                } else {
                    try {
                        when (val response = binding.execute(command.arguments)) {
                            HostResponse.UnitSuccess, is HostResponse.Failure -> response
                            else -> HostResponse.Failure(HostFailureKind.OTHER, "Command handler must complete Unit")
                        }
                    } catch (_: Exception) {
                        HostResponse.Failure(HostFailureKind.OTHER, "Command handler failed")
                    }
                }
            }
        return ProgramAddonDispatch.Completed(HostResponse.ByteArraySuccess(CommandBatchCodec.encodeResults(results)))
    }

    private fun failure(detail: String) = ProgramAddonDispatch.Completed(HostResponse.Failure(HostFailureKind.OTHER, detail))

    companion object {
        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "commands", 1, 0),
                listOf(HostOperationSchema(listOf(HostValueType.BYTE_ARRAY), HostValueType.BYTE_ARRAY, asynchronous = true)),
            )
    }
}
