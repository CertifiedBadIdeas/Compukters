/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.create.gametest

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.material.Fluids
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult

@PrefixGameTestTemplate(false)
object CreateBoilerGameTests {
    @JvmStatic
    @GameTest(batch = "create_boiler", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun namedBoilerLifecycle(helper: GameTestHelper) {
        val computer = BlockPos(2, 3, 3)
        val junction = BlockPos(4, 3, 3)
        val controller = BlockPos(5, 2, 3)
        val contact = controller.above()
        val engine = controller.above(3).east()
        val plainTank = BlockPos(5, 3, 5)
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        val tank = AllBlocks.FLUID_TANK.get()
        val engineState =
            AllBlocks.STEAM_ENGINE.defaultState
                .setValue(SteamEngineBlock.FACE, AttachFace.WALL)
                .setValue(SteamEngineBlock.FACING, Direction.EAST)
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        listOf(BlockPos(3, 3, 3), junction, BlockPos(4, 3, 4), BlockPos(4, 3, 5)).forEach { helper.setBlock(it, cable) }
        helper.setBlock(controller.below(), Blocks.CAMPFIRE)
        repeat(4) { helper.setBlock(controller.above(it), tank) }
        helper.setBlock(plainTank, tank)
        helper.setBlock(engine, engineState)
        helper.onEachTick {
            helper.level
                .getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(controller), null)
                ?.fill(FluidStack(Fluids.WATER, 20), IFluidHandler.FluidAction.EXECUTE)
        }
        val guest = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()

        fun ready() {
            val segment = helper.getBlockEntity(contact) as FluidTankBlockEntity
            val actual = segment.controllerBE
            helper.assertTrue(
                actual != null && actual.blockPos != segment.blockPos,
                "cable contact must be a real non-controller tank segment",
            )
            helper.assertTrue(
                actual!!.totalTankSize == 4 && actual.boiler.isActive && actual.boiler.waterSupply == 20f,
                "actual four-block boiler has not formed and sampled water supply",
            )
            helper.assertTrue(actual.boiler.passiveHeat, "campfire did not provide real passive heat")
        }
        sequence.thenWaitUntil { ready() }.thenExecute {
            CreatePeripheralGameTests.nameDevice(helper, contact, "boiler")
            val rejected = CreatePeripheralGameTests.nameDevice(helper, plainTank, "plain", expectSuccess = false)
            helper.assertTrue(
                rejected == PeripheralConfiguratorSaveResult.INVALID_TARGET,
                "ordinary non-boiler tank was exposed as a peripheral: $rejected",
            )
        }
        guest.prepare(sequence, BOILER_SOURCE)
        guest.awaitMarker(sequence, "boiler-acquired")
        sequence.thenExecute { helper.setBlock(engine, Blocks.AIR) }.thenWaitUntil {
            helper.assertTrue(
                !(helper.getBlockEntity(contact) as FluidTankBlockEntity).controllerBE.boiler.isActive,
                "removing the real engine did not deactivate the boiler",
            )
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "boiler-inactive")
        sequence.thenExecute { helper.setBlock(engine, engineState) }.thenWaitUntil { ready() }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "boiler-restored")
        sequence
            .thenExecute {
                helper.setBlock(contact, Blocks.AIR)
                helper.setBlock(contact, tank)
            }.thenWaitUntil { ready() }
            .thenExecute { CreatePeripheralGameTests.nameDevice(helper, contact, "boiler") }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "boiler-replaced")
        sequence.thenExecute { helper.setBlock(junction, Blocks.AIR) }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "boiler-cut")
        sequence.thenExecute { helper.setBlock(junction, cable) }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "boiler-done")
        sequence.thenSucceed()
    }

    private val BOILER_SOURCE =
        """
        import compukter.terminal.Terminal
        import create.boiler.Boilers
        import create.boiler.Boiler
        fun main() {
            val boiler = Boiler.first()
            check(boiler == Boilers.boiler("boiler"))
            check(Boiler.named("boiler") == boiler)
            check(Boiler.all().size == 1)
            check(boiler.waterSupply() == 20f)
            check(boiler.waterLevel() == 2)
            check(boiler.heatLevel() == 1)
            check(boiler.level() == 0)
            check(boiler.isPassive())
            Terminal.write("boiler-acquired\n")
            readln()
            var failures = 0
            try { boiler.level() } catch (e: compukter.io.IOException) { failures += 1 }
            try { Boilers.boiler("boiler") } catch (e: IllegalStateException) { failures += 1 }
            check(failures == 2)
            Terminal.write("boiler-inactive\n")
            readln()
            val restored = Boilers.boiler("boiler")
            check(restored.waterSupply() == 20f)
            check(restored.isPassive())
            Terminal.write("boiler-restored\n")
            readln()
            var stale = false
            try { restored.level() } catch (e: compukter.io.IOException) { stale = true }
            check(stale)
            val replaced = Boilers.boiler("boiler")
            check(replaced.waterSupply() == 20f)
            Terminal.write("boiler-replaced\n")
            readln()
            var cut = false
            try { replaced.level() } catch (e: compukter.io.IOException) { cut = true }
            check(cut)
            Terminal.write("boiler-cut\n")
            readln()
            var removed = false
            try { replaced.level() } catch (e: compukter.io.IOException) { removed = true }
            check(removed)
            check(Boilers.boiler("boiler").isPassive())
            Terminal.write("boiler-done\n")
        }
        """.trimIndent()
}
