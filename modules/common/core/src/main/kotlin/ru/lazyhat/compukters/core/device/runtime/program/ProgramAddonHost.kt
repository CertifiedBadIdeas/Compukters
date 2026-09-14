/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema

internal object EmptyProgramAddonHost : ProgramAddonHost {
    override val capabilitySchemas: List<HostCapabilitySchema> = emptyList()

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch = error("an addon request cannot target the empty host")

    override fun reset() = Unit
}

fun interface ProgramAddonRequestPort {
    fun submit(request: ProgramAddonRequest): Boolean
}

fun programAddonHostOf(hosts: List<ProgramAddonHost>): ProgramAddonHost =
    when (hosts.size) {
        0 -> EmptyProgramAddonHost
        1 -> hosts.single()
        else -> CompositeProgramAddonHost(hosts)
    }

private class CompositeProgramAddonHost(
    hosts: List<ProgramAddonHost>,
) : ProgramAddonHost {
    private val hosts = hosts.toList()
    override val capabilitySchemas: List<HostCapabilitySchema> = this.hosts.flatMap(ProgramAddonHost::capabilitySchemas)
    private val routes =
        buildMap {
            this@CompositeProgramAddonHost.hosts.forEach { host ->
                host.capabilitySchemas.forEach { schema ->
                    val key = CapabilityKey(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor)
                    check(put(key, host) == null) { "duplicate addon capability: $key" }
                }
            }
        }

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        val key = CapabilityKey(request.capability.namespace, request.capability.name, request.capability.abiMajor)
        val host = requireNotNull(routes[key]) { "addon capability is not registered: $key" }
        return host.dispatch(request)
    }

    override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0) { "maximum addon completions must be positive" }
        val completions = mutableListOf<ProgramAddonCompletion>()
        hosts.forEach { host ->
            val remaining = maximumCompletions - completions.size
            if (remaining == 0) return@forEach
            val polled = host.poll(remaining)
            require(polled.size <= remaining) { "addon host exceeded its completion budget" }
            completions += polled
        }
        return completions
    }

    override fun reset() = applyToAll(hosts, ProgramAddonHost::reset)

    override fun close() = applyToAll(hosts.asReversed(), ProgramAddonHost::close)

    private fun applyToAll(
        targets: List<ProgramAddonHost>,
        action: (ProgramAddonHost) -> Unit,
    ) {
        var failure: Throwable? = null
        targets.forEach { host ->
            try {
                action(host)
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure?.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    private data class CapabilityKey(
        val namespace: String,
        val name: String,
        val abiMajor: Int,
    )
}
