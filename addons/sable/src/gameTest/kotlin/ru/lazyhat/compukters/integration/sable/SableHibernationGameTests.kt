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

package ru.lazyhat.compukters.integration.sable

import com.mojang.logging.LogUtils
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType
import dev.ryanhcode.sable.companion.math.BoundingBox3i
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import net.minecraft.util.Unit as MinecraftUnit

@PrefixGameTestTemplate(false)
object SableHibernationGameTests {
    @JvmStatic
    @GameTest(batch = "hibernation", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun livePhysicsHibernation(helper: GameTestHelper) = run(helper, 1)

    @JvmStatic
    @GameTest(batch = "hibernation-multiple", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun twoComputersResumeWithLivePhysics(helper: GameTestHelper) = run(helper, 2)

    private fun run(
        helper: GameTestHelper,
        count: Int,
    ) {
        val level = helper.level
        val relative = BlockPos(2, 3, 2)
        val anchor = helper.absolutePos(relative)
        val positions = List(count) { relative.east(it * 8) }
        positions.forEach { helper.setBlock(it, CompuktersRegistry.COMPUTER.get()) }
        val supports =
            (0..((count - 1) * 8)).flatMap { x ->
                val zOffsets = if (count == 1) 0..0 else -1..1
                zOffsets.map { z -> relative.offset(x, -1, z) }
            }
        supports.forEach { helper.setBlock(it, Blocks.STONE) }
        val identities = positions.map { (level.getBlockEntity(helper.absolutePos(it)) as ComputerBlockEntity).computerId() }
        val assemblyBlocks = (positions + supports).map(helper::absolutePos)
        val container = requireNotNull(SubLevelContainer.getContainer(level))
        helper.assertTrue(!container.physicsSystem().paused, "live physics fixture must start with physics running")
        var body: ServerSubLevel? = null
        helper.testInfo.addListener(
            object : GameTestListener {
                private fun cleanup() {
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
        var saved: SubLevelData? = null
        var currentOrigin = anchor
        var activationNanos = 0L
        val guests = List(count) { index -> SableGuestComputerScenario(helper) { currentOrigin.east(index * 8) } }
        val sequence = helper.startSequence()
        guests.forEach { guest ->
            guest.prepare(sequence, PROGRAM)
            guest.run(sequence, "world")
            guest.awaitMarker(sequence, "live-ready")
        }
        sequence
            .thenExecute {
                body =
                    SubLevelAssemblyHelper.assembleBlocks(
                        level,
                        anchor,
                        assemblyBlocks,
                        BoundingBox3i(anchor.x, anchor.y - 1, anchor.z - 1, anchor.x + (count - 1) * 8 + 1, anchor.y + 1, anchor.z + 2),
                    )
                container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                currentOrigin = requireNotNull(body).plot.centerBlock
            }.thenWaitUntil {
                identities.forEachIndexed { index, identity ->
                    val moved = level.getBlockEntity(currentOrigin.east(index * 8)) as? ComputerBlockEntity
                    helper.assertTrue(
                        moved?.runtimeState == ProgramComputerState.WaitingForInput,
                        "assembled computer $index at ${currentOrigin.east(index * 8)} is ${moved?.runtimeState}: ${moved?.computerId()}",
                    )
                    helper.assertTrue(moved!!.computerId() == identity, "assembly changed ComputerId")
                }
            }
        guests.forEach { guest ->
            guest.resume(sequence)
            guest.awaitMarker(sequence, "live-inside")
        }
        sequence
            .thenExecute {
                val previous = requireNotNull(body)
                saved = SubLevelSerializer.toData(previous, emptyList())
                container.removeForceLoadTicket(previous, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                container.removeSubLevel(previous, SubLevelRemovalReason.UNLOADED)
                container.processSubLevelRemovals()
                helper.assertTrue(previous.isRemoved, "Sable construction did not unload")
            }.thenExecute {
                activationNanos = System.nanoTime()
                body = requireNotNull(SubLevelSerializer.fullyLoad(level, requireNotNull(saved)))
                container.addForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                currentOrigin = requireNotNull(body).plot.centerBlock
                helper.assertTrue(!container.physicsSystem().paused, "loading paused physics")
            }.thenWaitUntil {
                identities.forEachIndexed { index, identity ->
                    val restored = level.getBlockEntity(currentOrigin.east(index * 8)) as? ComputerBlockEntity
                    helper.assertTrue(
                        restored?.runtimeState == ProgramComputerState.WaitingForInput,
                        "loaded computer $index is still restoring",
                    )
                    helper.assertTrue(restored!!.computerId() == identity, "Sable reload changed ComputerId")
                }
            }
        guests.forEach { guest ->
            guest.resume(sequence)
            guest.awaitMarker(sequence, "live-restored")
        }
        sequence
            .thenExecute {
                val milliseconds = (System.nanoTime() - activationNanos) / 1_000_000
                LogUtils.getLogger().info(
                    "Sable hibernation live physics activation to all {} continued Guest physics queries: {} ms",
                    count,
                    milliseconds,
                )
                helper.assertTrue(milliseconds < 1000, "Sable computer took $milliseconds ms to resume with live physics")
                val position = requireNotNull(body).plot.centerBlock
                SubLevelAssemblyHelper.moveBlocks(
                    level,
                    SubLevelAssemblyHelper.AssemblyTransform(position, anchor, 0, Rotation.NONE, level),
                    List(count) { position.east(it * 8) },
                )
                currentOrigin = anchor
            }.thenWaitUntil {
                identities.forEachIndexed { index, identity ->
                    val returned = level.getBlockEntity(anchor.east(index * 8)) as? ComputerBlockEntity
                    helper.assertTrue(
                        returned?.runtimeState == ProgramComputerState.WaitingForInput,
                        "returned computer $index is still restoring",
                    )
                    helper.assertTrue(returned!!.computerId() == identity, "return changed ComputerId")
                }
            }
        guests.forEach { guest ->
            guest.resume(sequence)
            guest.awaitMarker(sequence, "live-returned")
        }
        sequence
            .thenExecute {
                container.removeForceLoadTicket(requireNotNull(body), SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                container.removeSubLevel(requireNotNull(body), SubLevelRemovalReason.REMOVED)
            }.thenSucceed()
    }

    private val PROGRAM =
        """
        import sable.physics.Physics
        fun main(args: Array<String>) {
            println("live-ready")
            check(readln() == "continue")
            val id = Physics.snapshot().constructionId
            check(!Physics.snapshot().paused)
            println("live-inside")
            check(readln() == "continue")
            check(Physics.snapshot().constructionId == id)
            check(!Physics.snapshot().paused)
            println("live-restored")
            check(readln() == "continue")
            try {
                Physics.snapshot()
                error("returned computer still belongs to a construction")
            } catch (expected: IllegalStateException) {
                println("live-returned")
            }
        }
        """.trimIndent()
}
