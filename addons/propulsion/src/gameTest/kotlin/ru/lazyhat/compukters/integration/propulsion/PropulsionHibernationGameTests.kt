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

package ru.lazyhat.compukters.integration.propulsion

import dev.propulsionteam.propulsionsimulated.content.thruster.thruster.creative_thruster.CreativeThrusterBlockEntity
import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.creative_vector_thruster.CreativeVectorThrusterBlockEntity
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType
import dev.ryanhcode.sable.companion.math.BoundingBox3i
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import net.minecraft.util.Unit as MinecraftUnit

@PrefixGameTestTemplate(false)
object PropulsionHibernationGameTests {
    @JvmStatic
    @GameTest(batch = "propulsion_hibernation", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 200_000)
    fun engineCommandsResumeAcrossCarrierReload(helper: GameTestHelper) = run(helper, false, false)

    @JvmStatic
    @GameTest(
        batch = "propulsion_hibernation_replaced",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 200_000,
    )
    fun replacementEngineKeepsSavedHandleStale(helper: GameTestHelper) = run(helper, false, true)

    @JvmStatic
    @GameTest(
        batch = "propulsion_hibernation_sable",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 200_000,
    )
    fun constructionReloadResumesEngineCommands(helper: GameTestHelper) = run(helper, true, false)

    @JvmStatic
    @GameTest(
        batch = "propulsion_hibernation_conflict",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 200_000,
    )
    fun conflictingWriterRollsBackPartiallyRestoredControl(helper: GameTestHelper) = run(helper, false, false, conflict = true)

    @JvmStatic
    @GameTest(
        batch = "propulsion_hibernation_store",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 200_000,
    )
    fun engineCommandsResumeAcrossWorldStoreRestart(helper: GameTestHelper) = run(helper, false, false, stopStore = true)

