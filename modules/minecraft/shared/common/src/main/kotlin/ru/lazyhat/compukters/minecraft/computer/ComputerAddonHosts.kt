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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundle
import ru.lazyhat.compukters.addon.api.AddonGuestApiCatalog
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.program.programAddonHostOf
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import java.util.concurrent.CopyOnWriteArrayList

fun interface ComputerAddonHostFactory {
    fun create(
        level: ServerLevel,
        position: BlockPos,
        state: BlockState,
    ): ProgramAddonHost?
}

object ComputerAddonHosts {
    private val registrations = CopyOnWriteArrayList<Registration>()
    private var guestApiCatalog = AddonGuestApiCatalog.empty()

    @Synchronized
    fun register(
        factory: ComputerAddonHostFactory,
        guestApiBundles: List<AddonGuestApiBundle> = emptyList(),
    ) {
        require(registrations.none { it.factory == factory }) { "computer addon host factory is already registered" }
        val updatedCatalog = AddonGuestApiCatalog.of(guestApiCatalog.bundles + guestApiBundles)
        registrations += Registration(factory, guestApiBundles)
        guestApiCatalog = updatedCatalog
    }

    @Synchronized
    fun availableGuestApiCatalog(): AddonGuestApiCatalog = guestApiCatalog

    internal fun create(
        level: ServerLevel,
        position: BlockPos,
        state: BlockState,
    ): ProgramAddonHost {
        val hosts =
            registrations.mapNotNull { registration ->
                registration.factory.create(level, position, state)?.also { host ->
                    requireAddonCapabilitySchemas(registration.guestApiBundles, host.capabilitySchemas)
                }
            }
        return try {
            programAddonHostOf(hosts)
        } catch (failure: Throwable) {
            hosts.asReversed().forEach { host -> runCatching(host::close).onFailure(failure::addSuppressed) }
            throw failure
        }
    }

    private data class Registration(
        val factory: ComputerAddonHostFactory,
        val guestApiBundles: List<AddonGuestApiBundle>,
    )
}

internal fun requireAddonCapabilitySchemas(
    bundles: List<AddonGuestApiBundle>,
    schemas: List<HostCapabilitySchema>,
) {
    val actual = schemas.associateBy(::capabilityKey)
    bundles.flatMap(AddonGuestApiBundle::capabilitySchemas).forEach { expected ->
        require(actual[capabilityKey(expected)] == expected.toHostSchema()) {
            "addon host does not provide registered capability ${expected.identity.namespace}:${expected.identity.name}"
        }
    }
}

private fun capabilityKey(schema: HostCapabilitySchema) = Triple(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor)

private fun capabilityKey(schema: AddonCapabilitySchema) = Triple(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor)

private fun AddonCapabilitySchema.toHostSchema(): HostCapabilitySchema =
    HostCapabilitySchema(
        ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity(
            identity.namespace,
            identity.name,
            identity.abiMajor,
            identity.abiMinor,
        ),
        operations.map { operation ->
            ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema(
                operation.arguments.map { argument -> argument.toHostValueType() },
                operation.result.toHostValueType(),
                operation.asynchronous,
            )
        },
    )

private fun AddonCapabilityValueType.toHostValueType(): HostValueType = HostValueType.valueOf(name)
