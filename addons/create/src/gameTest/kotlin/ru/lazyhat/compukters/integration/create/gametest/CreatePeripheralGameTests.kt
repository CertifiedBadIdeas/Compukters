/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.create.gametest

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.integration.create.CompuktersCreateMod
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer

@PrefixGameTestTemplate(false)
object CreatePeripheralGameTests {
    @JvmStatic
    @GameTest(batch = "create_kinetics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100_000)
    fun namedKineticLifecycle(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 3)
        val junction = BlockPos(4, 2, 3)
        val speed = BlockPos(5, 2, 1)
        val stress = BlockPos(5, 2, 5)
        val controller = BlockPos(5, 3, 3)
        val cable = CompuktersRegistry.PERIPHERAL_CABLE.get()
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        listOf(
            BlockPos(3, 2, 3),
            junction,
            BlockPos(4, 2, 2),
            BlockPos(4, 2, 1),
            BlockPos(4, 2, 4),
            BlockPos(4, 2, 5),
            BlockPos(4, 3, 3),
        ).forEach { helper.setBlock(it, cable) }
        val gaugeState =
            AllBlocks.SPEEDOMETER.defaultState
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true)
        helper.setBlock(speed, gaugeState)
        helper.setBlock(
            stress,
            AllBlocks.STRESSOMETER.defaultState
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true),
        )
        helper.setBlock(controller, AllBlocks.ROTATION_SPEED_CONTROLLER.get())
        listOf(speed.east(), stress.east()).forEach {
            helper.setBlock(it, AllBlocks.CREATIVE_MOTOR.defaultState.setValue(DirectionalKineticBlock.FACING, Direction.WEST))
        }
        val guest = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()
        sequence
            .thenExecuteAfter(5) {
                listOf(speed.east(), stress.east()).forEach {
                    (helper.getBlockEntity(it) as CreativeMotorBlockEntity).generatedSpeed.setValue(-64)
                }
                nameDevice(helper, speed, "speed")
                nameDevice(helper, stress, "stress")
                nameDevice(helper, controller, "controller")
            }.thenWaitUntil {
                val actual = (helper.getBlockEntity(speed) as SpeedGaugeBlockEntity).speed
                helper.assertTrue(actual == 64f, "Create motor has not driven the speedometer: $actual")
            }
        guest.prepare(sequence, KINETIC_SOURCE)
        guest.awaitMarker(sequence, "kinetic-acquired")
        sequence.thenExecute {
            helper.assertTrue(
                (helper.getBlockEntity(controller) as SpeedControllerBlockEntity).targetSpeed.value == 37,
                "Guest controller write did not reach the actual block",
            )
            helper.setBlock(junction, Blocks.AIR)
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "kinetic-cut")
        sequence.thenExecute { helper.setBlock(junction, cable) }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "kinetic-restored")
        sequence
            .thenExecute {
                helper.setBlock(speed, Blocks.AIR)
                helper.setBlock(speed, gaugeState)
            }.thenWaitUntil {
                helper.assertTrue(
                    (helper.getBlockEntity(speed) as SpeedGaugeBlockEntity).speed == 64f,
                    "replacement speedometer did not rejoin the real kinetic network",
                )
            }.thenExecute { nameDevice(helper, speed, "speed") }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "kinetic-replaced")
        sequence.thenExecute {
            val result = nameDevice(helper, stress, "speed", expectSuccess = false)
            helper.assertTrue(result == PeripheralConfiguratorSaveResult.CONFLICT, "configurator accepted a duplicate name: $result")
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "kinetic-done")
        sequence.thenSucceed()
    }

    internal fun nameDevice(
        helper: GameTestHelper,
        position: BlockPos,
        name: String,
        expectSuccess: Boolean = true,
    ): PeripheralConfiguratorSaveResult {
        val absolute = helper.absolutePos(position)
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
        if (expectSuccess) {
            helper.assertTrue(
                result == PeripheralConfiguratorSaveResult.NAMED_DEVICE,
                "configurator could not name $position as $name: $result",
            )
        }
        return result
    }

    private val KINETIC_SOURCE =
        """
        import compukter.terminal.Terminal
        import create.kinetics.Kinetics
        fun main() {
            val speed = Kinetics.speedometer("speed")
            val stress = Kinetics.stressometer("stress")
            val controller = Kinetics.rotationController("controller")
            check(speed.speed() == 64f)
            check(stress.capacity() > 0f)
            check(stress.stress() >= 0f)
            check(controller.setTargetSpeed(37) == 37)
            Terminal.write("kinetic-acquired\n")
            readln()
            var failures = 0
            try { speed.speed() } catch (e: compukter.io.IOException) { failures += 1 }
            try { stress.capacity() } catch (e: compukter.io.IOException) { failures += 1 }
            try { controller.targetSpeed() } catch (e: compukter.io.IOException) { failures += 1 }
            try { Kinetics.speedometer("speed") } catch (e: IllegalStateException) { failures += 1 }
            check(failures == 4)
            Terminal.write("kinetic-cut\n")
            readln()
            var removed = false
            try { speed.speed() } catch (e: IllegalStateException) { removed = true }
            check(removed)
            val restored = Kinetics.speedometer("speed")
            check(restored.speed() == 64f)
            check(Kinetics.rotationController("controller").targetSpeed() == 37)
            check(Kinetics.stressometer("stress").capacity() > 0f)
            Terminal.write("kinetic-restored\n")
            readln()
            var stale = false
            try { restored.speed() } catch (e: compukter.io.IOException) { stale = true }
            check(stale)
            check(Kinetics.speedometer("speed").speed() == 64f)
            Terminal.write("kinetic-replaced\n")
            readln()
            check(Kinetics.stressometer("stress").capacity() > 0f)
            Terminal.write("kinetic-done\n")
        }
        """.trimIndent()
}

@EventBusSubscriber(modid = CompuktersCreateMod.MOD_ID)
object CreateGameTestRegistration {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterGameTestsEvent) {
        event.register(CreatePeripheralGameTests::class.java)
        event.register(CreateBoilerGameTests::class.java)
    }
}