    private fun run(
        helper: GameTestHelper,
        construction: Boolean,
        replaceVector: Boolean,
        conflict: Boolean = false,
        stopStore: Boolean = false,
    ) {
        val level = helper.level
        val relative = BlockPos(2, 3, 2)
        val anchor = helper.absolutePos(relative)
        var origin = anchor
        helper.setBlock(relative, CompuktersRegistry.COMPUTER.get())

        fun engineBlock(id: String) = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", id))
        helper.setBlock(
            relative.below(),
            engineBlock("creative_vector_thruster").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
        )
        helper.setBlock(
            relative.east(),
            engineBlock("creative_thruster").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
        )
        val container = requireNotNull(SubLevelContainer.getContainer(level))
        var body: ServerSubLevel? = null
        helper.testInfo.addListener(
            object : GameTestListener {
                private fun cleanup() {
                    body?.let {
                        container.removeForceLoadTicket(it, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                        if (!it.isRemoved) container.removeSubLevel(it, SubLevelRemovalReason.REMOVED)
                        container.processSubLevelRemovals()
                    }
                }

                override fun testStructureLoaded(info: GameTestInfo) = Unit

                override fun testPassed(
                    info: GameTestInfo,
                    runner: GameTestRunner,
                ) = cleanup()

                override fun testFailed(
                    info: GameTestInfo,
                    runner: GameTestRunner,
                ) = cleanup()

                override fun testAddedForRerun(
                    info: GameTestInfo,
                    rerun: GameTestInfo,
                    runner: GameTestRunner,
                ) = cleanup()
            },
        )
        val sequence = helper.startSequence()
        if (construction) {
            sequence.thenExecuteAfter(5) {
                body =
                    SubLevelAssemblyHelper.assembleBlocks(
                        level,
                        anchor,
                        listOf(anchor, anchor.below(), anchor.east()),
                        BoundingBox3i(anchor.x, anchor.y - 1, anchor.z, anchor.x + 2, anchor.y + 1, anchor.z + 1),
                    )
                container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                origin = requireNotNull(body).plot.centerBlock
            }
        }
        sequence.thenExecuteAfter(10) {
            nameDevice(helper, origin.below(), "vector")
            nameDevice(helper, origin.east(), "ordinary")
        }
        val guest = GuestComputerScenario(helper, relative) { origin }
        val contender =
            if (conflict) {
                val contenderPosition = relative.below().west()
                helper.setBlock(contenderPosition, CompuktersRegistry.COMPUTER.get())
                GuestComputerScenario(helper, contenderPosition)
            } else {
                null
            }
        contender?.prepare(
            sequence,
            """
            import propulsion.thrusters.CreativeVectorThruster
            fun main() {
                println("contender-ready")
                readln()
                val engine = CreativeVectorThruster.named("vector")
                engine.setThrottle(0.25)
                engine.setVector(-0.5, 0.5)
                engine.setThrustKn(3.0)
                println("contender-owned")
                readln()
            }
            """.trimIndent(),
        )
        contender?.awaitMarker(sequence, "contender-ready")
        val source =
            """
            import propulsion.thrusters.CreativeVectorThruster
            import propulsion.thrusters.CreativeThruster
            fun main() {
                val vector = CreativeVectorThruster.named("vector")
                val ordinary = CreativeThruster.named("ordinary")
                val retainedTarget = 123.0
                vector.setThrottle(0.625)
                vector.setVector(0.125, -0.25)
                vector.setThrustKn(5.0)
                ordinary.setThrottle(0.375)
                ordinary.setThrustPercent(40)
                println("hibernation-ready")
                readln()
                check(retainedTarget == 123.0)
                check(ordinary.state().throttle == 0.375)
                VECTOR_CHECK
                println("hibernation-continued")
                readln()
            }
            """.trimIndent().replace(
                "VECTOR_CHECK",
                if (replaceVector) {
                    """
                    var stale = false
                    try { vector.state() } catch (expected: compukter.io.IOException) { stale = true }
                    check(stale)
                    """.trimIndent()
                } else {
                    """
                    check(vector.state().throttle == 0.625)
                    vector.setVector(-0.125, 0.25)
                    """.trimIndent()
                },
            )
        guest.prepare(sequence, source)
        guest.awaitMarker(sequence, "hibernation-ready")
        var saved: SubLevelData? = null
        var identity: Any? = null
        var pendingComputer: BlockEntity? = null
        sequence.thenExecute {
            val computer = level.getBlockEntity(origin) as ComputerBlockEntity
            identity = computer.computerId()
            if (construction) {
                val previous = requireNotNull(body)
                saved = SubLevelSerializer.toData(previous, emptyList())
                container.removeForceLoadTicket(previous, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                container.removeSubLevel(previous, SubLevelRemovalReason.UNLOADED)
                container.processSubLevelRemovals()
                helper.assertTrue(previous.isRemoved, "Construction did not unload")
            } else {
                val computerData = computer.saveWithFullMetadata(level.registryAccess())
                val ordinary = level.getBlockEntity(origin.east()) as CreativeThrusterBlockEntity
                val vector = level.getBlockEntity(origin.below()) as CreativeVectorThrusterBlockEntity
                val ordinaryData = ordinary.saveWithFullMetadata(level.registryAccess())
                val vectorData = vector.saveWithFullMetadata(level.registryAccess())
                if (stopStore) NeoForgeWorldFileSystemStores.onServerStopping(ServerStoppingEvent(level.server))
                level.removeBlockEntity(origin) // Capture commands before engine removal or lease validation.
                level.removeBlockEntity(origin.east())
                level.removeBlockEntity(origin.below())
                val loadedOrdinary = CreativeThrusterBlockEntity(origin.east(), ordinary.blockState)
                loadedOrdinary.loadWithComponents(ordinaryData, level.registryAccess())
                val loadedVector = CreativeVectorThrusterBlockEntity(origin.below(), vector.blockState)
                if (!replaceVector) loadedVector.loadWithComponents(vectorData, level.registryAccess())
                level.setBlockEntity(loadedOrdinary)
                level.setBlockEntity(loadedVector)
                pendingComputer = requireNotNull(BlockEntity.loadStatic(origin, computer.blockState, computerData, level.registryAccess()))
                if (!conflict) level.setBlockEntity(requireNotNull(pendingComputer))
            }
        }
        if (conflict) {
            requireNotNull(contender).resume(sequence)
            contender.awaitMarker(sequence, "contender-owned")
            sequence.thenExecute { level.setBlockEntity(requireNotNull(pendingComputer)) }
        }
        if (construction) {
            sequence.thenExecute {
                body = requireNotNull(SubLevelSerializer.fullyLoad(level, requireNotNull(saved)))
                container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                origin = requireNotNull(body).plot.centerBlock
                helper.assertTrue(!container.physicsSystem().paused, "Physics paused during restore")
            }
        }
        sequence.thenWaitUntil {
            val computer = level.getBlockEntity(origin) as? ComputerBlockEntity
            helper.assertTrue(computer?.runtimeState == ProgramComputerState.WaitingForInput, "Computer is still restoring")
            helper.assertTrue(computer!!.computerId() == identity, "Reload changed ComputerId")
            val ordinary = level.getBlockEntity(origin.east()) as CreativeThrusterBlockEntity
            val vector = level.getBlockEntity(origin.below()) as CreativeVectorThrusterBlockEntity
            if (conflict) {
                helper.assertTrue(!PropulsionGuestIntegration.isControlled(ordinary), "Partial ordinary lease leaked after vector conflict")
                helper.assertTrue(
                    vector.throttle == 0.25f && vector.targetVectorX == -0.5f && vector.targetVectorY == 0.5f,
                    "Restore overwrote competing writer",
                )
            } else {
                helper.assertTrue(ordinary.throttle == 0.375f && ordinary.thrustConfig == 39, "Ordinary engine commands did not restore")
            }
            if (!conflict && replaceVector) {
                helper.assertTrue(!PropulsionGuestIntegration.isControlled(vector), "Replacement engine was commandeered")
            } else if (!conflict) {
                helper.assertTrue(
                    vector.throttle == 0.625f && vector.targetVectorX == 0.125f && vector.targetVectorY == -0.25f,
                    "Vector commands did not restore",
                )
                helper.assertTrue(vector.hasPeripheralThrustOverride(), "Thrust override did not restore")
                helper.assertTrue(
                    (vector as CreativeVectorThrustAccess).`compukters$peripheralThrustOutput`() ==
                        (
                            5.0 *
                                dev.propulsionteam.propulsionsimulated.PropulsionConfig
                                    .getThrustUnitsPerKnOrDefault()
                        ).toFloat(),
                    "Restored thrust differs",
                )
            }
        }
        if (conflict) {
            guest.awaitMarker(sequence, ">")
            guest.inspectTerminal(sequence) { text ->
                helper.assertTrue(!text.contains("hibernation-ready"), "Control conflict did not cold boot the computer")
            }
            requireNotNull(contender).terminate(sequence)
        } else {
            guest.resume(sequence)
            guest.awaitMarker(sequence, "hibernation-continued")
            guest.terminate(sequence)
        }
        sequence.thenWaitUntil {
            helper.assertTrue(
                !PropulsionGuestIntegration.isControlled(requireNotNull(level.getBlockEntity(origin.east()))),
                "Ordinary lease leaked",
            )
            helper.assertTrue(
                !PropulsionGuestIntegration.isControlled(requireNotNull(level.getBlockEntity(origin.below()))),
                "Vector lease leaked",
            )
        }
        sequence.thenSucceed()
    }

    private fun nameDevice(
        helper: GameTestHelper,
        position: BlockPos,
        name: String,
    ) {
        val player = FakePlayerFactory.getMinecraft(helper.level)
        player.setPos(position.x + 0.5, position.y + 0.5, position.z + 0.5)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
        helper.assertTrue(
            PeripheralConfiguratorServer.save(
                player,
                InteractionHand.MAIN_HAND,
                PeripheralConfiguratorContext(position, Direction.WEST),
                name,
            ) ==
                PeripheralConfiguratorSaveResult.NAMED_DEVICE,
            "Could not name $name engine",
        )
    }
}
