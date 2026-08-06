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
    fun firstAndLastObserverCallbacksFollowTheSharedComputerEntryLifetime() {
        val firstObservers = mutableListOf<Pair<UInt, RetainedDisplayObserverKey>>()
        val lastObservers = mutableListOf<UInt>()
        val registry =
            RetainedDisplayClientRegistry(
                nativeCacheFactory = { RecordingNativeCache() },
                onFirstObserver = { computerId, observer -> firstObservers += computerId to observer },
                onLastObserver = lastObservers::add,
            )
        val menuKey = RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)
        val worldKey = RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD)

        val menu = registry.attach(42u, menuKey)
        val world = registry.attach(42u, worldKey)
        menu.close()

        assertEquals(listOf(42u to menuKey), firstObservers)
        assertEquals(emptyList(), lastObservers)

        world.close()
        world.close()

        assertEquals(listOf(42u), lastObservers)
    }

    @Test
    fun failedFirstObserverCallbackRollsBackAndKeepsCleanupFailuresSuppressed() {
        val cache = RecordingNativeCache(failRelease = true, failClose = true)
        val registry =
            RetainedDisplayClientRegistry(
                nativeCacheFactory = { cache },
                onFirstObserver = { _, _ -> error("synthetic attach callback failure") },
                onLastObserver = {},
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
            }

        assertEquals("synthetic attach callback failure", failure.message)
        assertEquals(
            listOf("synthetic view release failure", "synthetic native close failure"),
            failure.suppressed.map { it.message },
        )
        assertNull(registry.entry(42u))
        assertEquals(1, cache.closes)
    }

    @Test
    fun menuAndWorldObserversShareOneEntryAndCloseItAfterLastDetach() {
        val caches = mutableListOf<RecordingNativeCache>()
        val registry = registry { RecordingNativeCache().also(caches::add) }

        val menu = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
        val world = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD))

        assertSame(menu.entry, world.entry)
        assertEquals(1, caches.size)
        assertEquals(2, menu.entry.observerCount)
        assertEquals(
            listOf<Pair<RetainedDisplayViewKind, ULong?>>(
                RetainedDisplayViewKind.MENU to null,
                RetainedDisplayViewKind.WORLD to null,
            ),
            caches.single().retainedViews,
        )

        menu.close()
        menu.close()
        assertEquals(1, world.entry.observerCount)
        assertSame(world.entry, registry.entry(42u))
        assertEquals(listOf(RetainedDisplayViewKind.MENU), caches.single().releasedViews)

        world.close()
        assertNull(registry.entry(42u))
        assertEquals(
            listOf(RetainedDisplayViewKind.MENU, RetainedDisplayViewKind.WORLD),
            caches.single().releasedViews,
        )
        assertEquals(1, caches.single().closes)
    }

    @Test
    fun lateViewAttachReceivesTheAlreadyInstalledReplicaState() {
        val cache = RecordingNativeCache()
        val registry = registry { cache }
        val menu = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
        assertIs<RetainedDisplayClientInstallResult.Installed>(menu.entry.apply(emptySnapshot()))

        val world = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD))

        assertEquals(RetainedDisplayViewKind.WORLD to 1uL, cache.retainedViews.last())
        world.close()
        menu.close()
    }

    @Test
    fun observerHandleExposesOnlyItsViewFlavorPresentation() {
        val cache = RecordingNativeCache()
        val registry = registry { cache }
        val menu = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))

        assertSame(cache.presentation, menu.presentation())
        assertEquals(listOf(RetainedDisplayViewKind.MENU), cache.presentationRequests)
        menu.close()
    }

    @Test
    fun duplicateObserverKeyIsRejectedInsteadOfInflatingAReferenceCount() {
        val registry = registry { RecordingNativeCache() }
        val key = RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)
        val observer = registry.attach(42u, key)

        assertFailsWith<IllegalArgumentException> { registry.attach(42u, key) }

        observer.close()
    }

    @Test
    fun successfulNativeInstallMakesAcknowledgementVisible() {
        val cache = RecordingNativeCache()
        val registry = registry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)).entry

        val result = assertIs<RetainedDisplayClientInstallResult.Installed>(entry.apply(emptySnapshot()))

        assertEquals(listOf(1uL), cache.installedSequences)
        assertEquals(32, result.acknowledgement.size)
        assertEquals(1uL, entry.state?.sequence)
    }

    @Test
    fun nativeInstallFailureClearsReplicaAndRequestsAtomicInstallResync() {
        val cache = RecordingNativeCache(failInstall = true)
        val registry = registry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)).entry

        val result = assertIs<RetainedDisplayClientInstallResult.ResyncRequired>(entry.apply(emptySnapshot()))

        assertEquals(RetainedDisplayResyncReason.ATOMIC_INSTALL_FAILED, result.reason)
        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun nativeInstallFailureClearsReplicaEvenWhenNativeInvalidationAlsoFails() {
        val cache = RecordingNativeCache(failInstall = true, failInvalidate = true)
        val registry = registry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU)).entry

        assertFailsWith<IllegalStateException> { entry.apply(emptySnapshot()) }

        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun renderResourceLossInvalidatesNativeStateAndRequestsIndependentResync() {
        val cache = RecordingNativeCache()
        val registry = registry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD)).entry
        entry.apply(emptySnapshot())

        val result = assertIs<RetainedDisplayClientInstallResult.ResyncRequired>(entry.invalidateRenderResources())

        assertEquals(RetainedDisplayResyncReason.RENDER_RESOURCE_LOST, result.reason)
        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun renderResourceLossClearsReplicaBeforeFailingNativeInvalidation() {
        val cache = RecordingNativeCache(failInvalidate = true)
        val registry = registry { cache }
        val entry = registry.attach(42u, RetainedDisplayObserverKey("block", RetainedDisplayViewKind.WORLD)).entry
        entry.apply(emptySnapshot())

        assertFailsWith<IllegalStateException> { entry.invalidateRenderResources() }

        assertEquals(1, cache.invalidations)
        assertNull(entry.state)
    }

    @Test
    fun disconnectClosesEveryComputerEntry() {
        val caches = mutableListOf<RecordingNativeCache>()
        val registry = registry { RecordingNativeCache().also(caches::add) }
        registry.attach(42u, RetainedDisplayObserverKey("first", RetainedDisplayViewKind.MENU))
        registry.attach(43u, RetainedDisplayObserverKey("second", RetainedDisplayViewKind.WORLD))

        registry.close()

        assertNull(registry.entry(42u))
        assertNull(registry.entry(43u))
        assertEquals(listOf(1, 1), caches.map { it.closes })
    }

    @Test
    fun connectionDiscardClosesNativeEntriesWithoutSendingLastObserverCallbacks() {
        val lastObservers = mutableListOf<UInt>()
        val cache = RecordingNativeCache()
        val registry =
            RetainedDisplayClientRegistry(
                nativeCacheFactory = { cache },
                onFirstObserver = { _, _ -> },
                onLastObserver = lastObservers::add,
            )
        registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))

        registry.discardConnection()

        assertNull(registry.entry(42u))
        assertEquals(1, cache.closes)
        assertEquals(emptyList(), lastObservers)
    }

    @Test
    fun finalDetachRemovesEntryAndAttemptsCompositeCloseWhenViewReleaseFails() {
        val cache = RecordingNativeCache(failRelease = true, failClose = true)
        val registry = registry { cache }
        val observer = registry.attach(42u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))

        val failure = assertFailsWith<IllegalStateException> { observer.close() }

        assertEquals("synthetic view release failure", failure.message)
        assertEquals(listOf("synthetic native close failure"), failure.suppressed.map { it.message })
        assertNull(registry.entry(42u))
        assertEquals(0, observer.entry.observerCount)
        assertEquals(1, cache.closes)
    }

    @Test
    fun registryCloseAttemptsEveryEntryAfterAnEarlierCloseFailure() {
        val caches = mutableListOf<RecordingNativeCache>()
        val registry =
            registry { computerId ->
                RecordingNativeCache(failClose = computerId == 42u).also(caches::add)
            }
        registry.attach(42u, RetainedDisplayObserverKey("first", RetainedDisplayViewKind.MENU))
        registry.attach(43u, RetainedDisplayObserverKey("second", RetainedDisplayViewKind.WORLD))

        assertFailsWith<IllegalStateException> { registry.close() }

        assertNull(registry.entry(42u))
        assertNull(registry.entry(43u))
        assertEquals(listOf(1, 1), caches.map { it.closes })
    }

    @Test
    fun zeroComputerIdIsRejected() {
        val registry = registry { RecordingNativeCache() }

        assertFailsWith<IllegalArgumentException> {
            registry.attach(0u, RetainedDisplayObserverKey("menu", RetainedDisplayViewKind.MENU))
        }
    }

    private fun registry(factory: (UInt) -> RetainedDisplayNativeCache): RetainedDisplayClientRegistry =
        RetainedDisplayClientRegistry(factory, { _, _ -> }, {})

    private class RecordingNativeCache(
        private val failInstall: Boolean = false,
        private val failInvalidate: Boolean = false,
        private val failRelease: Boolean = false,
        private val failClose: Boolean = false,
    ) : RetainedDisplayNativeCache {
        val presentation = MinecraftRetainedNativePresentation(emptyList(), RetainedDisplayRenderMetrics())
        val installedSequences = mutableListOf<ULong>()
        val retainedViews = mutableListOf<Pair<RetainedDisplayViewKind, ULong?>>()
        val releasedViews = mutableListOf<RetainedDisplayViewKind>()
        val presentationRequests = mutableListOf<RetainedDisplayViewKind>()
        var invalidations = 0
        var closes = 0

        override fun presentation(viewKind: RetainedDisplayViewKind): MinecraftRetainedNativePresentation? {
            presentationRequests += viewKind
            return presentation
        }

        override fun retainView(
            viewKind: RetainedDisplayViewKind,
            state: RetainedDisplayState?,
        ) {
            retainedViews += viewKind to state?.sequence
        }

        override fun releaseView(viewKind: RetainedDisplayViewKind) {
            releasedViews += viewKind
            if (failRelease) error("synthetic view release failure")
        }

        override fun install(
            state: RetainedDisplayState,
            damage: RetainedDisplayInstallDamage,
        ) {
            if (failInstall) error("synthetic native install failure")
            installedSequences += state.sequence
        }

        override fun invalidate() {
            invalidations += 1
            if (failInvalidate) error("synthetic native invalidation failure")
        }

        override fun close() {
            closes += 1
            if (failClose) error("synthetic native close failure")
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
