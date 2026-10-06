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

import com.mojang.logging.LogUtils
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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import net.minecraft.util.Unit as MinecraftUnit

/** Tick-resolution timing probe. Physics is paused; nozzle block-entity ticks and VM execution remain live. */
@PrefixGameTestTemplate(false)
object CreativeVectorThrusterLatencyGameTests {
    @JvmStatic
    @GameTest(batch = "propulsion_vector_latency", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun snapshotToNozzle(helper: GameTestHelper) = probe(helper, parallel = false)

    @JvmStatic
    @GameTest(
        batch = "propulsion_vector_latency_parallel",
        template = "bastion/mobs/empty",
        templateNamespace = "minecraft",
        timeoutTicks = 100_000,
    )
    fun snapshotToNozzleParallel(helper: GameTestHelper) = probe(helper, parallel = true)

    private fun probe(
        helper: GameTestHelper,
        parallel: Boolean,
    ) {
        val computer = BlockPos(2, 3, 3)
        val engine = computer.below()
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createpropulsion", "creative_vector_thruster"))
        helper.setBlock(engine, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP))
        val container = requireNotNull(SubLevelContainer.getContainer(helper.level))
        val physics = container.physicsSystem()
        val wasPaused = physics.paused
        physics.setPaused(true)
        var body: ServerSubLevel? = null
        var currentPosition = helper.absolutePos(computer)
        val guest = GuestComputerScenario(helper, computer) { currentPosition }
        val samples = List(5) { Sample() }
        var armed = -1
        helper.onEachTick {
            if (armed >= 0 && body != null) {
                val thruster = helper.level.getBlockEntity(currentPosition.below()) as CreativeVectorThrusterBlockEntity
                val sample = samples[armed]
                val tick = helper.level.gameTime
                if (thruster.targetVectorX == 0.3f && sample.vectorTick == null) sample.vectorTick = tick
                if (thruster.throttle == 0.4f && sample.throttleTick == null) sample.throttleTick = tick
                if (sample.vectorTick != null) {
                    if (thruster.currentVectorX >= 0.15f && sample.vector50Tick == null) sample.vector50Tick = tick
                    if (thruster.currentVectorX >= 0.27f && sample.vector90Tick == null) sample.vector90Tick = tick
                }
                if (thruster.effectiveThrottle >= 0.36f && sample.throttle90Tick == null) sample.throttle90Tick = tick
            }
        }
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            val minimum = helper.absolutePos(engine)
            val maximum = helper.absolutePos(computer.offset(1, 1, 1))
            body =
                SubLevelAssemblyHelper.assembleBlocks(
                    helper.level,
                    helper.absolutePos(computer),
                    listOf(computer, engine).map(helper::absolutePos),
                    BoundingBox3i(minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z),
                )
            container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
            currentPosition = requireNotNull(body).plot.centerBlock
            val enginePosition = currentPosition.below()
            val original = helper.level.getBlockEntity(enginePosition) as CreativeVectorThrusterBlockEntity
            val observed = ObservedThruster(enginePosition, original.blockState)
            observed.loadWithComponents(original.saveWithFullMetadata(helper.level.registryAccess()), helper.level.registryAccess())
            helper.level.setBlockEntity(observed)
            observed.recordMutation = { entity ->
                if (armed >= 0) {
                    val sample = samples[armed]
                    val tick = helper.level.gameTime
                    if (entity.targetVectorX == 0.3f && sample.vectorAppliedTick == null) sample.vectorAppliedTick = tick
                    if (entity.throttle == 0.4f && sample.throttleAppliedTick == null) sample.throttleAppliedTick = tick
                }
            }
            val player = FakePlayerFactory.getMinecraft(helper.level)
            val absolute = currentPosition.below()
            player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
            val result =
                PeripheralConfiguratorServer.save(
                    player,
                    InteractionHand.MAIN_HAND,
                    PeripheralConfiguratorContext(absolute, Direction.WEST),
                    "engine",
                )
            helper.assertTrue(result == PeripheralConfiguratorSaveResult.NAMED_DEVICE, "Could not name latency probe engine")
        }
        val source =
            if (parallel) {
                SOURCE.replace(
                    "engine.setVector(0.3, 0.0)\n        engine.setThrottle(0.4)",
                    """
                    var vectorDone = false
                    var throttleDone = false
                    Tasks.launch { engine.setVector(0.3, 0.0); vectorDone = true }
                    Tasks.launch { engine.setThrottle(0.4); throttleDone = true }
                    while (!vectorDone || !throttleDone) Tasks.sleepTicks(1)
                    """.trimIndent(),
                )
            } else {
                SOURCE
            }
        if (parallel) helper.assertTrue(source != SOURCE, "Parallel probe did not replace sequential commands")
        guest.prepare(sequence, source, listOf("propulsion", "sable"))
        repeat(samples.size) { index ->
            guest.awaitMarker(sequence, "latency-ready-$index")
            sequence.thenExecute { armed = index }
            guest.resume(sequence)
            guest.awaitMarker(sequence, "latency-done-$index")
            guest.inspectTerminal(sequence) { text ->
                val source =
                    Regex("latency-source-$index=([0-9]+)")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                val read =
                    Regex("latency-read-$index=([0-9]+)")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                helper.assertTrue(source != null && read != null, "Missing latency sample timestamps: $text")
                val sample = samples[index]
                val vectorApplied = requireNotNull(sample.vectorAppliedTick)
                val throttleApplied = requireNotNull(sample.throttleAppliedTick)
                val vector = requireNotNull(sample.vectorTick)
                val throttle = requireNotNull(sample.throttleTick)
                val vector50 = requireNotNull(sample.vector50Tick)
                val vector90 = requireNotNull(sample.vector90Tick)
                helper.assertTrue(
                    vectorApplied >= source!! && throttleApplied >= source && read!! >= throttleApplied && read >= vectorApplied,
                    "Latency stage ordering changed",
                )
                helper.assertTrue(vector50 >= vector && vector90 >= vector50, "Nozzle step response ordering changed")
                helper.assertTrue(vector >= vectorApplied && throttle >= throttleApplied, "Observer preceded actual mutation")
                helper.assertTrue(vectorApplied >= source && throttleApplied >= source, "Control mutation preceded snapshot")
                LogUtils.getLogger().info(
                    "Propulsion mutation mode={} sample={} sourceToVectorApply={} sourceToThrottleApply={} vectorObserverLag={} throttleObserverLag={} ticks",
                    if (parallel) "parallel" else "sequential",
                    index,
                    vectorApplied - source,
                    throttleApplied - source,
                    vector - vectorApplied,
                    throttle - throttleApplied,
                )
                LogUtils.getLogger().info(
                    "Propulsion latency mode={} sample={} sourceToVector={} sourceToThrottle={} sourceToRead={} vector50={} vector90={} throttle90={} ticks",
                    if (parallel) "parallel" else "sequential",
                    index,
                    vector - source,
                    throttle - source,
                    read!! - source,
                    vector50 - vector,
                    vector90 - vector,
                    sample.throttle90Tick?.minus(throttle) ?: -1,
                )
            }
            sequence.thenExecute { armed = -1 }
            guest.resume(sequence)
        }
        guest.awaitMarker(sequence, "latency-finished")
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

