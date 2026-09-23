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

import create.CreateAddonContract
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StockTickerHostStateTest {
    @Test
    fun `snapshot preserves variants and requests the selected one`() {
        val plain = FakeItem("minecraft:book", "Book", 12)
        val named = FakeItem("minecraft:book", "Named Book", 3)
        val ticker = FakeTicker(mutableListOf(plain, named))
        val host = host(ticker)

        val device = host.call(13, VmValue.I32(0)).intValue()
        val snapshot = host.call(16, VmValue.I32(device)).intValue()
        assertEquals(HostResponse.IntSuccess(2), host.call(20, VmValue.I32(snapshot)))
        assertEquals(HostResponse.StringSuccess("minecraft:book"), host.call(19, VmValue.I32(snapshot), VmValue.I32(1)))
        assertEquals(HostResponse.StringSuccess("Named Book"), host.call(18, VmValue.I32(snapshot), VmValue.I32(1)))
        assertEquals(HostResponse.IntSuccess(3), host.call(17, VmValue.I32(snapshot), VmValue.I32(1)))

        ticker.items.clear()
        assertEquals(HostResponse.IntSuccess(2), host.call(20, VmValue.I32(snapshot)))
        assertEquals(
            HostResponse.BoolSuccess(true),
            host.call(15, VmValue.I32(snapshot), VmValue.I32(1), VmValue.I32(2), VmValue.StringValue("workshop")),
        )
        assertEquals(listOf(2 to "workshop"), named.requests)
        assertTrue(plain.requests.isEmpty())
        named.acceptRequests = false
        assertEquals(
            HostResponse.BoolSuccess(false),
            host.call(15, VmValue.I32(snapshot), VmValue.I32(1), VmValue.I32(1), VmValue.StringValue("workshop")),
        )
        named.currentCount = 0
        assertEquals(
            HostFailureKind.UNAVAILABLE,
            assertIs<HostResponse.Failure>(
                host.call(15, VmValue.I32(snapshot), VmValue.I32(1), VmValue.I32(1), VmValue.StringValue("workshop")),
            ).kind,
        )
    }

    @Test
    fun `snapshot and request limits reject invalid work without sending an order`() {
        val item = FakeItem("minecraft:iron_ingot", "Iron Ingot", 10)
        val ticker = FakeTicker(mutableListOf(item))
        val host = host(ticker)
        val device = host.call(13, VmValue.I32(0)).intValue()
        val snapshot = host.call(16, VmValue.I32(device)).intValue()

        listOf(-1, 1).forEach { index ->
            assertEquals(
                HostFailureKind.OTHER,
                assertIs<HostResponse.Failure>(host.call(17, VmValue.I32(snapshot), VmValue.I32(index))).kind,
            )
        }
        listOf(0, 4_097).forEach { quantity ->
            assertEquals(
                HostFailureKind.OTHER,
                assertIs<HostResponse.Failure>(
                    host.call(15, VmValue.I32(snapshot), VmValue.I32(0), VmValue.I32(quantity), VmValue.StringValue("workshop")),
                ).kind,
            )
        }
        listOf("", " \t ", "a\n", "x".repeat(65)).forEach { address ->
            assertEquals(
                HostFailureKind.OTHER,
                assertIs<HostResponse.Failure>(
                    host.call(15, VmValue.I32(snapshot), VmValue.I32(0), VmValue.I32(1), VmValue.StringValue(address)),
                ).kind,
            )
        }
        assertTrue(item.requests.isEmpty())
        repeat(3) { host.call(16, VmValue.I32(device)).intValue() }
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(host.call(16, VmValue.I32(device))).kind)
        assertEquals(HostResponse.UnitSuccess, host.call(21, VmValue.I32(snapshot)))
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(host.call(20, VmValue.I32(snapshot))).kind)
        assertEquals(HostResponse.IntSuccess(1), host.call(20, VmValue.I32(host.call(16, VmValue.I32(device)).intValue())))

        val oversized = host(FakeTicker(MutableList(257) { FakeItem("minecraft:stone", "Stone", 1) }))
        val oversizedDevice = oversized.call(13, VmValue.I32(0)).intValue()
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(oversized.call(16, VmValue.I32(oversizedDevice))).kind)
    }

    @Test
    fun `named snapshot stays stale after disconnection and reconnection`() {
        var connected = true
        val first = FakeTicker(mutableListOf(FakeItem("minecraft:iron_ingot", "Iron Ingot", 10))) { connected }
        var current = first
        val state =
            CreateHostState(
                resolveSide = { _, _ -> null },
                resolveName = { name, kind ->
                    if (name == "stock" && kind == PeripheralKind.STOCK_TICKER) {
                        CreateNamedResolution.Found(current)
                    } else {
                        CreateNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "missing")
                    }
                },
            )
        val host = CreateAddonContract.host(state)
        val oldDevice = host.call(14, VmValue.StringValue("stock")).intValue()
        val oldSnapshot = host.call(16, VmValue.I32(oldDevice)).intValue()

        connected = false
        assertEquals(HostFailureKind.INPUT_OUTPUT, assertIs<HostResponse.Failure>(host.call(20, VmValue.I32(oldSnapshot))).kind)
        connected = true
        current = FakeTicker(mutableListOf(FakeItem("minecraft:gold_ingot", "Gold Ingot", 4)))
        val newDevice = host.call(14, VmValue.StringValue("stock")).intValue()
        assertTrue(newDevice != oldDevice)
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(host.call(20, VmValue.I32(oldSnapshot))).kind)
        assertEquals(HostResponse.IntSuccess(1), host.call(20, VmValue.I32(host.call(16, VmValue.I32(newDevice)).intValue())))
    }

    private fun host(ticker: FakeTicker): ProgramAddonHost =
        CreateAddonContract.host(
            CreateHostState { side, kind -> ticker.takeIf { side == 0 && kind == PeripheralKind.STOCK_TICKER } },
        )

    private fun ProgramAddonHost.call(
        operation: Int,
        vararg values: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(ProgramAddonRequest(VmHostRequestIdentity(1, nextRequestId++), CREATE, operation, values.toList())),
        ).response

    private fun HostResponse.intValue(): Int = assertIs<HostResponse.IntSuccess>(this).value

    private class FakeTicker(
        val items: MutableList<FakeItem>,
        private val reachable: () -> Boolean = { true },
    ) : StockTickerAccess {
        override val identity: Any = this

        override fun valid(): Boolean = reachable()

        override fun snapshot(maximumEntries: Int): List<StockSnapshotEntry>? = items.takeIf { it.size <= maximumEntries }?.toList()
    }

    private class FakeItem(
        override val itemId: String,
        override val displayName: String,
        override val count: Int,
    ) : StockSnapshotEntry {
        var currentCount = count
        var acceptRequests = true
        val requests = mutableListOf<Pair<Int, String>>()

        override fun request(
            quantity: Int,
            address: String,
        ): Boolean? {
            if (currentCount < quantity) return null
            requests += quantity to address
            return acceptRequests
        }
    }

    private companion object {
        val CREATE = CapabilityIdentity("create", "create", 1, 0)
        var nextRequestId = 10_000L
    }
}
