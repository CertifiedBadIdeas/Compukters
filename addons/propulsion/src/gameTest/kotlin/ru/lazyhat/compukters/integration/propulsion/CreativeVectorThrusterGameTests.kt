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

import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.creative_vector_thruster.CreativeVectorThrusterBlockEntity
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType
import dev.ryanhcode.sable.companion.math.BoundingBox3i
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
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
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableBlock
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import net.minecraft.util.Unit as MinecraftUnit

@PrefixGameTestTemplate(false)
object CreativeVectorThrusterGameTests {
    @JvmStatic
    @GameTest(batch = "propulsion_vector", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun guestVectorControlLifetime(helper: GameTestHelper) {
        val first = BlockPos(2, 2, 3)
        val second = BlockPos(4, 2, 5)
        val link = BlockPos(3, 2, 3)
        val engine = BlockPos(5, 2, 3)
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        helper.setBlock(first, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(second, CompuktersRegistry.COMPUTER.get())
        listOf(link, BlockPos(4, 2, 3), BlockPos(4, 2, 4)).forEach { helper.setBlock(it, cable) }
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        helper.assertTrue(block != Blocks.AIR, "Creative Vector Thruster is not registered")
        helper.setBlock(engine, block)
        helper.setBlock(engine.east(), Blocks.REDSTONE_BLOCK)
        val thruster = helper.getBlockEntity(engine) as CreativeVectorThrusterBlockEntity
        val owner = GuestComputerScenario(helper, first)
        val contender = GuestComputerScenario(helper, second)
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            helper.assertTrue(
                helper.getBlockState(BlockPos(4, 2, 3)).getValue(PeripheralCableBlock.EAST),
                "Cable did not connect to Creative Vector Thruster",
            )
            nameDevice(helper, engine)
        }
        owner.prepare(sequence, OWNER)
        owner.awaitMarker(sequence, "vector-owned")
        sequence.thenExecute {
            helper.assertTrue(thruster.throttle == 0.375f, "Vector throttle did not reach actual block")
            helper.assertTrue(thruster.hasPeripheralThrustOverride(), "Custom vector thrust was not set")
            // Live redstone changes must be retained without overwriting the program's steering.
            thruster.westLink.setReceivedStrength(3)
            thruster.eastLink.setReceivedStrength(9)
            thruster.downLink.setReceivedStrength(12)
            thruster.upLink.setReceivedStrength(3)
            helper.assertTrue(thruster.targetVectorX == 0.6f && thruster.targetVectorY == -0.4f, "Redstone overwrote owned steering")
            val saved = thruster.saveWithFullMetadata(helper.level.registryAccess())
            helper.assertTrue(saved.getBoolean("compukters_propulsion:transient_control"), "Vector save lacks transient marker")
            val copy = CreativeVectorThrusterBlockEntity(helper.absolutePos(engine), thruster.blockState)
            copy.loadWithComponents(saved, helper.level.registryAccess())
            helper.assertTrue(!copy.hasPeripheralThrustOverride(), "NBT copy retained custom thrust")
            helper.assertTrue(
                copy.targetVectorX == -0.4f && copy.targetVectorY == 0.6f,
                "NBT copy retained program steering or lost redstone",
            )
            copy.setControlMode(dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity.ControlMode.PERIPHERAL)
            helper.assertTrue(copy.throttle == 0f, "NBT copy retained digital throttle")
            helper.assertTrue(
                thruster.hasPeripheralThrustOverride() && thruster.throttle == 0.375f && thruster.targetVectorX == 0.6f,
                "Saving modified live control",
            )
            val packet = thruster.getUpdateTag(helper.level.registryAccess())
            helper.assertTrue(!packet.getBoolean("compukters_propulsion:transient_control"), "Client packet marked transient")
            helper.assertTrue(
                packet.getFloat("TargetVectorX") == 0.6f && packet.getFloat("PeripheralThrustOutput") >= 0f,
                "Client packet lost control state",
            )
        }
        contender.prepare(sequence, CONTENDER)
        contender.awaitMarker(sequence, "vector-busy")
        sequence.thenExecute {
            helper.assertTrue(
                thruster.currentVectorX == 0.6f && thruster.currentVectorY == -0.4f,
                "Ordinary upstream ticks did not move the nozzle to its owned target",
            )
            helper.setBlock(link, Blocks.AIR)
        }
        sequence.thenWaitUntil {
            helper.assertTrue(
                thruster.throttle == 1f && !thruster.hasPeripheralThrustOverride(),
                "Cable loss did not restore vector redstone/thrust",
            )
            helper.assertTrue(
                thruster.targetVectorX == -0.4f && thruster.targetVectorY == 0.6f,
                "Cable loss did not restore live link signals",
            )
        }
        contender.resume(sequence)
        contender.awaitMarker(sequence, "vector-contender")
        sequence.thenExecute { helper.setBlock(link, cable) }
        owner.resume(sequence)
        owner.awaitMarker(sequence, "vector-stale")
        contender.resume(sequence)
        contender.awaitMarker(sequence, "vector-finished")
        sequence.thenWaitUntil {
            helper.assertTrue(
                thruster.throttle == 1f && !thruster.hasPeripheralThrustOverride() && thruster.targetVectorX == -0.4f,
                "Program end did not release vector commands",
            )
        }
        owner.resume(sequence)
        owner.awaitMarker(sequence, "vector-close")
        sequence.thenWaitUntil {
            helper.assertTrue(
                thruster.throttle == 1f && !thruster.hasPeripheralThrustOverride() && thruster.targetVectorX == -0.4f,
                "Explicit close did not release vector commands",
            )
        }
        owner.resume(sequence)
        owner.awaitMarker(sequence, "vector-reacquired")
        sequence.thenExecute { helper.setBlock(first, Blocks.AIR) }
        sequence.thenWaitUntil {
            helper.assertTrue(
                thruster.throttle == 1f && !thruster.hasPeripheralThrustOverride() && thruster.targetVectorY == 0.6f,
                "Computer removal did not release vector commands",
            )
        }
        sequence.thenSucceed()
    }

    @JvmStatic
    @GameTest(
        batch = "propulsion_vector_assembly",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 100_000,
    )
    fun vectorAssemblyClearsControl(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 3)
        val engine = BlockPos(5, 2, 3)
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        listOf(BlockPos(3, 2, 3), BlockPos(4, 2, 3)).forEach { helper.setBlock(it, CompuktersRegistry.PERIPHERAL_CABLE.get()) }
        helper.setBlock(engine, block)
        val container = requireNotNull(SubLevelContainer.getContainer(helper.level))
        val physics = container.physicsSystem()
        val wasPaused = physics.paused
        physics.setPaused(true)
        var body: ServerSubLevel? = null
        val guest = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) { nameDevice(helper, engine) }
        guest.prepare(sequence, ASSEMBLY)
        guest.awaitMarker(sequence, "vector-assembly-owned")
        sequence.thenExecute {
            val minimum = helper.absolutePos(engine)
            body =
                SubLevelAssemblyHelper.assembleBlocks(
                    helper.level,
                    minimum,
                    listOf(minimum),
                    BoundingBox3i(
                        minimum.x,
                        minimum.y,
                        minimum.z,
                        minimum.x + 1,
                        minimum.y + 1,
                        minimum.z + 1,
                    ),
                )
            container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
        }
        sequence.thenWaitUntil {
            val moved = VectorThrusterHost.resolve(helper.level, requireNotNull(body).plot.centerBlock)
            helper.assertTrue(moved != null, "Assembled vector engine unavailable")
            helper.assertTrue(
                !moved!!.hasPeripheralThrustOverride() && moved.throttle == 0f && moved.targetVectorX == 0f && moved.targetVectorY == 0f,
                "Sable transferred vector program commands",
            )
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "vector-assembly-stale")
        sequence.thenSucceed()
        helper.testInfo.addListener(
            object : GameTestListener {
                private fun cleanup() {
                    physics.setPaused(wasPaused)
                    body?.let {
                        container.removeForceLoadTicket(it, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                        if (!it.isRemoved) container.removeSubLevel(it, SubLevelRemovalReason.REMOVED)
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
                    original: GameTestInfo,
                    runner: GameTestRunner,
                ) = cleanup()
            },
        )
    }

    private fun nameDevice(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        val absolute = helper.absolutePos(position)
        val player = FakePlayerFactory.getMinecraft(helper.level)
        player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
        val result =
            PeripheralConfiguratorServer.save(
                player,
                InteractionHand.MAIN_HAND,
                PeripheralConfiguratorContext(absolute, Direction.WEST),
                "engine",
            )
        helper.assertTrue(result == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "Could not name Creative Vector Thruster: $result")
    }

    private val OWNER =
        """
        import propulsion.thrusters.Thrusters
        fun main() {
            val e = Thrusters.creativeVector("engine")
            e.setThrustKn(123.0)
            e.setVector(0.1, -0.1)
            check(e.state().targetVectorY < -0.13)
            e.setVector(0.6, -0.4)
            e.setThrottle(0.375)
            val s = e.state()
            check(s.customThrust && s.thrustKn == 123.0 && s.throttle == 0.375)
            check(s.targetVectorX > 0.59 && s.targetVectorX < 0.61)
            check(s.targetVectorY < -0.39 && s.targetVectorY > -0.41)
            println("vector-owned")
            readln()
            var stale = false
            try { e.setVector(0.0, 0.0) } catch (failure: IllegalStateException) { stale = true }
            check(stale)
            println("vector-stale")
            readln()
            val a = Thrusters.creativeVector("engine")
            a.setThrottle(0.5)
            a.setVector(1.0, 1.0)
            a.setThrustKn(100.0)
            a.clearThrustOverride()
            check(!a.state().customThrust)
            a.setThrustKn(90.0)
            a.close()
            println("vector-close")
            readln()
            val last = Thrusters.creativeVector("engine")
            last.setThrottle(0.00001)
            last.setVector(-1.0, -1.0)
            last.setThrustKn(50.0)
            println("vector-reacquired")
            readln()
        }
        """.trimIndent()

    private val CONTENDER =
        """
        import propulsion.thrusters.Thrusters
        fun main() {
            val engine = Thrusters.creativeVector("engine")
            var bad = false
            try { engine.setThrottle(1.1) } catch (failure: IllegalArgumentException) { bad = true }
            check(bad)
            bad = false
            try { engine.setThrustKn(-1.0) } catch (failure: IllegalArgumentException) { bad = true }
            check(bad)
            bad = false
            try { engine.setThrustKn(engine.state().maxThrustKn + 1.0) } catch (failure: IllegalStateException) { bad = true }
            check(bad)
            var invalid = false
            try { engine.setVector(0.0 / 0.0, 0.0) } catch (e: IllegalArgumentException) { invalid = true }
            check(invalid)
            check(engine.state().throttle == 0.375)
            var busy = false
            try { engine.setVector(0.0, 0.0) } catch (e: IllegalStateException) { busy = true }
            check(busy)
            println("vector-busy")
            readln()
            engine.setThrottle(0.25)
            engine.setVector(0.2, -0.2)
            engine.setThrustKn(70.0)
            println("vector-contender")
            readln()
            println("vector-finished")
        }
        """.trimIndent()

    private val ASSEMBLY =
        """
        import propulsion.thrusters.Thrusters
        fun main() {
            val engine = Thrusters.creativeVector("engine")
            engine.setThrottle(0.5)
            engine.setVector(0.6, -0.4)
            engine.setThrustKn(120.0)
            println("vector-assembly-owned")
            readln()
            var stale = false
            try { engine.state() } catch (e: IllegalStateException) { stale = true }
            check(stale)
            println("vector-assembly-stale")
        }
        """.trimIndent()
}
