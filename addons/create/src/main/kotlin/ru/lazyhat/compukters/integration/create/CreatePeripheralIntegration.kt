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

package ru.lazyhat.compukters.integration.create

import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity
import com.simibubi.create.content.logistics.BigItemStack
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager
import com.simibubi.create.content.logistics.stockTicker.PackageOrder
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity
import create.CreateAddonContract
import create.CreateCapabilityHandler
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.addonPending
import ru.lazyhat.compukters.api.addon.addonPollCompleted
import ru.lazyhat.compukters.api.addon.addonPollFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralContact
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralDevice
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralLookupStatus
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralProvider
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

object CreatePeripheralIntegration {
    fun register() {
        CompuktersAddonRegistry.register(
            CreateAddonContract.guestApi(CreatePeripheralIntegration::class.java),
            CompuktersAddonHostFactory { computer ->
                CreateAddonContract.host(
                    CreateHostState(
                        resolveSide = { side, kind -> resolveEndpoint(computer, side, kind) },
                        resolveName = { name, kind -> resolveNamedEndpoint(computer, name, kind) },
                    ),
                )
            },
            CompuktersPeripheralProvider(::resolvePeripheral),
        )
    }

    private fun resolvePeripheral(contact: CompuktersPeripheralContact): CompuktersPeripheralDevice? =
        when (contact.level.getBlockEntity(contact.position)) {
            is SpeedGaugeBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.SPEEDOMETER.deviceKey)
            }

            is StressGaugeBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.STRESSOMETER.deviceKey)
            }

            is SpeedControllerBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.ROTATION_CONTROLLER.deviceKey)
            }

            is StockTickerBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.STOCK_TICKER.deviceKey)
            }

            else -> {
                null
            }
        }

    private fun resolveEndpoint(
        computer: CompuktersComputerContext,
        side: Int,
        kind: PeripheralKind,
    ): CreateDeviceEndpoint? {
        val level = computer.level
        check(level.server.isSameThread) { "Create peripherals must be accessed on the server thread" }
        val direction = computer.adjacentDirection(side) ?: return null
        val position = computer.position.relative(direction)
        return resolveEndpoint(computer.level, position, kind)
    }

    private fun resolveNamedEndpoint(
        computer: CompuktersComputerContext,
        name: String,
        kind: PeripheralKind,
    ): CreateNamedResolution {
        val lookup = computer.findPeripheral(name)
        return when (lookup.status) {
            CompuktersPeripheralLookupStatus.FOUND -> {
                val device = checkNotNull(lookup.device)
                if (device.deviceKey != kind.deviceKey) {
                    CreateNamedResolution.Failed(
                        HostFailureKind.UNAVAILABLE,
                        "Named peripheral is not the requested Create device type",
                    )
                } else {
                    resolveEndpoint(
                        computer.level,
                        device.anchor,
                        kind,
                        latchingValidity { computer.isPeripheralReachable(device) },
                    )?.let(CreateNamedResolution::Found)
                        ?: CreateNamedResolution.Failed(
                            HostFailureKind.UNAVAILABLE,
                            "Named Create device was removed, replaced, or unloaded",
                        )
                }
            }

            CompuktersPeripheralLookupStatus.MISSING -> {
                CreateNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "No reachable Create peripheral has that name")
            }

            CompuktersPeripheralLookupStatus.AMBIGUOUS -> {
                CreateNamedResolution.Failed(HostFailureKind.OTHER, "More than one reachable Create peripheral has that name")
            }

            CompuktersPeripheralLookupStatus.INVALID_NAME -> {
                CreateNamedResolution.Failed(HostFailureKind.OTHER, "Invalid peripheral name")
            }

            CompuktersPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED -> {
                CreateNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "Peripheral cable topology limit was exceeded")
            }
        }
    }

    private fun resolveEndpoint(
        level: ServerLevel,
        position: BlockPos,
        kind: PeripheralKind,
        reachable: () -> Boolean = { true },
    ): CreateDeviceEndpoint? {
        if (!level.hasChunkAt(position)) return null
        return when (val entity = level.getBlockEntity(position)) {
            is SpeedGaugeBlockEntity -> {
                if (kind == PeripheralKind.SPEEDOMETER) {
                    SpeedometerEndpoint(level, position, entity, reachable)
                } else {
                    null
                }
            }

            is StressGaugeBlockEntity -> {
                if (kind == PeripheralKind.STRESSOMETER) {
                    StressometerEndpoint(level, position, entity, reachable)
                } else {
                    null
                }
            }

            is SpeedControllerBlockEntity -> {
                if (kind == PeripheralKind.ROTATION_CONTROLLER) {
                    RotationControllerEndpoint(level, position, entity, reachable)
                } else {
                    null
                }
            }

            is StockTickerBlockEntity -> {
                if (kind == PeripheralKind.STOCK_TICKER) {
                    StockTickerEndpoint(level, position, entity, reachable)
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }
}

internal class CreateHostState(
    private val resolveSide: (Int, PeripheralKind) -> CreateDeviceEndpoint?,
    private val resolveName: (String, PeripheralKind) -> CreateNamedResolution,
) : CreateCapabilityHandler {
    constructor(resolveSide: (Int, PeripheralKind) -> CreateDeviceEndpoint?) : this(
        resolveSide,
        { _, _ -> CreateNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "No reachable Create peripheral has that name") },
    )

    private val handles = linkedMapOf<Int, CreateDeviceEndpoint>()
    private val handlesByEndpoint = mutableMapOf<Any, Int>()
    private val snapshots = linkedMapOf<Int, RetainedStockSnapshot>()
    private var nextHandle = 1
    private var nextSnapshotHandle = 1

    override fun acquireSpeedometer(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.SPEEDOMETER)

    override fun speed(argument0: Int): AddonCallResult<Float> = withEndpoint<SpeedometerAccess, Float>(argument0) { it.speed() }

    override fun awaitSpeedChange(argument0: Int): AddonCallResult<Float> {
        val endpoint = endpoint<SpeedometerAccess>(argument0) ?: return endpointFailure()
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        val speedBits = endpoint.speed().toBits()
        return addonPending {
            if (!endpoint.valid()) {
                removeEndpoint(endpoint)
                addonPollFailed(HostFailureKind.INPUT_OUTPUT, STALE_PERIPHERAL_DETAIL)
            } else {
                endpoint.speed().takeIf { it.toBits() != speedBits }?.let(::addonPollCompleted)
            }
        }
    }

    override fun acquireStressometer(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.STRESSOMETER)

    override fun stress(argument0: Int): AddonCallResult<Float> = withEndpoint<StressometerAccess, Float>(argument0) { it.stress() }

    override fun capacity(argument0: Int): AddonCallResult<Float> = withEndpoint<StressometerAccess, Float>(argument0) { it.capacity() }

    override fun awaitStressChange(argument0: Int): AddonCallResult<Unit> {
        val endpoint = endpoint<StressometerAccess>(argument0) ?: return endpointFailure()
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        val stressBits = endpoint.stress().toBits()
        val capacityBits = endpoint.capacity().toBits()
        return addonPending {
            if (!endpoint.valid()) {
                removeEndpoint(endpoint)
                addonPollFailed(HostFailureKind.INPUT_OUTPUT, STALE_PERIPHERAL_DETAIL)
            } else if (endpoint.stress().toBits() != stressBits || endpoint.capacity().toBits() != capacityBits) {
                addonPollCompleted(Unit)
            } else {
                null
            }
        }
    }

    override fun acquireRotationController(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.ROTATION_CONTROLLER)

    override fun targetSpeed(argument0: Int): AddonCallResult<Int> =
        withEndpoint<RotationControllerAccess, Int>(argument0) { it.targetSpeed() }

    override fun setTargetSpeed(
        argument0: Int,
        argument1: Int,
    ): AddonCallResult<Int> = withEndpoint<RotationControllerAccess, Int>(argument0) { it.setTargetSpeed(argument1) }

    override fun acquireRotationControllerByName(argument0: String): AddonCallResult<Int> =
        acquireNamed(argument0, PeripheralKind.ROTATION_CONTROLLER)

    override fun acquireSpeedometerByName(argument0: String): AddonCallResult<Int> = acquireNamed(argument0, PeripheralKind.SPEEDOMETER)

    override fun acquireStressometerByName(argument0: String): AddonCallResult<Int> = acquireNamed(argument0, PeripheralKind.STRESSOMETER)

    override fun acquireStockTicker(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.STOCK_TICKER)

    override fun acquireStockTickerByName(argument0: String): AddonCallResult<Int> = acquireNamed(argument0, PeripheralKind.STOCK_TICKER)

    override fun snapshot(argument0: Int): AddonCallResult<Int> {
        val endpoint = endpoint<StockTickerAccess>(argument0) ?: return endpointFailure()
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        if (snapshots.size >= MAXIMUM_STOCK_SNAPSHOTS) {
            return addonFailed(HostFailureKind.UNAVAILABLE, "Create stock snapshot limit was reached")
        }
        val entries =
            endpoint.snapshot(MAXIMUM_STOCK_ENTRIES)
                ?: return addonFailed(HostFailureKind.UNAVAILABLE, "Create stock exceeds the snapshot entry limit")
        val handle = nextSnapshotHandle++
        snapshots[handle] = RetainedStockSnapshot(endpoint, entries)
        return addonCompleted(handle)
    }

    override fun snapshotSize(argument0: Int): AddonCallResult<Int> = withSnapshot(argument0) { addonCompleted(it.entries.size) }

    override fun snapshotItemId(
        argument0: Int,
        argument1: Int,
    ): AddonCallResult<String> = withStockEntry(argument0, argument1) { addonCompleted(it.itemId) }

    override fun snapshotDisplayName(
        argument0: Int,
        argument1: Int,
    ): AddonCallResult<String> = withStockEntry(argument0, argument1) { addonCompleted(it.displayName) }

    override fun snapshotCount(
        argument0: Int,
        argument1: Int,
    ): AddonCallResult<Int> = withStockEntry(argument0, argument1) { addonCompleted(it.count) }

    override fun closeSnapshot(argument0: Int): AddonCallResult<Unit> {
        snapshots.remove(argument0)
        return addonCompleted(Unit)
    }

    override fun request(
        argument0: Int,
        argument1: Int,
        argument2: Int,
        argument3: String,
    ): AddonCallResult<Boolean> =
        withStockEntry(argument0, argument1) { entry ->
            when {
                argument2 !in 1..MAXIMUM_REQUEST_QUANTITY -> {
                    addonFailed(HostFailureKind.OTHER, "Create stock request quantity must be 1..$MAXIMUM_REQUEST_QUANTITY")
                }

                argument2 > entry.count -> {
                    addonFailed(HostFailureKind.UNAVAILABLE, "Requested quantity exceeds the stock snapshot")
                }

                !validPackageAddress(argument3) -> {
                    addonFailed(HostFailureKind.OTHER, "Create package address is invalid")
                }

                else -> {
                    entry.request(argument2, argument3)?.let(::addonCompleted)
                        ?: addonFailed(HostFailureKind.UNAVAILABLE, "Requested Create stock is no longer available")
                }
            }
        }

    override fun reset() {
        handles.clear()
        handlesByEndpoint.clear()
        snapshots.clear()
        nextHandle = 1
        nextSnapshotHandle = 1
    }

    private fun acquire(
        side: Int,
        kind: PeripheralKind,
    ): AddonCallResult<Int> {
        if (side !in 0..5) return addonFailed(HostFailureKind.OTHER, "Invalid Create kinetic side")
        val endpoint =
            resolveSide(side, kind)
                ?: return addonFailed(
                    HostFailureKind.UNAVAILABLE,
                    if (kind == PeripheralKind.STOCK_TICKER) {
                        "No Create Stock Ticker is attached on that side"
                    } else {
                        "No matching Create kinetic device is attached on that side"
                    },
                )
        return retain(endpoint)
    }

    private fun acquireNamed(
        name: String,
        kind: PeripheralKind,
    ): AddonCallResult<Int> =
        when (val resolution = resolveName(name, kind)) {
            is CreateNamedResolution.Failed -> addonFailed(resolution.kind, resolution.detail)
            is CreateNamedResolution.Found -> retain(resolution.endpoint)
        }

    private fun retain(endpoint: CreateDeviceEndpoint): AddonCallResult<Int> {
        val existing = handlesByEndpoint[endpoint.identity]
        if (existing != null) return addonCompleted(existing)
        if (handles.size >= MAXIMUM_HANDLES) {
            return addonFailed(HostFailureKind.UNAVAILABLE, "Create kinetic device handle limit was reached")
        }
        val handle = nextHandle++
        handles[handle] = endpoint
        handlesByEndpoint[endpoint.identity] = handle
        return addonCompleted(handle)
    }

    private inline fun <reified T : CreateDeviceEndpoint, R> withEndpoint(
        handle: Int,
        operation: (T) -> R,
    ): AddonCallResult<R> {
        val endpoint = endpoint<T>(handle) ?: return endpointFailure()
        return if (endpoint.valid()) addonCompleted(operation(endpoint)) else staleEndpoint(endpoint)
    }

    private inline fun <reified T : CreateDeviceEndpoint> endpoint(handle: Int): T? = handles[handle] as? T

    private inline fun <R> withSnapshot(
        handle: Int,
        operation: (RetainedStockSnapshot) -> AddonCallResult<R>,
    ): AddonCallResult<R> {
        val snapshot = snapshots[handle] ?: return addonFailed(HostFailureKind.UNAVAILABLE, "Create stock snapshot is unavailable")
        if (!snapshot.endpoint.valid()) return staleEndpoint(snapshot.endpoint)
        return operation(snapshot)
    }

    private inline fun <R> withStockEntry(
        handle: Int,
        index: Int,
        operation: (StockSnapshotEntry) -> AddonCallResult<R>,
    ): AddonCallResult<R> =
        withSnapshot(handle) { snapshot ->
            val entry =
                snapshot.entries.getOrNull(index)
                    ?: return@withSnapshot addonFailed(HostFailureKind.OTHER, "Create stock snapshot index is out of range")
            operation(entry)
        }

    private fun endpointFailure(): AddonCallResult<Nothing> =
        addonFailed(HostFailureKind.UNAVAILABLE, "Create kinetic device handle is unavailable")

    private fun staleEndpoint(endpoint: CreateDeviceEndpoint): AddonCallResult<Nothing> {
        removeEndpoint(endpoint)
        return addonFailed(
            HostFailureKind.INPUT_OUTPUT,
            if (endpoint is StockTickerAccess) STALE_STOCK_TICKER_DETAIL else STALE_PERIPHERAL_DETAIL,
        )
    }

    private fun removeEndpoint(endpoint: CreateDeviceEndpoint) {
        val handle = handlesByEndpoint.remove(endpoint.identity) ?: return
        handles.remove(handle)
        snapshots.entries.removeIf { it.value.endpoint.identity === endpoint.identity }
    }
}

internal data class RetainedStockSnapshot(
    val endpoint: StockTickerAccess,
    val entries: List<StockSnapshotEntry>,
)

internal interface StockSnapshotEntry {
    val itemId: String
    val displayName: String
    val count: Int

    /** Null means the exact variant no longer has enough current stock. */
    fun request(
        quantity: Int,
        address: String,
    ): Boolean?
}

internal interface StockTickerAccess : CreateDeviceEndpoint {
    /** Null means more entries than [maximumEntries], with no partial result. */
    fun snapshot(maximumEntries: Int): List<StockSnapshotEntry>?
}

private fun validPackageAddress(address: String): Boolean =
    address.isNotBlank() && address.toByteArray(Charsets.UTF_8).size <= MAXIMUM_ADDRESS_BYTES && address.none(Char::isISOControl)

internal enum class PeripheralKind {
    SPEEDOMETER,
    STRESSOMETER,
    ROTATION_CONTROLLER,
    STOCK_TICKER,
    ;

    val deviceKey: String
        get() = name.lowercase()
}

internal sealed interface CreateNamedResolution {
    data class Found(
        val endpoint: CreateDeviceEndpoint,
    ) : CreateNamedResolution

    data class Failed(
        val kind: HostFailureKind,
        val detail: String,
    ) : CreateNamedResolution
}

internal interface CreateDeviceEndpoint {
    val identity: Any

    fun valid(): Boolean
}

internal interface SpeedometerAccess : CreateDeviceEndpoint {
    fun speed(): Float
}

internal interface StressometerAccess : CreateDeviceEndpoint {
    fun stress(): Float

    fun capacity(): Float
}

internal interface RotationControllerAccess : CreateDeviceEndpoint {
    fun targetSpeed(): Int

    fun setTargetSpeed(speed: Int): Int
}

private abstract class BoundCreateEndpoint<T : BlockEntity>(
    protected val level: ServerLevel,
    private val position: BlockPos,
    protected val entity: T,
    private val reachable: () -> Boolean,
) : CreateDeviceEndpoint {
    override val identity: Any
        get() = entity

    override fun valid(): Boolean {
        check(level.server.isSameThread) { "Create peripherals must be accessed on the server thread" }
        return reachable() && !entity.isRemoved && level.hasChunkAt(position) && level.getBlockEntity(position) === entity
    }
}

private class SpeedometerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: SpeedGaugeBlockEntity,
    reachable: () -> Boolean,
) : BoundCreateEndpoint<SpeedGaugeBlockEntity>(level, position, entity, reachable),
    SpeedometerAccess {
    override fun speed(): Float = entity.getSpeed()
}

