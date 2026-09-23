/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus

object DisplayPeripheralIntegration {
    private const val PROVIDER_ID = "compukters-display"
    private const val DEVICE_KEY = "text"

    fun register() {
        ComputerAddonHosts.register(
            factory =
                ComputerAddonHostFactory { level, position, _ ->
                    val computer =
                        level.getBlockEntity(position) as? ComputerBlockEntity
                            ?: return@ComputerAddonHostFactory null
                    DisplayHostState(
                        resolveSide = { side ->
                            val direction = directionFor(computer.blockState.getValue(ComputerBlock.FACING), side)
                            direction?.let { resolve(level, computer, position.relative(it), side) }
                        },
                        resolveName = { name ->
                            val result = ComputerPeripheralLookup.find(level, position, PROVIDER_ID, name)
                            when (result.status) {
                                ComputerPeripheralLookupStatus.FOUND -> {
                                    val identity = checkNotNull(result.identity)
                                    if (identity.deviceKey != DEVICE_KEY) {
                                        unavailable("Named peripheral is not a text display")
                                    } else {
                                        resolve(level, computer, identity.anchor, null)
                                            ?.let(DisplayResolution::Found)
                                            ?: unavailable("Named display is removed or unloaded")
                                    }
                                }

                                ComputerPeripheralLookupStatus.MISSING -> {
                                    unavailable("No reachable display has that name")
                                }

                                ComputerPeripheralLookupStatus.AMBIGUOUS -> {
                                    DisplayResolution.Failed(HostFailureKind.OTHER, "Display name is ambiguous")
                                }

                                ComputerPeripheralLookupStatus.INVALID_NAME -> {
                                    DisplayResolution.Failed(HostFailureKind.OTHER, "Invalid display name")
                                }

                                ComputerPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED -> {
                                    unavailable("Display cable topology limit exceeded")
                                }
                            }
                        },
                    )
                },
            registrationIdentity = this,
            peripheralProvider =
                ComputerPeripheralProvider { level, position, _ ->
                    if (!level.hasChunkAt(position) || level.getBlockEntity(position) !is DisplayBlockEntity) {
                        null
                    } else {
                        ComputerPeripheralIdentity(PROVIDER_ID, position.immutable(), DEVICE_KEY)
                    }
                },
        )
    }

    private fun resolve(
        level: ServerLevel,
        computer: ComputerBlockEntity,
        position: BlockPos,
        side: Int?,
    ): DisplayEndpoint? {
        check(level.server.isSameThread) { "display peripherals must be resolved on the server thread" }
        if (!level.hasChunkAt(position)) return null
        val display = level.getBlockEntity(position) as? DisplayBlockEntity ?: return null
        val machineEpoch = computer.terminalMachineId ?: return null
        return WorldDisplayEndpoint(level, computer, machineEpoch, display, side)
    }

    private fun directionFor(
        facing: Direction,
        side: Int,
    ): Direction? =
        when (side) {
            0 -> facing
            1 -> facing.opposite
            2 -> facing.counterClockWise
            3 -> facing.clockWise
            4 -> Direction.UP
            5 -> Direction.DOWN
            else -> null
        }

    private fun unavailable(detail: String) = DisplayResolution.Failed(HostFailureKind.UNAVAILABLE, detail)

    private class WorldDisplayEndpoint(
        private val level: ServerLevel,
        private val computer: ComputerBlockEntity,
        private val machineEpoch: Long,
        private val display: DisplayBlockEntity,
        private val side: Int?,
    ) : DisplayEndpoint {
        override val identity: Any = display
        override val buffer: DisplayBuffer = display.buffer

        override fun valid(): Boolean {
            check(level.server.isSameThread) { "display handles must be validated on the server thread" }
            val computerPosition = computer.blockPos
            val displayPosition = display.blockPos
            if (!level.hasChunkAt(computerPosition) || !level.hasChunkAt(displayPosition)) return false
            if (computer.isRemoved || display.isRemoved) return false
            if (level.getBlockEntity(computerPosition) !== computer || level.getBlockEntity(displayPosition) !== display) return false
            if (computer.terminalMachineId != machineEpoch || !computer.runtimeState.isPoweredOn()) return false
            return if (side == null) {
                ComputerPeripheralLookup.isReachable(
                    level,
                    computerPosition,
                    ComputerPeripheralIdentity(PROVIDER_ID, displayPosition, DEVICE_KEY),
                )
            } else {
                val direction = directionFor(computer.blockState.getValue(ComputerBlock.FACING), side) ?: return false
                computerPosition.relative(direction) == displayPosition
            }
        }
    }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this is ProgramComputerState.Running ||
            this is ProgramComputerState.WaitingForInput ||
            this is ProgramComputerState.WaitingForCompiler
}
