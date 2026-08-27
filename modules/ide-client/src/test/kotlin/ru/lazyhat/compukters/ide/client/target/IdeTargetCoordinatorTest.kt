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
import kotlin.test.assertSame

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

    @Test
    fun `verify is non mutating and its matching ticket is reused by deploy`() {
        val fixture = attachedFixture()
        val artifact = artifact()
        val path = IdeDeploymentPath.fromProgramName("hello")
        val ticket = ticket(artifact = artifact)

        fixture.coordinator.verify(artifact)
        assertIs<IdeTargetState.Uploading>(fixture.coordinator.state())
        fixture.port.verifications.single().future.complete(IdeVerifyResult.Verified(ticket))
        fixture.coordinator.tick()
        assertEquals(IdeTargetState.Verified(target(), artifact.hash), fixture.coordinator.state())
        assertEquals(0, fixture.port.revisions.size)
        assertEquals(0, fixture.port.deployments.size)

        fixture.coordinator.deploy(artifact, path)
        assertEquals(1, fixture.port.verifications.size)
        fixture.port.revisions.single().future.complete(IdeRevisionResult.Observed(IdeExecutableRevision.Absent))
        fixture.coordinator.tick()
        val deployment = fixture.port.deployments.single()
        assertSame(ticket, deployment.ticket)
        assertEquals(IdeExecutableRevision.Absent, deployment.expected)
        deployment.future.complete(IdeDeployResult.Deployed(IdeExecutableRevision.Present(1)))
        fixture.coordinator.tick()
        assertEquals(
            IdeTargetState.Deployed(target(), path, IdeExecutableRevision.Present(1)),
            fixture.coordinator.state(),
        )
    }

    @Test
    fun `existing executable requires exact confirmation and stale revision requires it again`() {
        val fixture = attachedFixture()
        val artifact = artifact()
        val path = IdeDeploymentPath.fromProgramName("hello")

        fixture.coordinator.deploy(artifact, path)
        fixture.port.verifications.single().future.complete(IdeVerifyResult.Verified(ticket(artifact = artifact)))
        fixture.coordinator.tick()
        fixture.port.revisions.single().future.complete(
            IdeRevisionResult.Observed(IdeExecutableRevision.Present(4)),
        )
        fixture.coordinator.tick()
        assertEquals(
            IdeTargetState.ConfirmationRequired(target(), path, IdeExecutableRevision.Present(4)),
            fixture.coordinator.state(),
        )
        assertEquals(0, fixture.port.deployments.size)

        fixture.coordinator.confirmDeployment()
        assertEquals(IdeExecutableRevision.Present(4), fixture.port.deployments.single().expected)
        fixture.port.deployments.single().future.complete(
            IdeDeployResult.StaleRevision(IdeExecutableRevision.Present(5)),
        )
        fixture.coordinator.tick()
        assertEquals(
            IdeTargetState.ConfirmationRequired(target(), path, IdeExecutableRevision.Present(5)),
            fixture.coordinator.state(),
        )

        fixture.coordinator.confirmDeployment()
        assertEquals(IdeExecutableRevision.Present(5), fixture.port.deployments.last().expected)
    }

    @Test
    fun `retryable deploy failure retains ticket while success consumes it`() {
        val fixture = attachedFixture()
        val artifact = artifact()
        val path = IdeDeploymentPath.fromProgramName("hello")
        fixture.coordinator.deploy(artifact, path)
        fixture.port.verifications.single().future.complete(IdeVerifyResult.Verified(ticket(artifact = artifact)))
        fixture.coordinator.tick()
        fixture.port.revisions.single().future.complete(IdeRevisionResult.Observed(IdeExecutableRevision.Absent))
        fixture.coordinator.tick()
        fixture.port.deployments.single().future.complete(
            IdeDeployResult.Failed(failure(IdeTargetFailureKind.Timeout), retryable = true),
        )
        fixture.coordinator.tick()

        fixture.coordinator.deploy(artifact, path)
        assertEquals(1, fixture.port.verifications.size)
        fixture.port.revisions.last().future.complete(IdeRevisionResult.Observed(IdeExecutableRevision.Absent))
        fixture.coordinator.tick()
        fixture.port.deployments.last().future.complete(
            IdeDeployResult.Deployed(IdeExecutableRevision.Present(1)),
        )
        fixture.coordinator.tick()

        fixture.coordinator.deploy(artifact, path)
        assertEquals(2, fixture.port.verifications.size)
    }

    @Test
    fun `artifact and target changes invalidate verification tickets`() {
        val fixture = attachedFixture()
        val firstArtifact = artifact(1)
        fixture.coordinator.verify(firstArtifact)
        fixture.port.verifications.single().future.complete(
            IdeVerifyResult.Verified(ticket(artifact = firstArtifact)),
        )
        fixture.coordinator.tick()

        fixture.coordinator.deploy(artifact(2), IdeDeploymentPath.fromProgramName("other"))
        assertEquals(2, fixture.port.verifications.size)

        fixture.coordinator.detach()
        val attach = fixture.port.nextAttach()
        fixture.coordinator.attach(claim())
        attach.complete(IdeAttachResult.Attached(target(profileSeed = 2)))
        fixture.coordinator.tick()
        fixture.coordinator.deploy(firstArtifact, IdeDeploymentPath.fromProgramName("hello"))
        assertEquals(3, fixture.port.verifications.size)
    }

    private fun fixture(): Fixture {
        val port = ControlledTargetPort()
        val clock = TargetClock()
        return Fixture(port, clock, IdeTargetCoordinator(port, clock))
    }

    private fun attachedFixture(): Fixture =
        fixture().also { fixture ->
            val attached = fixture.port.nextAttach()
            fixture.coordinator.attach(claim())
            attached.complete(IdeAttachResult.Attached(target()))
            fixture.coordinator.tick()
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
    val verifications = mutableListOf<VerificationCall>()
    val revisions = mutableListOf<RevisionCall>()
    val deployments = mutableListOf<DeploymentCall>()

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
    ): CompletableFuture<IdeVerifyResult> =
        CompletableFuture<IdeVerifyResult>().also { verifications += VerificationCall(target, artifact, it) }

    override fun executableRevision(
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
    ): CompletableFuture<IdeRevisionResult> =
        CompletableFuture<IdeRevisionResult>().also { revisions += RevisionCall(target, path, it) }

    override fun deploy(
        target: IdeAttachedTarget,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
    ): CompletableFuture<IdeDeployResult> =
        CompletableFuture<IdeDeployResult>().also {
            deployments += DeploymentCall(target, ticket, path, expected, it)
        }

    override fun submitCanonicalLine(
        target: IdeAttachedTarget,
        line: CharArray,
    ): CompletableFuture<IdeSubmissionResult> = error("not used")

    data class VerificationCall(
        val target: IdeAttachedTarget,
        val artifact: IdeTargetArtifact,
        val future: CompletableFuture<IdeVerifyResult>,
    )

    data class RevisionCall(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
        val future: CompletableFuture<IdeRevisionResult>,
    )

    data class DeploymentCall(
        val target: IdeAttachedTarget,
        val ticket: IdeVerificationTicket,
        val path: IdeDeploymentPath,
        val expected: IdeExecutableRevision,
        val future: CompletableFuture<IdeDeployResult>,
    )
}

private fun claim() = IdeTargetClaim.of(byteArrayOf(1))

private fun target(profileSeed: Byte = 1) =
    IdeAttachedTarget(
        IdeTargetId("computer-1"),
        IdeTargetProfileId(Hash256.of(ByteArray(32) { profileSeed })),
        IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true),
        "Computer",
    )

private fun failure(kind: IdeTargetFailureKind) = IdeTargetFailure(kind, kind.name)

private fun artifact(seed: Byte = 1) = IdeTargetArtifact(Hash256.of(ByteArray(32) { seed }), byteArrayOf(seed))

private fun ticket(
    target: IdeAttachedTarget = target(),
    artifact: IdeTargetArtifact = artifact(),
) = IdeVerificationTicket.of(byteArrayOf(9), target, artifact)
