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
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableBlock
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import net.minecraft.util.Unit as MinecraftUnit

@PrefixGameTestTemplate(false)
object CreativeThrusterGameTests {
    @JvmStatic
    @GameTest(batch = "propulsion", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun guestControlLifetime(helper: GameTestHelper) {
        val first = BlockPos(2, 2, 3)
        val second = BlockPos(4, 2, 5)
        val link = BlockPos(3, 2, 3)
        val engine = BlockPos(5, 2, 3)
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        helper.setBlock(first, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(second, CompuktersRegistry.COMPUTER.get())
        listOf(link, BlockPos(4, 2, 3), BlockPos(4, 2, 4)).forEach { helper.setBlock(it, cable) }
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_thruster"))
        helper.assertTrue(block != Blocks.AIR, "Creative Thruster is not registered")
        helper.setBlock(engine, block)
        helper.setBlock(engine.east(), Blocks.REDSTONE_BLOCK)
        val thruster = helper.getBlockEntity(engine) as CreativeThrusterBlockEntity
        val owner = GuestComputerScenario(helper, first)
        val contender = GuestComputerScenario(helper, second)
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            helper.assertTrue(
                helper.getBlockState(BlockPos(4, 2, 3)).getValue(PeripheralCableBlock.EAST),
                "Peripheral cable did not visually connect to the Creative Thruster",
            )
            nameDevice(helper, engine)
        }
        owner.prepare(sequence, OWNER)
        owner.awaitMarker(sequence, "owner-acquired")
        sequence.thenExecute {
            helper.assertTrue(thruster.throttle == 0.375f, "Digital throttle did not reach the actual thruster")
            helper.assertTrue(thruster.thrustConfig == 49, "Thrust percentage did not map to configuration index")
            val saved = thruster.saveWithFullMetadata(helper.level.registryAccess())
            helper.assertTrue(saved.getBoolean("compukters_propulsion:transient_control"), "Owned throttle was not marked transient")
            val copy = CreativeThrusterBlockEntity(helper.absolutePos(engine), thruster.blockState)
            copy.loadWithComponents(saved, helper.level.registryAccess())
            helper.assertTrue(copy.thrustConfig == 49, "NBT copy lost the engine setting")
            helper.assertTrue(copy.throttle != 0.375f, "NBT copy retained an unowned digital throttle")
            copy.setControlMode(dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity.ControlMode.PERIPHERAL)
            helper.assertTrue(copy.throttle == 0f, "NBT copy did not clear digital input")
            helper.assertTrue(thruster.throttle == 0.375f, "Serializing modified the live owner's throttle")
        }
        contender.prepare(sequence, CONTENDER)
        contender.awaitMarker(sequence, "contender-busy")
        sequence.thenExecute { helper.setBlock(link, Blocks.AIR) }
        sequence.thenWaitUntil { helper.assertTrue(thruster.throttle == 1f, "Cable loss did not restore powered redstone") }
        contender.resume(sequence)
        contender.awaitMarker(sequence, "contender-acquired")
        sequence.thenExecute {
            helper.assertTrue(thruster.throttle == 0.25f, "Released control was not available to contender")
            helper.setBlock(link, cable)
        }
        owner.resume(sequence)
        owner.awaitMarker(sequence, "owner-stale")
        contender.resume(sequence)
        contender.awaitMarker(sequence, "contender-finished")
        sequence.thenWaitUntil { helper.assertTrue(thruster.throttle == 1f, "Program termination did not restore redstone") }
        owner.resume(sequence)
        owner.awaitMarker(sequence, "owner-reacquired")
        sequence.thenExecute { helper.setBlock(first, Blocks.AIR) }
        sequence.thenWaitUntil { helper.assertTrue(thruster.throttle == 1f, "Computer removal did not release control") }
        sequence.thenExecute { helper.assertTrue(thruster.thrustConfig == 49, "Release changed the saved thrust setting") }
        sequence.thenSucceed()
    }

    @JvmStatic
    @GameTest(batch = "propulsion_assembly", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun multiblockAssemblyClearsControl(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 3)
        val engine = BlockPos(5, 2, 3)
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_thruster"))
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        listOf(BlockPos(3, 2, 3), BlockPos(4, 2, 3)).forEach { helper.setBlock(it, CompuktersRegistry.PERIPHERAL_CABLE.get()) }
        val members = (0..1).flatMap { x -> (0..1).flatMap { y -> (0..1).map { z -> engine.offset(x, y, z) } } }
        members.forEach { helper.setBlock(it, block) }
        val container = requireNotNull(SubLevelContainer.getContainer(helper.level))
        val physics = container.physicsSystem()
        val wasPaused = physics.paused
        physics.setPaused(true)
        var body: ServerSubLevel? = null
        val guest = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()
        sequence.thenWaitUntil {
            val controller = PropulsionGuestIntegration.controller(helper.level, helper.absolutePos(engine))
            helper.assertTrue(controller?.width == 2, "Creative Thruster multiblock did not form")
        }
        sequence.thenExecute { nameDevice(helper, engine) }
        guest.prepare(sequence, ASSEMBLY)
        guest.awaitMarker(sequence, "assembly-owned")
        sequence.thenExecute {
            val anchor = requireNotNull(PropulsionGuestIntegration.controller(helper.level, helper.absolutePos(engine))).blockPos
            val minimum = helper.absolutePos(engine)
            body =
                SubLevelAssemblyHelper.assembleBlocks(
                    helper.level,
                    anchor,
                    members.map(helper::absolutePos),
                    BoundingBox3i(minimum.x, minimum.y, minimum.z, minimum.x + 2, minimum.y + 2, minimum.z + 2),
                )
            container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
        }
        sequence.thenWaitUntil {
            val position = requireNotNull(body).plot.centerBlock
            val moved = PropulsionGuestIntegration.controller(helper.level, position)
            helper.assertTrue(moved != null && moved.width == 2, "Assembled multiblock controller is unavailable")
            helper.assertTrue(moved!!.thrustConfig == 59, "Assembly changed the configured thrust")
            helper.assertTrue(moved.throttle == 0f, "Assembly transferred the program's digital command")
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "assembly-stale")
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
                    rerun: GameTestInfo,
                    runner: GameTestRunner,
                ) = cleanup()
            },
        )
    }

    private val ASSEMBLY =
        """
        import propulsion.thrusters.Thrusters
        import propulsion.thrusters.CreativeThruster
        fun main() {
            val engine = CreativeThruster.named("engine")
            check(engine == Thrusters.creative("engine"))
            check(CreativeThruster.first() == engine)
            check(CreativeThruster.all().size == 1)
            check(CreativeThruster.firstOrNull { it.state().width < 0 } == null)
            check(engine.state().width == 2)
            engine.setThrustPercent(60)
            engine.setThrottle(0.5)
            check(engine.state().throttle == 0.5)
            println("assembly-owned")
            readln()
            var stale = false
            try { engine.state() } catch (failure: compukter.io.IOException) { stale = true }
            check(stale)
            println("assembly-stale")
        }
        """.trimIndent()

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
        helper.assertTrue(result == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "Could not name Creative Thruster: $result")
    }

    private val OWNER =
        """
        import propulsion.thrusters.Thrusters
        import propulsion.thrusters.CreativeThruster
        fun main() {
            val engine = CreativeThruster.named("engine")
            check(engine == Thrusters.creative("engine"))
            check(CreativeThruster.first() == engine)
            check(CreativeThruster.all().size == 1)
            check(CreativeThruster.firstOrNull { it.state().width < 0 } == null)
            var rejected = false
            try { engine.setThrottle(0.0 / 0.0) } catch (failure: IllegalArgumentException) { rejected = true }
            check(rejected)
            rejected = false
            try { engine.setThrustPercent(0) } catch (failure: IllegalArgumentException) { rejected = true }
            check(rejected)
            engine.setThrustPercent(50)
            engine.setThrottle(0.375)
            val snapshot = engine.state()
            check(snapshot.throttle == 0.375)
            check(snapshot.thrustPercent == 50)
            check(snapshot.configuredThrustKn > 0.0)
            check(snapshot.width == 1)
            println("owner-acquired")
            readln()
            rejected = false
            try { engine.state() } catch (failure: compukter.io.IOException) { rejected = true }
            check(rejected)
            check(snapshot.throttle == 0.375)
            println("owner-stale")
            readln()
            val next = Thrusters.creative("engine")
            next.setThrottle(0.5)
            next.setThrottle(0.00005)
            next.close()
            rejected = false
            try { next.state() } catch (failure: compukter.io.IOException) { rejected = true }
            check(rejected)
            val last = Thrusters.creative("engine")
            last.setThrottle(0.625)
            println("owner-reacquired")
            readln()
        }
        """.trimIndent()

    private val CONTENDER =
        """
        import propulsion.thrusters.Thrusters
        import propulsion.thrusters.CreativeThruster
        fun main() {
            val engine = CreativeThruster.named("engine")
            check(engine == Thrusters.creative("engine"))
            check(CreativeThruster.first() == engine)
            check(CreativeThruster.all().size == 1)
            check(CreativeThruster.firstOrNull { it.state().width < 0 } == null)
            check(engine.state().throttle == 0.375)
            var busy = false
            try { engine.setThrottle(0.25) } catch (failure: IllegalStateException) { busy = true }
            check(busy)
            println("contender-busy")
            readln()
            engine.setThrottle(0.25)
            println("contender-acquired")
            readln()
            // Ordinary program completion must release this handle without an explicit close.
            println("contender-finished")
        }
        """.trimIndent()
}

@EventBusSubscriber(modid = CompuktersPropulsionMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
object PropulsionGameTestRegistration {
    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterGameTestsEvent) {
        event.register(CreativeThrusterGameTests::class.java)
        event.register(CreativeVectorThrusterGameTests::class.java)
        event.register(CreativeVectorThrusterLatencyGameTests::class.java)
    }
}
