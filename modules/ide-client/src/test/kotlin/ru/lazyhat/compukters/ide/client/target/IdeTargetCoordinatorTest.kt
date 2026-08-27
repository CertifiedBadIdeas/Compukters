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

package ru.lazyhat.compukters.ide.client.target

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdeTargetCoordinatorTest {
    @Test
    fun `attach completion is applied only to its current generation`() {
        val fixture = fixture()
        val first = fixture.port.nextAttach()

        fixture.coordinator.attach(claim())
        assertIs<IdeTargetState.Attaching>(fixture.coordinator.state())
        fixture.coordinator.detach()
        first.complete(IdeAttachResult.Attached(target()))
        fixture.coordinator.tick()
        assertEquals(IdeTargetState.LocalOnly, fixture.coordinator.state())
        assertEquals(listOf(target()), fixture.port.detached)

        val second = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        second.complete(IdeAttachResult.Attached(target()))
        fixture.coordinator.tick()
        assertEquals(IdeTargetState.Attached(target()), fixture.coordinator.state())
    }

    @Test
    fun `rejected attach and heartbeat loss are bounded target states`() {
        val fixture = fixture()
        val rejected = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        rejected.complete(IdeAttachResult.Rejected(failure(IdeTargetFailureKind.Permission)))
        fixture.coordinator.tick()
        assertIs<IdeTargetState.Failed>(fixture.coordinator.state())

        val attached = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        attached.complete(IdeAttachResult.Attached(target()))
        fixture.coordinator.tick()
        fixture.clock.now = 5_000
        fixture.coordinator.tick()
        assertEquals(1, fixture.port.heartbeats.size)
        fixture.port.heartbeats.single().complete(
            IdeHeartbeatResult.Lost(failure(IdeTargetFailureKind.TargetLost)),
        )
        fixture.coordinator.tick()
        assertEquals(
            IdeTargetState.Detached(failure(IdeTargetFailureKind.TargetLost)),
            fixture.coordinator.state(),
        )
    }

    @Test
    fun `detach and close release only the currently attached target`() {
        val fixture = fixture()
        val attached = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        attached.complete(IdeAttachResult.Attached(target()))
        fixture.coordinator.tick()

        fixture.coordinator.detach()
        fixture.coordinator.detach()
        assertEquals(listOf(target()), fixture.port.detached)
        assertEquals(IdeTargetState.LocalOnly, fixture.coordinator.state())

        val reopened = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        reopened.complete(IdeAttachResult.Attached(target()))
        fixture.coordinator.tick()
        fixture.coordinator.close()
        fixture.coordinator.close()
        assertEquals(listOf(target(), target()), fixture.port.detached)
    }

    private fun fixture(): Fixture {
        val port = ControlledTargetPort()
        val clock = TargetClock()
        return Fixture(port, clock, IdeTargetCoordinator(port, clock))
    }

    private data class Fixture(
        val port: ControlledTargetPort,
        val clock: TargetClock,
        val coordinator: IdeTargetCoordinator,
    )
}

private class TargetClock(
    var now: Long = 0,
) : IdeControllerClock {
    override fun nowMillis(): Long = now
}

private class ControlledTargetPort : IdeTargetPort {
    private val attachFutures = ArrayDeque<CompletableFuture<IdeAttachResult>>()
    val heartbeats = mutableListOf<CompletableFuture<IdeHeartbeatResult>>()
    val detached = mutableListOf<IdeAttachedTarget>()

    fun nextAttach(): CompletableFuture<IdeAttachResult> =
        CompletableFuture<IdeAttachResult>().also(attachFutures::addLast)

    override fun attach(claim: IdeTargetClaim): CompletableFuture<IdeAttachResult> = attachFutures.removeFirst()

    override fun heartbeat(target: IdeAttachedTarget): CompletableFuture<IdeHeartbeatResult> =
        CompletableFuture<IdeHeartbeatResult>().also(heartbeats::add)

    override fun detach(target: IdeAttachedTarget): CompletableFuture<Unit> {
        detached += target
        return CompletableFuture.completedFuture(Unit)
    }

    override fun verify(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ): CompletableFuture<IdeVerifyResult> = error("not used")

    override fun executableRevision(
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
    ): CompletableFuture<IdeRevisionResult> = error("not used")

    override fun deploy(
        target: IdeAttachedTarget,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
    ): CompletableFuture<IdeDeployResult> = error("not used")

    override fun submitCanonicalLine(
        target: IdeAttachedTarget,
        line: CharArray,
    ): CompletableFuture<IdeSubmissionResult> = error("not used")
}

private fun claim() = IdeTargetClaim.of(byteArrayOf(1))

private fun target() =
    IdeAttachedTarget(
        IdeTargetId("computer-1"),
        IdeTargetProfileId(Hash256.of(ByteArray(32) { 1 })),
        IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true),
        "Computer",
    )

private fun failure(kind: IdeTargetFailureKind) = IdeTargetFailure(kind, kind.name)
