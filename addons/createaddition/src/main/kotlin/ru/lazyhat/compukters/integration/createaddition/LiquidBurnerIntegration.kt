/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.createaddition

import com.mrh0.createaddition.blocks.liquid_blaze_burner.LiquidBlazeBurnerBlockEntity
import createaddition.BurnerStatus
import createaddition.CreateadditionAddonContract
import createaddition.CreateadditionCapabilityHandler
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.capabilities.Capabilities
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralAccessException
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralContract
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralDevice
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralEndpoint
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

object LiquidBurnerIntegration {
    private val contract =
        CompuktersPeripheralContract("createaddition:liquid_burner", "liquid_burner") { location ->
            (location.level.getBlockEntity(location.position) as? LiquidBlazeBurnerBlockEntity)?.let { entity ->
                LiquidBurnerEndpoint(location.level, location.position, entity) { location.isReachable() }
            }
        }

    fun register() {
        CompuktersAddonRegistry.register(
            CreateadditionAddonContract.guestApi(LiquidBurnerIntegration::class.java),
            CompuktersAddonHostFactory { computer ->
                CreateadditionAddonContract.host(LiquidBurnerHost { handle -> computer.peripheral(contract, handle) })
            },
            { contact ->
                if (contact.level.getBlockEntity(contact.position) is LiquidBlazeBurnerBlockEntity) {
                    CompuktersPeripheralDevice(contact.position, "liquid_burner")
                } else {
                    null
                }
            },
            listOf(contract),
        )
    }
}

internal interface BurnerAccess : CompuktersPeripheralEndpoint {
    fun status(): BurnerStatus
}

internal class LiquidBurnerHost(
    private val resolve: (Int) -> BurnerAccess,
) : CreateadditionCapabilityHandler {
    private var used = false

    override fun status(argument0: Int): AddonCallResult<BurnerStatus> {
        used = true
        return try {
            val endpoint = resolve(argument0)
            if (!endpoint.valid()) return addonFailed(HostFailureKind.INPUT_OUTPUT, "Liquid burner is stale or disconnected")
            val status = endpoint.status()
            if (status.fuelCapacity <= 0 || status.fuelAmount !in 0..status.fuelCapacity || status.heat !in 0..4 || status.burnTicks < 0) {
                addonFailed(HostFailureKind.OTHER, "Liquid burner returned invalid readings")
            } else {
                addonCompleted(status)
            }
        } catch (failure: CompuktersPeripheralAccessException) {
            addonFailed(failure.kind, failure.message)
        } catch (failure: IllegalStateException) {
            addonFailed(HostFailureKind.INPUT_OUTPUT, "Liquid burner fluid storage is unavailable")
        }
    }

    override fun checkpoint(): ByteArray {
        check(!used) { "Active liquid burner resources do not support hibernation" }
        return byteArrayOf()
    }

    override fun restoreCheckpoint(state: ByteArray) {
        require(state.isEmpty()) { "Liquid burner resources do not support restoration" }
        reset()
    }

    override fun reset() {
        used = false
    }
}

internal class LiquidBurnerEndpoint(
    private val level: ServerLevel,
    private val position: BlockPos,
    private val entity: LiquidBlazeBurnerBlockEntity,
    private val reachable: () -> Boolean,
) : BurnerAccess {
    override val identity: Any get() = entity
    private var stale = false

    override fun valid(): Boolean {
        check(level.server.isSameThread)
        if (!stale) stale = !level.hasChunkAt(position) || level.getBlockEntity(position) !== entity || !reachable()
        return !stale
    }

    override fun status(): BurnerStatus {
        check(level.server.isSameThread)
        val tank = checkNotNull(level.getCapability(Capabilities.FluidHandler.BLOCK, position, null))
        check(tank.tanks == 1)
        return BurnerStatus(
            tank.getFluidInTank(0).amount,
            tank.getTankCapacity(0),
            entity.heatLevelForRender.ordinal,
            entity.getRemainingBurnTime(),
        )
    }
}
