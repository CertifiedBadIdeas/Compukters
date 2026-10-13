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

import net.minecraft.core.BlockPos
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralContract
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralEndpoint
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralIdentity
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralOwnership
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralSession
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PeripheralCheckpointLocationTest {
    @Test
    fun `member checkpoint follows an exact device across dimensions and translated plots`() {
        val stamp = UUID.randomUUID()
        val network = UUID.randomUUID()
        val before = BlockPos(1_000_000, 64, 1_000_000)
        val after = BlockPos(2_000_000, 90, 3_000_000)
        val directory =
            PeripheralNetworkDirectory(
                listOf(
                    PeripheralNetwork(
                        network,
                        "ship",
                        listOf(
                            PeripheralNetworkMember(stamp, PeripheralDeviceIdentity("engine", "surface", before, "throttle")),
                            PeripheralNetworkMember(stamp, PeripheralDeviceIdentity("engine", "surface", before, "vector")),
                        ),
                    ),
                ),
            )
        val saved = PeripheralCheckpointLocation(before, stamp).encode(before.above())
        val restored = PeripheralCheckpointLocation.decode(saved, after.above())
        assertNull(restored.anchor)
        assertEquals(before, restored.resolve("surface", directory))
        directory.relocate(stamp, "space", after)
        assertEquals(after, restored.resolve("space", directory))
        assertNull(restored.resolve("surface", directory))
        directory.remove(stamp)
        // A replacement at either address cannot inherit this UUID-backed checkpoint.
        directory.bind(network, PeripheralNetworkMember(UUID.randomUUID(), PeripheralDeviceIdentity("engine", "space", after, "vector")))
        assertNull(restored.resolve("space", directory))
    }

    @Test
    fun `same retained token restores moved instance and rejects replacement and competing owner`() {
        val stamp = UUID.randomUUID()
        val network = UUID.randomUUID()
        val from = BlockPos(1_000_000, 64, 1_000_000)
        val to = BlockPos(2_000_000, 64, 2_000_000)
        val directory =
            PeripheralNetworkDirectory(
                listOf(
                    PeripheralNetwork(
                        network,
                        "ship",
                        listOf(
                            PeripheralNetworkMember(stamp, PeripheralDeviceIdentity("engine", "surface", from, "vector")),
                        ),
                    ),
                ),
            )
        var position = from
        var dimension = "surface"
        var instance = stamp
        val device = Any()
        val ownership = PeripheralOwnership()
        val contract =
            PeripheralContract<PeripheralCheckpointLocation, Any>("test:engine", "engine", "vector") { identity ->
                if (identity.location.resolve(dimension, directory) != position) {
                    null
                } else {
                    PeripheralEndpoint(device, device, instance.toString()) { true }
                }
            }

        fun session(computer: BlockPos) =
            PeripheralSession(
                listOf(contract),
                discover = { emptyList() },
                side = { emptyList() },
                lookupName = { _, _ -> PeripheralIdentity("engine", PeripheralCheckpointLocation(position, stamp), "vector") },
                encodeLocation = { it.encode(computer) },
                decodeLocation = { PeripheralCheckpointLocation.decode(it, computer) },
                ownership = ownership,
            )
        val original = session(from.above())
        val handle = original.named(contract.id, "engine")
        val checkpoint = original.checkpoint()
        original.reset()
        position = to
        dimension = "space"
        directory.relocate(stamp, dimension, to)
        val restored = session(to.above())
        restored.restoreCheckpoint(checkpoint)
        assertSame(device, restored.endpoint(contract, handle))
        val competing = session(to.above())
        assertFailsWith<PeripheralFailure> { competing.restoreCheckpoint(checkpoint) }
        restored.reset()
        instance = UUID.randomUUID()
        val replaced = session(to.above())
        replaced.restoreCheckpoint(checkpoint)
        assertFailsWith<PeripheralFailure> { replaced.endpoint(contract, handle) }
    }

    @Test
    fun `direct device offset survives translation without packed coordinate truncation`() {
        val computer = BlockPos(1_000_000, 64, -2_000_000)
        val moved = BlockPos(-3_000_000, 90, 4_000_000)
        val offset = BlockPos(10, -4, 5)
        val bytes = PeripheralCheckpointLocation(computer.offset(offset)).encode(computer)
        assertEquals(moved.offset(offset), PeripheralCheckpointLocation.decode(bytes, moved).anchor)
    }

    @Test
    fun `legacy absolute checkpoint is explicit and malformed tagged locations are rejected`() {
        val old = BlockPos(10, 20, 30)
        assertEquals(old, PeripheralCheckpointLocation.decode(ByteBuffer.allocate(8).putLong(old.asLong()).array(), BlockPos.ZERO).anchor)
        for (bytes in listOf(
            byteArrayOf(),
            byteArrayOf(
                1,
            ),
            byteArrayOf(2, 0),
            byteArrayOf(1, 2),
            ByteArray(14),
            byteArrayOf(1, 1),
            ByteArray(19) {
                1
            },
        )) {
            assertFailsWith<IllegalArgumentException> { PeripheralCheckpointLocation.decode(bytes, BlockPos.ZERO) }
        }
        val overflow =
            ByteBuffer
                .allocate(14)
                .put(1)
                .put(0)
                .putInt(Int.MAX_VALUE)
                .putInt(0)
                .putInt(0)
                .array()
        assertFailsWith<ArithmeticException> { PeripheralCheckpointLocation.decode(overflow, BlockPos(1, 0, 0)) }
    }
}