private class StressometerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: StressGaugeBlockEntity,
    reachable: () -> Boolean,
) : BoundCreateEndpoint<StressGaugeBlockEntity>(level, position, entity, reachable),
    StressometerAccess {
    override fun stress(): Float = entity.getNetworkStress()

    override fun capacity(): Float = entity.getNetworkCapacity()
}

private class RotationControllerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: SpeedControllerBlockEntity,
    reachable: () -> Boolean,
) : BoundCreateEndpoint<SpeedControllerBlockEntity>(level, position, entity, reachable),
    RotationControllerAccess {
    override fun targetSpeed(): Int = entity.targetSpeed.getValue()

    override fun setTargetSpeed(speed: Int): Int {
        entity.targetSpeed.setValue(speed)
        return entity.targetSpeed.getValue()
    }
}

private class StockTickerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: StockTickerBlockEntity,
    reachable: () -> Boolean,
) : BoundCreateEndpoint<StockTickerBlockEntity>(level, position, entity, reachable),
    StockTickerAccess {
    override fun snapshot(maximumEntries: Int): List<StockSnapshotEntry>? {
        val stock = entity.getAccurateSummary().stacks
        if (stock.size > maximumEntries) return null
        return stock.map { entry ->
            val stack = entry.stack.copyWithCount(1)
            TickerStockEntry(entity, stack, entry.count)
        }
    }
}