    /** Captures the real setter path on the server; no additional polling or production hooks. */
    private class ObservedThruster(
        position: BlockPos,
        state: BlockState,
    ) : CreativeVectorThrusterBlockEntity(position, state) {
        var recordMutation: ((ObservedThruster) -> Unit)? = null

        override fun setChanged() {
            super.setChanged()
            recordMutation?.invoke(this)
        }

        override fun setDigitalInput(input: Float) {
            super.setDigitalInput(input)
            recordMutation?.invoke(this)
        }
    }

    private class Sample {
        var vectorAppliedTick: Long? = null
        var throttleAppliedTick: Long? = null
        var vectorTick: Long? = null
        var throttleTick: Long? = null
        var vector50Tick: Long? = null
        var vector90Tick: Long? = null
        var throttle90Tick: Long? = null
    }

    private val SOURCE =
        """
        import compukter.concurrent.Tasks
        import propulsion.thrusters.CreativeVectorThruster
        import sable.physics.Physics
        fun main() {
            val engine = CreativeVectorThruster.named("engine")
            repeat(5) { index ->
                engine.setThrottle(0.0)
                engine.setVector(0.0, 0.0)
                Tasks.sleepTicks(35)
                println("latency-ready-" + index)
                readln()
                val source = Physics.snapshot()
                engine.setVector(0.3, 0.0)
                engine.setThrottle(0.4)
                val read = engine.state()
                println("latency-source-" + index + "=" + source.gameTick)
                println("latency-read-" + index + "=" + read.gameTick)
                Tasks.sleepTicks(35)
                println("latency-done-" + index)
                readln()
            }
            engine.setThrottle(0.0)
            engine.setVector(0.0, 0.0)
            println("latency-finished")
            readln()
        }
        """.trimIndent()
}
