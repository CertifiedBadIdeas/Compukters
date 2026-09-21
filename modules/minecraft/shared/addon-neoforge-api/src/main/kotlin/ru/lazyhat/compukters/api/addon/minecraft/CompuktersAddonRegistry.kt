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

package ru.lazyhat.compukters.api.addon.minecraft

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundle
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider

class CompuktersComputerContext internal constructor(
    val level: ServerLevel,
    val position: BlockPos,
    private val facing: Direction,
) {
    fun adjacentDirection(side: Int): Direction? =
        when (side) {
            0 -> facing
            1 -> facing.opposite
            2 -> facing.counterClockWise
            3 -> facing.clockWise
            4 -> Direction.UP
            5 -> Direction.DOWN
            else -> null
        }
}

fun interface CompuktersAddonHostFactory {
    fun create(context: CompuktersComputerContext): ProgramAddonHost?
}

class CompuktersPeripheralContact internal constructor(
    val level: ServerLevel,
    val position: BlockPos,
    val contactedFace: Direction,
)

data class CompuktersPeripheralDevice(
    val anchor: BlockPos,
    val deviceKey: String = "",
) {
    init {
        require(deviceKey.length <= 128) {
            "peripheral device key exceeds 128 characters"
        }
        require(deviceKey.all { it.code in 0x20..0x7e }) {
            "peripheral device key must contain printable ASCII characters only"
        }
    }
}

fun interface CompuktersPeripheralProvider {
    fun resolve(contact: CompuktersPeripheralContact): CompuktersPeripheralDevice?
}

object CompuktersAddonRegistry {
    @JvmStatic
    fun register(
        guestApi: AddonGuestApiBundle,
        factory: CompuktersAddonHostFactory,
    ) = registerInternal(guestApi, factory, null)

    @JvmStatic
    fun register(
        guestApi: AddonGuestApiBundle,
        factory: CompuktersAddonHostFactory,
        peripheralProvider: CompuktersPeripheralProvider,
    ) = registerInternal(guestApi, factory, peripheralProvider)

    private fun registerInternal(
        guestApi: AddonGuestApiBundle,
        factory: CompuktersAddonHostFactory,
        peripheralProvider: CompuktersPeripheralProvider?,
    ) {
        val providerId = guestApi.identity.id
        ComputerAddonHosts.register(
            ComputerAddonHostFactory { level, position, state ->
                factory.create(CompuktersComputerContext(level, position, state.getValue(ComputerBlock.FACING)))
            },
            listOf(guestApi),
            factory,
            peripheralProvider?.let { provider ->
                ComputerPeripheralProvider { level, position, contactedFace ->
                    provider.resolve(CompuktersPeripheralContact(level, position, contactedFace))?.let { device ->
                        ComputerPeripheralIdentity(providerId, device.anchor.immutable(), device.deviceKey)
                    }
                }
            },
        )
    }
}
