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
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralContract
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus

object DisplayPeripheralIntegration {
    private const val PROVIDER_ID = "compukters-display"
    private const val DEVICE_KEY = "text"

    private fun displayContract(id: String) =
        ComputerPeripheralContract<DisplayEndpoint>(
            id,
            PROVIDER_ID,
            DEVICE_KEY,
            ownsReachability = true,
        ) { level, computerPosition, identity ->
            val computer = level.getBlockEntity(computerPosition) as? ComputerBlockEntity
            computer?.let { resolve(level, it, identity.anchor, null) }?.let { endpoint ->
                PeripheralEndpoint(endpoint, endpoint.identity, endpoint.checkpointIdentity, endpoint::valid)
            }
        }

    internal val contract = displayContract("compukter:text_display")
    internal val graphicalContract = displayContract("compukter:graphical_display")

    fun register() {
        ComputerAddonHosts.registerPeripheral(
            factory =
                ComputerPeripheralHostFactory { level, position, _, peripherals ->
                    val computer =
                        level.getBlockEntity(position) as? ComputerBlockEntity
                            ?: return@ComputerPeripheralHostFactory null
                    DisplayHostState(
                        peripherals = peripherals,
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
                                    unavailable("Display discovery limit exceeded")
                                }
                            }
                        },
                    )
                },
            registrationIdentity = this,
            contracts = listOf(contract, graphicalContract),
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
        val display = level.getBlockEntity(position) as? DisplayBlockEntity
        val surface =
            if (display != null) {
                DisplayWorldAccess.surface(level, display)
            } else {
                DisplayStorage.get(level).directory.snapshot().singleOrNull { candidate ->
                    (0 until candidate.canvas.rows).any { row ->
                        (0 until candidate.canvas.columns).any { column -> DisplayWorldAccess.position(candidate, column, row) == position }
                    }
                }
            }
        surface ?: return null
        val machineEpoch = computer.terminalMachineId ?: return null
        return WorldDisplayEndpoint(level, computer, machineEpoch, surface)
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
        private val surface: ru.lazyhat.compukters.core.display.DisplaySurface,
    ) : DisplayEndpoint {
        override val identity: Any = surface.canvas
        override val checkpointIdentity: String get() = surface.id.toString()
        override val canvas: ru.lazyhat.compukters.core.display.DisplayCanvas = surface.canvas

        override fun leaseValid(): Boolean = computer.peripheralCheckpointPending || valid()

        private var expired = false

        override fun valid(): Boolean {
            if (expired) return false
            return available().also { if (!it) expired = true }
        }

        private fun available(): Boolean {
            check(level.server.isSameThread)
            val computerPosition = computer.blockPos
            if (!level.hasChunkAt(computerPosition) || computer.isRemoved ||
                level.getBlockEntity(computerPosition) !== computer
            ) {
                return false
            }
            if (computer.terminalMachineId != machineEpoch || !computer.peripheralResourcesAvailable()) return false
            if (DisplayStorage.get(level).directory.byId(surface.id) !== surface) return false
            return surface.panels.any { panel ->
                val position = DisplayWorldAccess.position(surface, panel.column, panel.row)
                if (!level.hasChunkAt(position)) return@any false
                val entity = level.getBlockEntity(position) as? DisplayBlockEntity ?: return@any false
                entity.checkpointIdentity == panel.instance.toString() && !entity.isRemoved &&
                    entity.blockState.getValue(DisplayBlock.FACING) == DisplayWorldAccess.facing(surface) &&
                    ComputerPeripheralLookup.isReachable(
                        level,
                        computerPosition,
                        ComputerPeripheralIdentity(PROVIDER_ID, position, DEVICE_KEY),
                    )
            }
        }
    }
}
