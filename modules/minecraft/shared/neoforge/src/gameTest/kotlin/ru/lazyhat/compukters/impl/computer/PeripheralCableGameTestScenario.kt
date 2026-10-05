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
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableBlock
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer

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
        verifyDirectContacts(helper, computer)
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
            .thenExecuteAfter(2) {
                helper.assertTrue(
                    helper.getBlockState(BlockPos(4, 2, 3)).getValue(PeripheralCableBlock.EAST),
                    "cable did not connect to the first compatible block",
                )
                helper.assertTrue(
                    helper.getBlockState(BlockPos(4, 2, 1)).getValue(PeripheralCableBlock.EAST),
                    "cable did not connect to the second compatible block",
                )
                assertFound(helper, computer, "input", helper.absolutePos(firstDevice))
                assertFound(helper, computer, "output", helper.absolutePos(secondDevice))
                verifyConfiguratorSave(helper, computer, firstIdentity, secondIdentity)
                val adjacent = computer.above()
                helper.setBlock(adjacent, Blocks.BARREL)
                ComputerPeripheralNames.setName(
                    level,
                    ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(adjacent), FIRST_DEVICE_KEY),
                    "input",
                )
                assertStatus(helper, computer, "input", ComputerPeripheralLookupStatus.AMBIGUOUS)
                helper.setBlock(adjacent, Blocks.AIR)
                assertFound(helper, computer, "input", firstIdentity.anchor)
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

    private fun verifyDirectContacts(
        helper: GameTestHelper,
        computer: BlockPos,
    ) {
        val level = helper.level
        Direction.entries.forEach { direction ->
            val position = computer.relative(direction)
            helper.setBlock(position, Blocks.BARREL)
            val identity = ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(position), FIRST_DEVICE_KEY)
            val name = "adjacent_" + direction.serializedName
            ComputerPeripheralNames.setName(level, identity, name)
            assertFound(helper, computer, name, identity.anchor)
            helper.assertTrue(
                ComputerPeripheralLookup.isReachable(level, helper.absolutePos(computer), identity),
                "direct device on $direction was not reachable",
            )
            helper.setBlock(position, Blocks.AIR)
            assertStatus(helper, computer, name, ComputerPeripheralLookupStatus.MISSING)
            helper.assertTrue(
                !ComputerPeripheralLookup.isReachable(level, helper.absolutePos(computer), identity),
                "removed direct device on $direction remained reachable",
            )
            helper.setBlock(position, Blocks.BARREL)
            assertFound(helper, computer, name, identity.anchor)
            helper.setBlock(position, Blocks.AIR)
        }
        // Direct discovery must pass the contacted device face, not the computer-facing direction.
        listOf(Direction.DOWN, Direction.UP).forEach { direction ->
            val position = computer.relative(direction)
            helper.setBlock(position, Blocks.FURNACE)
            val identity = ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(position), FACE_DEVICE_KEY)
            ComputerPeripheralNames.setName(level, identity, "face_sensitive")
            assertStatus(
                helper,
                computer,
                "face_sensitive",
                if (direction == Direction.DOWN) {
                    ComputerPeripheralLookupStatus.FOUND
                } else {
                    ComputerPeripheralLookupStatus.MISSING
                },
            )
            helper.setBlock(position, Blocks.AIR)
        }
        val diagonal = computer.offset(1, 0, 1)
        helper.setBlock(diagonal, Blocks.BARREL)
        val diagonalIdentity = ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(diagonal), FIRST_DEVICE_KEY)
        ComputerPeripheralNames.setName(level, diagonalIdentity, "diagonal")
        assertStatus(helper, computer, "diagonal", ComputerPeripheralLookupStatus.MISSING)
        helper.setBlock(diagonal, Blocks.AIR)

        // One logical identity touched directly and through a cable must not become ambiguous.
        val adjacent = computer.east()
        helper.setBlock(adjacent, Blocks.BARREL)
        val identity = ComputerPeripheralIdentity(TEST_PROVIDER_ID, helper.absolutePos(adjacent), FIRST_DEVICE_KEY)
        ComputerPeripheralNames.setName(level, identity, "both_paths")
        helper.setBlock(computer.above(), CompuktersRegistry.PERIPHERAL_CABLE.get())
        helper.setBlock(adjacent.above(), CompuktersRegistry.PERIPHERAL_CABLE.get())
        assertFound(helper, computer, "both_paths", identity.anchor)
        helper.setBlock(computer.above(), Blocks.AIR)
        assertFound(helper, computer, "both_paths", identity.anchor)
        helper.setBlock(adjacent.above(), Blocks.AIR)
        helper.setBlock(adjacent, Blocks.AIR)
    }

    private fun verifyConfiguratorSave(
        helper: GameTestHelper,
        computer: BlockPos,
        firstIdentity: ComputerPeripheralIdentity,
        secondIdentity: ComputerPeripheralIdentity,
    ) {
        val player = helper.makeMockServerPlayerInLevel()
        player.setPos(firstIdentity.anchor.x + 0.5, firstIdentity.anchor.y + 0.5, firstIdentity.anchor.z + 0.5)
        val configurator = ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get())
        player.setItemInHand(InteractionHand.MAIN_HAND, configurator)
        val firstContext =
            PeripheralConfiguratorContext(
                firstIdentity.anchor,
                Direction.WEST,
            )
        val secondContext = firstContext.copy(position = secondIdentity.anchor)

        val renamed = PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, firstContext, "sensor")
        helper.assertTrue(renamed == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "configurator did not rename the target: $renamed")
        assertFound(helper, computer, "sensor", firstIdentity.anchor)
        helper.assertTrue(configurator.get(DataComponents.CUSTOM_NAME) == null, "configurator renamed itself")

        val conflict = PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, secondContext, "sensor")
        helper.assertTrue(conflict == PeripheralConfiguratorSaveResult.CONFLICT, "duplicate name was accepted: $conflict")
        assertFound(helper, computer, "output", secondIdentity.anchor)

        val restored = PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, firstContext, "input")
        helper.assertTrue(restored == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "configurator did not restore the fixture: $restored")
        assertFound(helper, computer, "input", firstIdentity.anchor)

        player.setPos(firstIdentity.anchor.x + 20.0, firstIdentity.anchor.y + 0.5, firstIdentity.anchor.z + 0.5)
        val remote = PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, firstContext, "remote")
        helper.assertTrue(remote == PeripheralConfiguratorSaveResult.OUT_OF_RANGE, "out-of-range change was accepted: $remote")
        assertFound(helper, computer, "input", firstIdentity.anchor)

        player.setPos(firstIdentity.anchor.x + 0.5, firstIdentity.anchor.y + 0.5, firstIdentity.anchor.z + 0.5)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Blocks.STONE))
        val wrongItem = PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, firstContext, "hijacked")
        helper.assertTrue(
            wrongItem == PeripheralConfiguratorSaveResult.INVALID_TARGET,
            "save without a configurator was accepted: $wrongItem",
        )
        assertFound(helper, computer, "input", firstIdentity.anchor)
    }

    @Synchronized
    private fun registerProvider() {
        if (providerRegistered) return
        ComputerAddonHosts.register(
            ComputerAddonHostFactory { _, _, _ -> null },
            registrationIdentity = PeripheralCableGameTestScenario,
            peripheralProvider =
                ComputerPeripheralProvider { level, position, contactedFace ->
                    if (level.getBlockEntity(position) == null) {
                        null
                    } else {
                        when (level.getBlockState(position).block) {
                            Blocks.BARREL -> {
                                ComputerPeripheralIdentity(TEST_PROVIDER_ID, position.immutable(), FIRST_DEVICE_KEY)
                            }

                            Blocks.DROPPER -> {
                                ComputerPeripheralIdentity(TEST_PROVIDER_ID, position.immutable(), SECOND_DEVICE_KEY)
                            }

                            Blocks.FURNACE -> {
                                if (contactedFace == Direction.UP) {
                                    ComputerPeripheralIdentity(TEST_PROVIDER_ID, position.immutable(), FACE_DEVICE_KEY)
                                } else {
                                    null
                                }
                            }

                            else -> {
                                null
                            }
                        }
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
    private const val FACE_DEVICE_KEY = "furnace_top"
}
