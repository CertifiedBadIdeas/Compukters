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
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
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
    fun guestVectorHandleList(helper: GameTestHelper) {
        vectorHandleList(helper, removeComputer = false)
    }

    @JvmStatic
    @GameTest(batch = "propulsion_vector", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun guestVectorComputerRemovalClearsThrust(helper: GameTestHelper) {
        vectorHandleList(helper, removeComputer = true)
    }

    @JvmStatic
    @GameTest(batch = "propulsion_vector", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun guestVectorTerminateClearsThrust(helper: GameTestHelper) {
        vectorHandleList(helper, removeComputer = false, terminateProgram = true)
    }

    @JvmStatic
    @GameTest(
        batch = "propulsion_vector_redstone",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 100_000,
    )
    fun topLeverInputDoesNotPowerAdjacentEngine(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 2)
        val engine = computer.below()
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(engine, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP))
        helper.setBlock(
            computer.above(),
            Blocks.LEVER
                .defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.POWERED, true),
        )
        val scenario = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()

        fun assertStopped() {
            val thruster = helper.getBlockEntity(engine) as CreativeVectorThrusterBlockEntity
            helper.assertTrue(thruster.throttle == 0f, "top lever powered the engine through the computer")
            helper.assertTrue(thruster.currentThrust == 0f, "top lever caused physical thrust through the computer")
        }
        sequence.thenExecuteAfter(10) {
            nameDevice(helper, engine, "t1")
            assertStopped()
        }
        scenario.prepare(
            sequence,
            """
                import compukter.redstone.Redstone
                import propulsion.thrusters.CreativeVectorThruster
                fun main() {
                    val engine = CreativeVectorThruster.named("t1")
                    engine.setThrustKn(5.0)
                    engine.setThrottle(0.0)
                    println("top-level=" + Redstone.top.get())
                    readln()
                }
            """.trimIndent(),
        )
        sequence.thenExecute {
            helper.assertTrue(
                helper.level.getSignal(helper.absolutePos(computer.above()), Direction.UP) == 15,
                "top lever stopped providing input during program preparation",
            )
        }
        scenario.awaitMarker(sequence, "top-level=15")
        sequence.thenExecute { assertStopped() }
        scenario.terminate(sequence)
        sequence.thenWaitUntil {
            val thruster = helper.getBlockEntity(engine) as CreativeVectorThrusterBlockEntity
            helper.assertTrue(!thruster.hasPeripheralThrustOverride(), "terminated program retained engine control")
            assertStopped()
        }
        sequence.thenExecuteAfter(10) { assertStopped() }
        sequence.thenSucceed()
    }

    private fun vectorHandleList(
        helper: GameTestHelper,
        removeComputer: Boolean,
        terminateProgram: Boolean = false,
    ) {
        val computer = BlockPos(2, 2, 3)
        val engines = listOf("fl", "fr", "bl", "br").mapIndexed { index, name -> name to BlockPos(5, 2, index + 2) }
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        helper.setBlock(BlockPos(3, 2, 3), cable)
        for (z in 2..5) helper.setBlock(BlockPos(4, 2, z), cable)
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        engines.forEach { (_, position) -> helper.setBlock(position, block) }
        val scenario = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            engines.forEach { (name, position) -> nameDevice(helper, position, name) }
        }
        scenario.prepare(sequence, HANDLE_LIST)
        scenario.awaitMarker(sequence, "vector-list-owned")
        sequence.thenExecuteAfter(20) {
            engines.forEach { (name, position) ->
                val thruster = helper.getBlockEntity(position) as CreativeVectorThrusterBlockEntity
                helper.assertTrue(thruster.hasPeripheralThrustOverride(), "$name did not receive custom thrust through list iteration")
                helper.assertTrue(thruster.throttle == 0.25f, "$name did not receive throttle through list iteration")
                helper.assertTrue(thruster.currentThrust > 0f, "$name must produce physical thrust before release")
            }
        }
        if (removeComputer) {
            sequence.thenExecute { helper.setBlock(computer, Blocks.AIR) }
        } else if (terminateProgram) {
            scenario.terminate(sequence)
        } else {
            scenario.resume(sequence)
            scenario.awaitMarker(sequence, "vector-list-finished")
        }
        sequence.thenWaitUntil {
            engines.forEach { (name, position) ->
                val thruster = helper.getBlockEntity(position) as CreativeVectorThrusterBlockEntity
                helper.assertTrue(!thruster.hasPeripheralThrustOverride(), "$name retained its lease after program completion")
            }
        }
        sequence.thenExecute {
            engines.forEach { (name, position) ->
                val thruster = helper.getBlockEntity(position) as CreativeVectorThrusterBlockEntity
                helper.assertTrue(thruster.currentThrust == 0f, "$name retained physical thrust after program completion")
            }
        }
        sequence.thenExecuteAfter(2) {
            engines.forEach { (name, position) ->
                val thruster = helper.getBlockEntity(position) as CreativeVectorThrusterBlockEntity
                helper.assertTrue(thruster.currentThrust == 0f, "$name regained thrust from its old shutdown envelope")
            }
        }
        sequence.thenSucceed()
    }

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
            helper.assertTrue(thruster.targetVectorX == 0.013f && thruster.targetVectorY == -0.007f, "Redstone overwrote owned steering")
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
                thruster.hasPeripheralThrustOverride() && thruster.throttle == 0.375f && thruster.targetVectorX == 0.013f,
                "Saving modified live control",
            )
            val packet = thruster.getUpdateTag(helper.level.registryAccess())
            helper.assertTrue(!packet.getBoolean("compukters_propulsion:transient_control"), "Client packet marked transient")
            helper.assertTrue(
                packet.getFloat("TargetVectorX") == 0.013f && packet.getFloat("PeripheralThrustOutput") >= 0f,
                "Client packet lost control state",
            )
        }
        contender.prepare(sequence, CONTENDER)
        contender.awaitMarker(sequence, "vector-busy")
        sequence.thenExecute {
            helper.assertTrue(
                thruster.currentVectorX == 0.013f && thruster.currentVectorY == -0.007f,
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

    @JvmStatic
    @GameTest(batch = "propulsion_vector_mount", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun guestMountOnConstruction(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 3)
        // Deliberately rotated names: fl is +X,+Z, unlike the controller's former hard-coded map.
        val engines =
            listOf(
                "fl" to BlockPos(4, 2, 5),
                "fr" to BlockPos(4, 2, 1),
                "bl" to BlockPos(0, 2, 5),
                "br" to BlockPos(0, 2, 1),
            )
        val positions = mutableListOf<BlockPos>()
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        for (x in 0..4) {
            for (z in 1..5) {
                val position = BlockPos(x, 2, z)
                positions.add(position)
                helper.setBlock(position, cable)
            }
        }
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        engines.forEach { (_, position) ->
            helper.setBlock(position, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP))
        }
        val container = requireNotNull(SubLevelContainer.getContainer(helper.level))
        val physics = container.physicsSystem()
        val wasPaused = physics.paused
        physics.setPaused(true)
        var body: ServerSubLevel? = null
        var currentPosition = helper.absolutePos(computer)
        val guest = GuestComputerScenario(helper, computer) { currentPosition }
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            val minimum = helper.absolutePos(BlockPos(0, 2, 1))
            val maximum = helper.absolutePos(BlockPos(5, 3, 6))
            body =
                SubLevelAssemblyHelper.assembleBlocks(
                    helper.level,
                    helper.absolutePos(computer),
                    positions.map(helper::absolutePos),
                    BoundingBox3i(minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z),
                )
            container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
            currentPosition = requireNotNull(body).plot.centerBlock
            engines.forEach { (name, position) ->
                nameDeviceAt(helper, currentPosition.offset(position.subtract(computer)), name)
            }
        }
        guest.prepare(sequence, MOUNT)
        guest.awaitMarker(sequence, "vector-mount-ok")
        sequence.thenExecute {
            val origin = requireNotNull(body).plot.centerBlock
            engines.forEach { (_, position) ->
                val entity = VectorThrusterHost.resolve(helper.level, origin.offset(position.subtract(computer)))!!
                helper.assertTrue(!PropulsionGuestIntegration.isControlled(entity), "Read-only mount claimed engine control")
            }
        }
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
        name: String = "engine",
    ) = nameDeviceAt(helper, helper.absolutePos(position), name)

    private fun nameDeviceAt(
        helper: GameTestHelper,
        absolute: BlockPos,
        name: String,
    ) {
        val player = FakePlayerFactory.getMinecraft(helper.level)
        player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
        val result =
            PeripheralConfiguratorServer.save(
                player,
                InteractionHand.MAIN_HAND,
                PeripheralConfiguratorContext(absolute, Direction.WEST),
                name,
            )
        helper.assertTrue(result == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "Could not name Creative Vector Thruster: $result")
    }

    private val MOUNT =
        """
        import propulsion.thrusters.CreativeVectorThruster
        fun main() {
            val names = listOf("fl", "fr", "bl", "br")
            var construction = ""
            repeat(4) { index ->
                val mount = CreativeVectorThruster.named(names[index]).mount()
                check(mount.constructionId != "")
                if (index == 0) construction = mount.constructionId
                check(mount.constructionId == construction)
                check(mount.offsetX == (if (index < 2) 2 else -2))
                check(mount.offsetY == 0)
                check(mount.offsetZ == (if (index == 0 || index == 2) 2 else -2))
                check(mount.forceX == 0 && mount.forceY == 1 && mount.forceZ == 0)
            }
            println("vector-mount-ok")
        }
        """.trimIndent()

    private val HANDLE_LIST =
        """
        import propulsion.thrusters.CreativeVectorThruster
        fun main() {
            val fl = CreativeVectorThruster.named("fl")
            val fr = CreativeVectorThruster.named("fr")
            val bl = CreativeVectorThruster.named("bl")
            val br = CreativeVectorThruster.named("br")
            val all = listOf(fl, fr, bl, br)
            repeat(4) { index ->
                val mount = all[index].mount()
                check(mount.constructionId == "")
                check(mount.offsetX == 3 && mount.offsetY == 0 && mount.offsetZ == index - 1)
            }
            all.forEach {
                it.setThrustKn(100.0)
                it.setThrottle(0.25)
            }
            for (engine in all) check(engine.state().thrustKn == 100.0)
            println("vector-list-owned")
            readln()
            println("vector-list-finished")
        }
        """.trimIndent()

    private val OWNER =
        """
        import propulsion.thrusters.CreativeVectorThruster
        fun main() {
            val e = CreativeVectorThruster.named("engine")
            val peripheral: compukter.peripheral.Peripheral = e
            check(peripheral == e)
            check(peripheral is CreativeVectorThruster)
            check((peripheral as CreativeVectorThruster) == e)
            check(e == CreativeVectorThruster.named("engine"))
            check(CreativeVectorThruster.first() == e)
            check(CreativeVectorThruster.filter { it.state().throttle >= 0.0 }.size == 1)
            e.setThrustKn(123.0)
            e.setVector(0.1, -0.1)
            val first = e.state()
            check(first.targetVectorX > 0.0999 && first.targetVectorX < 0.1001)
            check(first.targetVectorY > -0.1001 && first.targetVectorY < -0.0999)
            e.setVector(0.013, -0.007)
            e.setThrottle(0.375)
            val s = e.state()
            check(s.customThrust && s.thrustKn == 123.0 && s.throttle == 0.375)
            check(s.targetVectorX > 0.0129 && s.targetVectorX < 0.0131)
            check(s.targetVectorY > -0.0071 && s.targetVectorY < -0.0069)
            println("vector-owned")
            readln()
            var stale = false
            try { e.setVector(0.0, 0.0) } catch (failure: compukter.io.IOException) { stale = true }
            check(stale)
            println("vector-stale")
            readln()
            val a = CreativeVectorThruster.named("engine")
            a.setThrottle(0.5)
            a.setVector(1.0, 1.0)
            a.setThrustKn(100.0)
            a.clearThrustOverride()
            check(!a.state().customThrust)
            a.setThrustKn(90.0)
            a.close()
            println("vector-close")
            readln()
            val last = CreativeVectorThruster.named("engine")
            last.setThrottle(0.00001)
            last.setVector(-1.0, -1.0)
            last.setThrustKn(50.0)
            println("vector-reacquired")
            readln()
        }
        """.trimIndent()

    private val CONTENDER =
        """
        import propulsion.thrusters.CreativeVectorThruster
        fun main() {
            val engine = CreativeVectorThruster.named("engine")
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
        import propulsion.thrusters.CreativeVectorThruster
        fun main() {
            val engine = CreativeVectorThruster.named("engine")
            engine.setThrottle(0.5)
            engine.setVector(0.6, -0.4)
            engine.setThrustKn(120.0)
            println("vector-assembly-owned")
            readln()
            var stale = false
            try { engine.state() } catch (e: compukter.io.IOException) { stale = true }
            check(stale)
            println("vector-assembly-stale")
        }
        """.trimIndent()
}
