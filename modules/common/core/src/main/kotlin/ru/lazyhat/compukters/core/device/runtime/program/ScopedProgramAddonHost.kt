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

/** One host instance per live process, created lazily; suspended parents keep their resources. */
fun programScopedAddonHostOf(factory: () -> ProgramAddonHost): ProgramAddonHost = ScopedProgramAddonHost(factory)

private class ScopedProgramAddonHost(
    private val factory: () -> ProgramAddonHost,
) : ProgramAddonHost {
    private val hosts = mutableMapOf<Long, ProgramAddonHost?>()
    private var lastProgramId = 0L
    private val initial = factory()
    override val capabilitySchemas = initial.capabilitySchemas.toList()

    init {
        hosts[0] = initial
    }

    override fun programStarted(programId: Long) {
        require(programId > lastProgramId) { "addon process identity must not be reused" }
        require(hosts.size < 32) { "addon process depth limit exceeded" }
        lastProgramId = programId
        hosts[programId] = null
    }

    override fun programStopped(programId: Long) {
        require(programId != 0L && hosts.containsKey(programId)) { "unknown addon process scope" }
        hosts.remove(programId)?.close()
    }

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        require(hosts.containsKey(request.programId)) { "addon request targets a retired process" }
        val host =
            hosts[request.programId] ?: factory().also { created ->
                try {
                    require(created.capabilitySchemas == capabilitySchemas) { "addon process schemas changed" }
                    hosts[request.programId] = created
                } catch (failure: Throwable) {
                    runCatching(created::close).onFailure(failure::addSuppressed)
                    throw failure
                }
            }
        return host.dispatch(request)
    }

    override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0)
        val result = mutableListOf<ProgramAddonCompletion>()
        hosts.values.filterNotNull().forEach { host ->
            val remaining = maximumCompletions - result.size
            if (remaining == 0) return@forEach
            val polled = host.poll(remaining)
            require(polled.size <= remaining) { "addon process exceeded completion budget" }
            result += polled
        }
        return result
    }

    override fun reset() {
        val previous = hosts.values.filterNotNull().asReversed()
        hosts.clear()
        hosts[0] = null
        lastProgramId = 0
        var failure: Throwable? = null
        previous.forEach { host ->
            try {
                host.close()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure?.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
}