private class TickerStockEntry(
    private val ticker: StockTickerBlockEntity,
    private val stack: ItemStack,
    override val count: Int,
) : StockSnapshotEntry {
    override val itemId: String = BuiltInRegistries.ITEM.getKey(stack.item).toString()
    override val displayName: String = stack.hoverName.string.take(MAXIMUM_DISPLAY_NAME_LENGTH)

    override fun request(
        quantity: Int,
        address: String,
    ): Boolean? {
        val current = LogisticsManager.getStockOf(ticker.behaviour.freqId, stack, null)
        if (current < quantity) return null
        val order = PackageOrder(listOf(BigItemStack(stack.copy(), quantity)))
        return ticker.broadcastPackageRequest(RequestType.RESTOCK, order, null, address)
    }
}

private const val MAXIMUM_HANDLES = 64
private const val MAXIMUM_STOCK_SNAPSHOTS = 4
private const val MAXIMUM_STOCK_ENTRIES = 256
private const val MAXIMUM_REQUEST_QUANTITY = 4_096
private const val MAXIMUM_ADDRESS_BYTES = 64
private const val MAXIMUM_DISPLAY_NAME_LENGTH = 128
private const val STALE_PERIPHERAL_DETAIL = "Create kinetic device was removed, replaced, disconnected, or unloaded"
private const val STALE_STOCK_TICKER_DETAIL = "Create Stock Ticker was removed, replaced, disconnected, or unloaded"

internal fun latchingValidity(check: () -> Boolean): () -> Boolean {
    var valid = true
    return {
        if (valid) valid = check()
        valid
    }
}
