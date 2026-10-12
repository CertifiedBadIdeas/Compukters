/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralProgramHost
import ru.lazyhat.compukters.core.redstone.RedstonePortHost
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralNames
import ru.lazyhat.compukters.minecraft.redstone.RedstonePortBlockEntity
import java.util.concurrent.CompletableFuture

/** Production process hosts and a real Guest program exercise ownership and six-face redstone I/O. */
internal object RedstonePortGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        val otherPosition = BlockPos(8, 2, 2)
        val portPosition = BlockPos(5, 2, 2)
        val displayPosition = BlockPos(5, 2, 5)
        helper.setBlock(computerPosition, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(otherPosition, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(portPosition, CompuktersRegistry.REDSTONE_PORT.get())
        helper.setBlock(displayPosition, CompuktersRegistry.DISPLAY.get())
        val level = helper.level
        val absolutePort = helper.absolutePos(portPosition)
        val port = level.getBlockEntity(absolutePort) as RedstonePortBlockEntity
        PeripheralNetworkGameTestFixtures.bind(helper, computerPosition, otherPosition, portPosition, displayPosition)
        ComputerPeripheralNames.setName(level, ComputerPeripheralIdentity("compukters-redstone", absolutePort, "port"), "relay")
        ComputerPeripheralNames.setName(
            level,
            ComputerPeripheralIdentity("compukters-display", helper.absolutePos(displayPosition), "text"),
            "panel",
        )
        val computer = helper.compuktersComputerBlockEntity(computerPosition)
        val other = helper.compuktersComputerBlockEntity(otherPosition)
        computer.prepareTerminalAsync()
        other.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var sequence = 0L

        fun request(
            host: ProgramAddonHost,
            process: Long,
            capability: CapabilityIdentity,
            operation: Int,
            vararg arguments: VmValue,
        ): HostResponse =
            (
                host.dispatch(
                    ProgramAddonRequest(VmHostRequestIdentity(1, ++sequence), capability, operation, arguments.toList(), process),
                ) as ProgramAddonDispatch.Completed
            ).response

        fun named(
            host: ProgramAddonHost,
            process: Long,
            contract: String,
            name: String,
        ): HostResponse =
            request(host, process, PeripheralProgramHost.SCHEMA.identity, 5, VmValue.StringValue(contract), VmValue.StringValue(name))

        fun busy(response: HostResponse) =
            helper.assertTrue(
                response is HostResponse.Failure && response.kind == HostFailureKind.UNAVAILABLE,
                "another process acquired a busy peripheral: $response",
            )

        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(
                    computer.runtimeState == ProgramComputerState.WaitingForInput &&
                        other.runtimeState == ProgramComputerState.WaitingForInput,
                    "computer shells are not ready",
                )
            }.thenExecute {
                // The loader GameTest compilation cannot access the common module's internal factory in Kotlin.
                // Invoke that production factory by its JVM signature without adding a public test-only API.
                val factory =
                    ComputerAddonHosts::class.java.methods.single {
                        it.returnType == ProgramAddonHost::class.java &&
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    net.minecraft.server.level.ServerLevel::class.java,
                                    BlockPos::class.java,
                                    net.minecraft.world.level.block.state.BlockState::class.java,
                                ),
                            )
                    }

                fun processHost(position: BlockPos): ProgramAddonHost =
                    factory.invoke(ComputerAddonHosts, level, position, level.getBlockState(position)) as ProgramAddonHost
                val owner = processHost(computer.blockPos)
                val competitor = processHost(other.blockPos)
                owner.use {
                    competitor.use {
                        owner.programStarted(1)
                        val handle = (named(owner, 1, "compukter:redstone_port", "relay") as HostResponse.IntSuccess).value
                        owner.programStarted(2)
                        busy(named(owner, 2, "compukter:redstone_port", "relay"))
                        busy(named(competitor, 0, "compukter:redstone_port", "relay"))
                        helper.assertTrue(
                            request(owner, 1, RedstonePortHost.SCHEMA.identity, 1, VmValue.I32(handle), VmValue.I32(4), VmValue.I32(28)) ==
                                HostResponse.UnitSuccess,
                            "owner could not set port output",
                        )
                        helper.assertTrue(
                            level.getDirectSignal(absolutePort, Direction.DOWN) == 12,
                            "port top direct output has wrong direction or level",
                        )
                        owner.programStopped(1)
                        helper.assertTrue(port.output(Direction.UP) == 0, "process exit retained port output")
                        named(owner, 2, "compukter:redstone_port", "relay") as HostResponse.IntSuccess
                        owner.programStopped(2)
                        val competitorHandle = (named(competitor, 0, "compukter:redstone_port", "relay") as HostResponse.IntSuccess).value
                        request(
                            competitor,
                            0,
                            RedstonePortHost.SCHEMA.identity,
                            1,
                            VmValue.I32(competitorHandle),
                            VmValue.I32(4),
                            VmValue.I32(28),
                        )
                        val saved = port.saveWithFullMetadata(level.registryAccess())
                        val restored =
                            net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                                absolutePort,
                                port.blockState,
                                saved,
                                level.registryAccess(),
                            ) as RedstonePortBlockEntity
                        helper.assertTrue(restored.output(Direction.UP) == 0, "NBT restored transient output")
                        PeripheralNetworkGameTestFixtures.unbind(helper, portPosition)
                        ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralOwnership
                            .expire(level.server)
                        helper.assertTrue(port.output(Direction.UP) == 0, "network removal retained port output")
                        val stale =
                            request(competitor, 0, RedstonePortHost.SCHEMA.identity, 0, VmValue.I32(competitorHandle), VmValue.I32(4))
                        helper.assertTrue(
                            stale is HostResponse.Failure && stale.kind == HostFailureKind.INPUT_OUTPUT,
                            "disconnected port handle remained valid",
                        )
                        PeripheralNetworkGameTestFixtures.bind(helper, computerPosition, portPosition)
                        ComputerPeripheralNames.setName(
                            level,
                            ComputerPeripheralIdentity("compukters-redstone", absolutePort, "port"),
                            "relay",
                        )
                        named(competitor, 0, "compukter:redstone_port", "relay") as HostResponse.IntSuccess
                        // Text and graphics are contracts of the same physical canvas, before any write.
                        named(owner, 0, "compukter:text_display", "panel") as HostResponse.IntSuccess
                        busy(named(competitor, 0, "compukter:graphical_display", "panel"))
                    }
                }
                helper.assertTrue(port.output(Direction.UP) == 0, "closing computer host retained port output")
                helper.setBlock(portPosition.north(), Blocks.REDSTONE_BLOCK)
                helper.assertTrue(level.getSignal(absolutePort, Direction.UP) == 0, "port passively forwarded input")
            }.thenIdle(2)
            .thenExecute {
                val artifact = requireNotNull(javaClass.getResourceAsStream("/fixtures/redstone-port.cpkt")).use { it.readAllBytes() }
                setup =
                    computer
                        .verifyForDeployAsync(artifact)
                        .thenCompose { candidate ->
                            computer.executableRevisionAsync("/home/relay").thenCompose { expected ->
                                computer.deployAsync("/home/relay", requireNotNull(expected), requireNotNull(candidate))
                            }
                        }.thenCompose { computer.submitTerminalTextAsync("relay") }
                        .thenCompose { accepted ->
                            check(accepted)
                            computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                        }.thenAccept { accepted -> check(accepted) }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "Guest deployment is pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                helper.assertTrue(
                    level.getSignal(absolutePort, Direction.WEST) == 7 && level.getDirectSignal(absolutePort, Direction.DOWN) == 12 &&
                        level.getSignal(absolutePort, Direction.UP) == 3,
                    "Guest did not set broadcast, weak and direct port outputs",
                )
            }.thenExecute {
                helper.setBlock(portPosition.north(), Blocks.AIR)
            }.thenWaitUntil {
                helper.assertTrue(
                    level.getDirectSignal(absolutePort, Direction.DOWN) == 0 && level.getSignal(absolutePort, Direction.NORTH) == 9,
                    "Guest input wait did not observe zero",
                )
            }.thenExecute {
                helper.setBlock(portPosition.north(), Blocks.REDSTONE_BLOCK)
            }.thenWaitUntil {
                helper.assertTrue(
                    (0..5).all { port.signals.output(it) == 0 },
                    "Guest close retained port outputs",
                )
                helper.assertTrue(computer.runtimeState == ProgramComputerState.WaitingForInput, "Guest port program did not exit")
            }.thenExecute {
                // Exercise actual carrier unload and NBT restoration after Guest ownership has been released.
                val lease = Any()
                port.signals.acquire(lease) { true }
                port.signals.setAll(lease, 31)
                val saved = port.saveWithFullMetadata(level.registryAccess())
                level.removeBlockEntity(absolutePort)
                helper.assertTrue(
                    (0..5).all { port.signals.output(it) == 0 } && !port.signals.owns(lease),
                    "port unload retained outputs or control",
                )
                val restored =
                    net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        absolutePort,
                        port.blockState,
                        saved,
                        level.registryAccess(),
                    ) as RedstonePortBlockEntity
                level.setBlockEntity(restored)
                helper.assertTrue((0..5).all { restored.signals.output(it) == 0 }, "reloaded port restored output levels")
            }.thenSucceed()
    }
}
