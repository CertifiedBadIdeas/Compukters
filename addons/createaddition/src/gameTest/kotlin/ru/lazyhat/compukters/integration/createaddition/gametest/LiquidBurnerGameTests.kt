/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.createaddition.gametest

import com.mrh0.createaddition.index.CABlocks
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluids
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.integration.createaddition.CompuktersCreateadditionMod
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult

@PrefixGameTestTemplate(false)
object LiquidBurnerGameTests {
    @JvmStatic
    @GameTest(batch = "liquid_burner", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_000_000)
    fun namedBurnerLifecycle(helper: GameTestHelper) {
        val computer = BlockPos(2, 2, 3)
        val burner = BlockPos(5, 2, 3)
        helper.setBlock(computer, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(burner, CABlocks.LIQUID_BLAZE_BURNER.get())
        val guest = GuestComputerScenario(helper, computer)
        val sequence = helper.startSequence()
        sequence.thenExecuteAfter(5) {
            val tank = checkNotNull(helper.level.getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(burner), null))
            helper.assertTrue(tank.fill(FluidStack(Fluids.LAVA, 1), IFluidHandler.FluidAction.EXECUTE) == 1, "Could not insert one mB")
            PeripheralNetworkGameTestFixtures.bind(helper, helper.absolutePos(computer), helper.absolutePos(burner))
            name(helper, burner)
        }
        guest.prepare(sequence, SOURCE)
        guest.awaitMarker(sequence, "burner-acquired")
        sequence.thenExecute {
            val tank = checkNotNull(helper.level.getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(burner), null))
            helper.assertTrue(tank.drain(1, IFluidHandler.FluidAction.EXECUTE).amount == 1, "Last mB must be recoverable")
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "burner-empty")
        sequence.thenExecute {
            helper.setBlock(burner, Blocks.AIR)
            helper.setBlock(burner, CABlocks.LIQUID_BLAZE_BURNER.get())
            PeripheralNetworkGameTestFixtures.removeReplaced(helper, helper.absolutePos(computer), "burner")
            PeripheralNetworkGameTestFixtures.bind(helper, helper.absolutePos(computer), helper.absolutePos(burner))
            name(helper, burner)
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "burner-replaced")
        sequence.thenExecute { PeripheralNetworkGameTestFixtures.unbind(helper, helper.absolutePos(computer)) }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "burner-disconnected")
        sequence.thenSucceed()
    }

    private fun name(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        helper.assertTrue(
            PeripheralNetworkGameTestFixtures.name(
                helper,
                helper.absolutePos(position),
                "burner",
            ) == PeripheralConfiguratorSaveResult.NAMED_DEVICE,
            "Could not name burner",
        )
    }

    private val SOURCE =
        """
        import createaddition.boiler.LiquidBurner
        fun main() {
            val burner = LiquidBurner.named("burner")
            check(LiquidBurner.first() == burner)
            val initial = burner.status()
            check(initial.fuelAmount == 1 && initial.fuelCapacity > 0 && initial.burnTicks == 0)
            println("burner-acquired")
            readln()
            check(burner.status().fuelAmount == 0)
            println("burner-empty")
            readln()
            var stale = false
            try { burner.status() } catch (e: compukter.io.IOException) { stale = true }
            check(stale)
            val restored = LiquidBurner.named("burner")
            check(restored != burner && restored.status().fuelAmount == 0)
            println("burner-replaced")
            readln()
            var cut = false
            try { restored.status() } catch (e: compukter.io.IOException) { cut = true }
            check(cut)
            println("burner-disconnected")
        }
        """.trimIndent()
}

@EventBusSubscriber(modid = CompuktersCreateadditionMod.MOD_ID)
object LiquidBurnerGameTestRegistration {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterGameTestsEvent) {
        event.register(LiquidBurnerGameTests::class.java)
    }
}
