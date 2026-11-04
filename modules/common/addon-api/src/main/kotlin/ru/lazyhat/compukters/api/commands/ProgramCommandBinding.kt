/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.commands

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

/** An explicit immediate Unit operation, normally emitted from an addon's canonical binding. */
class ProgramCommandBinding(
    val symbol: String,
    val capability: CapabilityIdentity,
    val operation: Int,
    arguments: List<HostValueType>,
    private val execute: (List<VmValue>) -> HostResponse,
) {
    val arguments: List<HostValueType> = arguments.toList()

    init {
        require(symbol.length in 1..256 && symbol.all { it.isLetterOrDigit() || it in "_.$" })
        require(operation >= 0 && this.arguments.size <= 32)
        require(this.arguments.none { it == HostValueType.UNIT || it == HostValueType.RECORD })
    }

    fun execute(arguments: List<VmValue>): HostResponse {
        require(arguments.map(CommandBatchCodec::typeOf) == this.arguments) { "Command argument types do not match" }
        return execute.invoke(arguments)
    }
}

/** Implemented by SDK-generated hosts that explicitly opt immediate operations into batching. */
interface ProgramCommandProvider {
    val commandBindings: List<ProgramCommandBinding>
}
