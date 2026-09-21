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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames

internal object PeripheralCableGameTestScenario {
    private var providerRegistered = false

    fun run(helper: GameTestHelper) {
        registerProvider()
        val computer = BlockPos(2, 2, 2)
        val junction = BlockPos(4, 2, 2)
        val firstDevice = BlockPos(5, 2, 3)
        val secondDevice = BlockPos(5, 2, 1)
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()

        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        listOf(BlockPos(3, 2, 2), junction, BlockPos(4, 2, 3), BlockPos(4, 2, 1)).forEach { position ->
            helper.setBlock(position, cable)
        }
        helper.setBlock(firstDevice, Blocks.BARREL)
        helper.setBlock(secondDevice, Blocks.DROPPER)

        val level = helper.level
        val firstIdentity =
            ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(firstDevice), FIRST_DEVICE_KEY)
        val secondIdentity =
            ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(secondDevice), SECOND_DEVICE_KEY)
        ComputerPeripheralNames.setName(level, firstIdentity, "input")
        ComputerPeripheralNames.setName(level, secondIdentity, "output")

        helper
            .startSequence()
            .thenExecute {
                assertFound(helper, computer, "input", helper.absolutePos(firstDevice))
                assertFound(helper, computer, "output", helper.absolutePos(secondDevice))
            }.thenExecute {
                helper.setBlock(junction, Blocks.AIR)
                assertStatus(helper, computer, "input", ComputerPeripheralLookupStatus.MISSING)
                assertStatus(helper, computer, "output", ComputerPeripheralLookupStatus.MISSING)
            }.thenExecute {
                helper.setBlock(junction, cable)
                assertFound(helper, computer, "input", helper.absolutePos(firstDevice))
                assertFound(helper, computer, "output", helper.absolutePos(secondDevice))
            }.thenExecute {
                ComputerPeripheralNames.setName(level, secondIdentity, "input")
                assertStatus(helper, computer, "input", ComputerPeripheralLookupStatus.AMBIGUOUS)
            }.thenSucceed()
    }

    @Synchronized
    private fun registerProvider() {
        if (providerRegistered) return
        ComputerAddonHosts.register(
            ComputerAddonHostFactory { _, _, _ -> null },
            registrationIdentity = PeripheralCableGameTestScenario,
            peripheralProvider =
                ComputerPeripheralProvider { level, position, _ ->
                    when (level.getBlockState(position).block) {
                        Blocks.BARREL -> ComputerPeripheralIdentity(TEST_PROVIDER_ID, position.immutable(), FIRST_DEVICE_KEY)
                        Blocks.DROPPER -> ComputerPeripheralIdentity(TEST_PROVIDER_ID, position.immutable(), SECOND_DEVICE_KEY)
                        else -> null
                    }
                },
        )
        providerRegistered = true
    }

    private fun assertFound(
        helper: GameTestHelper,
        computer: BlockPos,
        name: String,
        expectedAnchor: BlockPos,
    ) {
        val result = ComputerPeripheralLookup.find(helper.level, helper.absolutePos(computer), TEST_PROVIDER_ID, name)
        helper.assertTrue(result.status == ComputerPeripheralLookupStatus.FOUND, "$name was not found: ${result.status}")
        helper.assertTrue(result.identity?.anchor == expectedAnchor, "$name resolved to ${result.identity?.anchor}")
    }

    private fun assertStatus(
        helper: GameTestHelper,
        computer: BlockPos,
        name: String,
        expected: ComputerPeripheralLookupStatus,
    ) {
        val result = ComputerPeripheralLookup.find(helper.level, helper.absolutePos(computer), TEST_PROVIDER_ID, name)
        helper.assertTrue(result.status == expected, "$name expected $expected, got ${result.status}")
    }

    private const val TEST_PROVIDER_ID = "compukters_gametest"
    private const val FIRST_DEVICE_KEY = "barrel"
    private const val SECOND_DEVICE_KEY = "dropper"
}
