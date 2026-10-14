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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.PeripheralObservation
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdePeripheralObserverTest {
    private fun target(id: String = "computer"): IdeAttachedTarget {
        val hash = Hash256.of(ByteArray(32) { 1 })
        return IdeAttachedTarget(
            IdeTargetId(id),
            IdeTargetProfileId(hash),
            TargetCompileProfile(ToolchainLockIdentity("2.4.10", "2.4", 1u, 2u, 1u, hash, hash), emptyList(), WorkerLimits()),
            IdeTargetCapabilities(false, false, false),
            "Computer",
        )
    }

    @Test
    fun `completed responses older than the observation lifetime are discarded`() {
        var time = 0L
        val future = CompletableFuture<IdeTargetReply>()
        var latest: List<PeripheralObservation>? = null
        val observer = IdePeripheralObserver({ future }, { _, entries, _ -> latest = entries }, { time })
        observer.tick(target())
        future.complete(IdeTargetReply.NamedPeripherals(emptyList()))
        time = IdePeripheralObserver.TIMEOUT_MILLIS
        observer.tick(target())
        assertNull(latest)
        observer.close()
    }

    @Test
    fun `observation refreshes without Devices and clears on detach`() {
        var time = 0L
        val requests = mutableListOf<CompletableFuture<IdeTargetReply>>()
        var latest: List<PeripheralObservation>? = null
        val observer =
            IdePeripheralObserver({ CompletableFuture<IdeTargetReply>().also(requests::add) }, { _, entries, _ ->
                latest =
                    entries
            }, { time })
        val computer = target()
        observer.tick(computer)
        assertEquals(1, requests.size)
        requests.single().complete(IdeTargetReply.NamedPeripherals(listOf(IdeNamedPeripheral("screen", "minecraft:air"))))
        observer.tick(computer)
        assertEquals("screen", latest!!.single().name)
        time = 1000
        observer.tick(computer)
        assertEquals(2, requests.size)
        observer.tick(null)
        assertNull(latest)
        assertTrue(requests.last().isCancelled)
    }

    @Test
    fun `replacement attachment rejects old result and timeout cannot retain a healthy observation`() {
        var time = 0L
        val requests = mutableListOf<CompletableFuture<IdeTargetReply>>()
        var latest: List<PeripheralObservation>? = null
        val observer =
            IdePeripheralObserver({ CompletableFuture<IdeTargetReply>().also(requests::add) }, { _, entries, _ ->
                latest =
                    entries
            }, { time })
        observer.tick(target())
        observer.tick(target("other"))
        assertTrue(requests.first().isCancelled)
        requests.last().complete(IdeTargetReply.NamedPeripherals(emptyList()))
        observer.tick(target("other"))
        assertEquals(emptyList(), latest)
        time = 1000
        observer.tick(target("other"))
        time = 6000
        observer.tick(target("other"))
        assertNull(latest)
        assertTrue(requests[2].isCancelled)
        observer.close()
        assertTrue(requests.last().isCancelled)
    }
}
