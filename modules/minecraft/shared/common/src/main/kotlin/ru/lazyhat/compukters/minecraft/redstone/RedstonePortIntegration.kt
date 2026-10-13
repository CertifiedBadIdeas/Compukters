/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.redstone

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.core.redstone.RedstonePortEndpoint
import ru.lazyhat.compukters.core.redstone.RedstonePortHost
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralHostFactory
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralProvider
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralContract
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookup

object RedstonePortIntegration {
    private const val PROVIDER = "compukters-redstone"
    private const val DEVICE = "port"
    internal val contract =
        ComputerPeripheralContract<RedstonePortEndpoint>(
            "compukter:redstone_port",
            PROVIDER,
            DEVICE,
            false,
            "compukter.redstone.RedstonePort",
        ) { level, position, identity ->
            val computer = level.getBlockEntity(position) as? ComputerBlockEntity
            val port = level.getBlockEntity(identity.anchor) as? RedstonePortBlockEntity
            if (computer == null || port == null || computer.terminalMachineId == null) {
                null
            } else {
                val endpoint = WorldEndpoint(level, computer, computer.terminalMachineId!!, port, identity)
                PeripheralEndpoint(endpoint, port, null, endpoint::valid)
            }
        }

    fun register() {
        ComputerAddonHosts.registerPeripheral(
            factory =
                ComputerPeripheralHostFactory { _, _, _, peripherals ->
                    RedstonePortHost({ peripherals.endpoint(contract, it) }, { peripherals.close(contract, it) })
                },
            registrationIdentity = this,
            contracts = listOf(contract),
            peripheralProvider =
                ComputerPeripheralProvider { level, position, _ ->
                    if (!level.hasChunkAt(position) || level.getBlockEntity(position) !is RedstonePortBlockEntity) {
                        null
                    } else {
                        ComputerPeripheralIdentity(PROVIDER, position.immutable(), DEVICE)
                    }
                },
        )
    }

    private class WorldEndpoint(
        private val level: ServerLevel,
        private val computer: ComputerBlockEntity,
        private val epoch: Long,
        private val port: RedstonePortBlockEntity,
        private val identity: ComputerPeripheralIdentity,
    ) : RedstonePortEndpoint {
        override val signals get() = port.signals
        private var acquired = false
        private var expired = false

        fun valid(): Boolean {
            check(level.server.isSameThread)
            if (expired) return false
            val valid =
                !computer.isRemoved && level.hasChunkAt(computer.blockPos) && level.getBlockEntity(computer.blockPos) === computer &&
                    computer.terminalMachineId == epoch && computer.peripheralResourcesAvailable() &&
                    !port.isRemoved && level.hasChunkAt(port.blockPos) && level.getBlockEntity(port.blockPos) === port &&
                    ComputerPeripheralLookup.isReachable(level, computer.blockPos, identity) &&
                    (!acquired || signals.owns(this))
            if (!valid) expired = true
            return valid
        }

        override fun acquire() {
            signals.acquire(this, ::worldValid)
            acquired = true
        }

        private fun worldValid(): Boolean = valid()

        override fun release() {
            signals.release(this)
        }
    }
}
