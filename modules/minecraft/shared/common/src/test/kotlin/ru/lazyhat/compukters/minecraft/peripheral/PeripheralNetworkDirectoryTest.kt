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

package ru.lazyhat.compukters.minecraft.peripheral

import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeripheralNetworkDirectoryTest {
    @Test
    fun `computer removal retains network and devices and replacement must explicitly join`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val pc = member(0, PeripheralNetworkMember.COMPUTER_PROVIDER)
        val device = member(32)
        directory.bind(network.id, pc)
        directory.bind(network.id, device)
        directory.remove(pc.instance)
        val replacement = member(0, PeripheralNetworkMember.COMPUTER_PROVIDER)
        assertNull(directory.networkOf(replacement.instance))
        assertEquals(listOf(device), directory.get(network.id)?.members)
        directory.bind(network.id, replacement)
        assertEquals(network.id, directory.networkOf(replacement.instance)?.id)
    }

    @Test
    fun `membership has no diameter limit but each computer has independent spherical reach`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val near = member(64)
        val far = member(200)
        directory.bind(network.id, near)
        directory.bind(network.id, far)
        assertTrue(peripheralInRange(DIMENSION, BlockPos.ZERO, near.identity, 64))
        assertFalse(peripheralInRange(DIMENSION, BlockPos.ZERO, far.identity, 64))
        assertTrue(peripheralInRange(DIMENSION, BlockPos(200, 0, 0), far.identity, 64))
        assertFalse(peripheralInRange("minecraft:the_nether", BlockPos.ZERO, near.identity, 64))
        assertFalse(peripheralInRange(DIMENSION, BlockPos(0, 1, 0), near.identity, 64))
    }

    @Test
    fun `membership and instance identities survive codec reload`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val device = member(2)
        directory.bind(network.id, device)
        val encoded = PeripheralNetworkCodecs.directory.encodeStart(JsonOps.INSTANCE, directory).getOrThrow()
        val restored = PeripheralNetworkCodecs.directory.parse(JsonOps.INSTANCE, encoded).getOrThrow()
        assertEquals(directory.snapshot(), restored.snapshot())
        assertEquals(network.id, restored.networkOf(device.instance)?.id)
        assertNull(restored.networkOf(member(2).instance))
    }

    @Test
    fun `device cannot join multiple networks and rejected binding preserves state`() {
        val directory = PeripheralNetworkDirectory()
        val first = directory.create("first")
        val second = directory.create("second")
        val device = member(0)
        directory.bind(first.id, device)
        val before = directory.snapshot()
        assertFailsWith<IllegalArgumentException> { directory.bind(second.id, device) }
        assertEquals(before, directory.snapshot())
    }

    @Test
    fun `member limit applies to far and unavailable members without partial mutation`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        repeat(PeripheralNetworkDirectory.MAXIMUM_MEMBERS) { directory.bind(network.id, member(it * 100)) }
        assertFailsWith<IllegalArgumentException> { directory.bind(network.id, member(-1)) }
        assertEquals(PeripheralNetworkDirectory.MAXIMUM_MEMBERS, directory.get(network.id)?.members?.size)
    }

    @Test
    fun `malformed duplicate membership is rejected on reload`() {
        val device = member(0)
        assertFailsWith<IllegalArgumentException> {
            PeripheralNetworkDirectory(
                listOf(
                    PeripheralNetwork(UUID.randomUUID(), "first", listOf(device)),
                    PeripheralNetwork(UUID.randomUUID(), "second", listOf(device)),
                ),
            )
        }
    }

    @Test
    fun `relocation retains exact instance membership and its name`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val device = member(2)
        directory.bind(network.id, device)
        directory.setName(device.instance, device.identity, "pump")
        assertTrue(directory.relocate(device.instance, DIMENSION, BlockPos(20, 3, 4)))
        val restored = directory.networkOf(device.instance)!!.members.single()
        assertEquals("pump", restored.name)
        assertEquals(BlockPos(20, 3, 4), restored.identity.anchor)
        assertFalse(directory.relocate(device.instance, DIMENSION, restored.identity.anchor))
    }

    @Test
    fun `network and names survive the actual target saved data round trip`() {
        val storage = PeripheralNetworkStorage()
        val network = storage.directory.create("factory")
        val device = member(2)
        storage.directory.bind(network.id, device)
        storage.directory.setName(device.instance, device.identity, "pump")
        storage.setDirty()
        val restored = PeripheralNetworkStorageTestPersistence.roundTrip(storage)
        assertEquals(storage.directory.snapshot(), restored.directory.snapshot())
        assertEquals(network.id, restored.directory.networkOf(device.instance)?.id)
    }

    @Test
    fun `unknown persisted schema version is rejected`() {
        val encoded =
            PeripheralNetworkCodecs.directory
                .encodeStart(
                    JsonOps.INSTANCE,
                    PeripheralNetworkDirectory(),
                ).getOrThrow()
                .asJsonObject
        encoded.addProperty("version", 2)
        assertFailsWith<IllegalArgumentException> { PeripheralNetworkCodecs.directory.parse(JsonOps.INSTANCE, encoded).getOrThrow() }
    }

    @Test
    fun `screen inherits the only bound panel and collapses duplicate bindings`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val first = member(2)
        val second = member(3)
        val screen = member(2).copy(name = "panel")
        directory.bind(network.id, first)
        directory.consolidate(setOf(first.instance, second.instance), screen)
        assertEquals(listOf(screen), directory.get(network.id)!!.members)
        assertNull(directory.networkOf(first.instance))
        directory.bind(network.id, second)
        directory.consolidate(setOf(second.instance), screen)
        assertEquals(listOf(screen), directory.get(network.id)!!.members)
        val encoded = PeripheralNetworkCodecs.directory.encodeStart(JsonOps.INSTANCE, directory).getOrThrow()
        val restored = PeripheralNetworkCodecs.directory.parse(JsonOps.INSTANCE, encoded).getOrThrow()
        assertEquals(network.id, restored.networkOf(screen.instance)?.id)
    }

    @Test
    fun `assembling panels from different networks clears all source and target bindings`() {
        val directory = PeripheralNetworkDirectory()
        val firstNetwork = directory.create("first")
        val secondNetwork = directory.create("second")
        val first = member(2)
        val second = member(3)
        val unrelated = member(10)
        directory.bind(firstNetwork.id, first)
        directory.bind(firstNetwork.id, unrelated)
        directory.bind(secondNetwork.id, second)
        directory.consolidate(setOf(second.instance), first)
        assertNull(directory.networkOf(first.instance))
        assertNull(directory.networkOf(second.instance))
        assertEquals(listOf(unrelated), directory.get(firstNetwork.id)!!.members)
        assertTrue(directory.get(secondNetwork.id)!!.members.isEmpty())
    }

    @Test
    fun `consolidation at the member limit frees source slots before binding the screen`() {
        val directory = PeripheralNetworkDirectory()
        val network = directory.create("factory")
        val members = List(PeripheralNetworkDirectory.MAXIMUM_MEMBERS) { member(it) }
        members.forEach { directory.bind(network.id, it) }
        val screen = member(0)
        directory.consolidate(members.take(2).map { it.instance }.toSet(), screen)
        assertEquals(PeripheralNetworkDirectory.MAXIMUM_MEMBERS - 1, directory.get(network.id)!!.members.size)
        assertEquals(network.id, directory.networkOf(screen.instance)?.id)
    }

    private fun member(
        x: Int,
        provider: String = "display",
    ) = PeripheralNetworkMember(
        UUID.randomUUID(),
        PeripheralDeviceIdentity(provider, DIMENSION, BlockPos(x, 0, 0), "device"),
    )

    companion object {
        private const val DIMENSION = "minecraft:overworld"
    }
}
