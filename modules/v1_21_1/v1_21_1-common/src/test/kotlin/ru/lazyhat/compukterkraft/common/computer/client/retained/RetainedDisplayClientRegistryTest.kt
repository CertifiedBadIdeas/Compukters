/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.common.computer.client.retained

import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayInstallDamage
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayResyncReason
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class RetainedDisplayClientRegistryTest {
    @Test
    fun menuAndWorldObserversShareOneEntryAndCloseItAfterLastDetach() {
        val caches = mutableListOf<RecordingNativeCache>()
        val registry = RetainedDisplayClientRegistry { RecordingNativeCache().also(caches::add) }

        val menu = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
        val world = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD))

        assertSame(menu.entry, world.entry)
        assertEquals(1, caches.size)
        assertEquals(2, menu.entry.observerCount)

        menu.close()
        menu.close()
        assertEquals(1, world.entry.observerCount)
        assertSame(world.entry, registry.entry(42u))

        world.close()
        assertNull(registry.entry(42u))
        assertEquals(1, caches.single().closes)
    }

    @Test
    fun duplicateObserverKeyIsRejectedInsteadOfInflatingAReferenceCount() {
        val registry = RetainedDisplayClientRegistry { RecordingNativeCache() }
        val key = RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)
        val observer = registry.attach(42u, key)

        assertFailsWith<IllegalArgumentException> { registry.attach(42u, key) }

        observer.close()
    }

    @Test
    fun successfulNativeInstallMakesAcknowledgementVisible() {
        val cache = RecordingNativeCache()
        val registry = RetainedDisplayClientRegistry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)).entry

        val result = assertIs<RetainedDisplayClientInstallResult.Installed>(entry.apply(emptySnapshot()))

        assertEquals(listOf(1uL), cache.installedSequences)
        assertEquals(32, result.acknowledgement.size)
        assertEquals(1uL, entry.state?.sequence)
    }

    @Test
    fun nativeInstallFailureClearsReplicaAndRequestsAtomicInstallResync() {
        val cache = RecordingNativeCache(failInstall = true)
        val registry = RetainedDisplayClientRegistry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)).entry

        val result = assertIs<RetainedDisplayClientInstallResult.ResyncRequired>(entry.apply(emptySnapshot()))

        assertEquals(RetainedDisplayResyncReason.ATOMIC_INSTALL_FAILED, result.reason)
        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun renderResourceLossInvalidatesNativeStateAndRequestsIndependentResync() {
        val cache = RecordingNativeCache()
        val registry = RetainedDisplayClientRegistry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD)).entry
        entry.apply(emptySnapshot())

        val result = assertIs<RetainedDisplayClientInstallResult.ResyncRequired>(entry.invalidateRenderResources())

        assertEquals(RetainedDisplayResyncReason.RENDER_RESOURCE_LOST, result.reason)
        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun disconnectClosesEveryComputerEntry() {
        val caches = mutableListOf<RecordingNativeCache>()
        val registry = RetainedDisplayClientRegistry { RecordingNativeCache().also(caches::add) }
        registry.attach(42u, RetainedDisplayObserverKey("first", RetainedDisplayViewKind.MENU))
        registry.attach(43u, RetainedDisplayObserverKey("second", RetainedDisplayViewKind.WORLD))

        registry.close()

        assertNull(registry.entry(42u))
        assertNull(registry.entry(43u))
        assertEquals(listOf(1, 1), caches.map { it.closes })
    }

    @Test
    fun zeroComputerIdIsRejected() {
        val registry = RetainedDisplayClientRegistry { RecordingNativeCache() }

        assertFailsWith<IllegalArgumentException> {
            registry.attach(0u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
        }
    }

    private class RecordingNativeCache(
        private val failInstall: Boolean = false,
    ) : RetainedDisplayNativeCache {
        val installedSequences = mutableListOf<ULong>()
        var invalidations = 0
        var closes = 0

        override fun install(
            state: RetainedDisplayState,
            damage: RetainedDisplayInstallDamage,
        ) {
            if (failInstall) error("synthetic native install failure")
            installedSequences += state.sequence
        }

        override fun invalidate() {
            invalidations += 1
        }

        override fun close() {
            closes += 1
        }
    }

    private fun emptySnapshot(): ByteArray =
        ByteBuffer
            .allocate(48)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x5053_444b)
            .putShort(1)
            .putShort(1)
            .putInt(48)
            .putInt(42)
            .putLong(7)
            .putLong(1)
            .putInt(0)
            .putInt(8)
            .putShort(0)
            .putShort(0)
            .putInt(0)
            .array()
}
