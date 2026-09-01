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
 */

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeTargetTerminalSessionServiceTest {
    @Test
    fun `open publishes the initial state and subsequent deltas for the leased target`() {
        val fixture = fixture()
        val opened = assertIs<IdeTerminalOpenedPayload>(fixture.service.open(OWNER, 3, fixture.reference, tick = 2))

        assertEquals(TOKEN, opened.token)
        assertEquals(7, opened.machineId)
        assertEquals(state(1), opened.state)

        fixture.update = TerminalUpdate.Delta(1, 2, listOf(TerminalChange.Cursor(TerminalPosition(1, 0), true)))
        val delivery = fixture.service.publish(tick = 3).single()
        val delta = assertIs<IdeTerminalDeltaPayload>(delivery.payload)
        assertEquals(OWNER, delivery.player)
        assertEquals(TOKEN, delta.token)
        assertEquals(2, delta.delta.targetRevision)
        assertTrue(fixture.service.publish(tick = 4).isEmpty())
    }

    @Test
    fun `resync returns a delta or full state only to the session owner`() {
        val fixture = fixture()
        fixture.service.open(OWNER, 1, fixture.reference, tick = 1)
        fixture.update = TerminalUpdate.Delta(1, 2, listOf(TerminalChange.Reset))

        assertIs<IdeTerminalDeltaPayload>(
            fixture.service.resync(OWNER, IdeTerminalResyncPayload(TOKEN, 7, 1), tick = 2),
        )
        assertEquals(null, fixture.service.resync(ATTACKER, IdeTerminalResyncPayload(TOKEN, 7, 1), tick = 2))

        fixture.update = null
        assertIs<IdeTerminalFullPayload>(
            fixture.service.resync(OWNER, IdeTerminalResyncPayload(TOKEN, 7, 2), tick = 2),
        )
    }

    @Test
    fun `input checks token player machine and one shared admission boundary`() {
        var admitted = 0
        val fixture = fixture(inputAdmission = { player, tick -> player == OWNER && tick == 5L && admitted++ == 0 })
        fixture.service.open(OWNER, 1, fixture.reference, tick = 1)

        assertTrue(
            fixture.service.key(
                OWNER,
                IdeTerminalKeyPayload(TOKEN, 7, TerminalKey.ENTER, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)),
                tick = 5,
            ),
        )
        assertFalse(fixture.service.text(OWNER, IdeTerminalTextPayload(TOKEN, 7, "x"), tick = 5))
        assertFalse(fixture.service.text(ATTACKER, IdeTerminalTextPayload(TOKEN, 7, "x"), tick = 6))
        assertFalse(fixture.service.text(OWNER, IdeTerminalTextPayload(TOKEN, 8, "x"), tick = 6))
        assertEquals(listOf("key:ENTER:PRESS:CONTROL"), fixture.inputs)
    }

    @Test
    fun `lease removal and machine replacement end the exact session`() {
        val fixture = fixture()
        fixture.service.open(OWNER, 8, fixture.reference, tick = 1)
        fixture.machineId = 9

        val failed =
            assertIs<IdeTerminalFailedPayload>(
                fixture.service
                    .publish(tick = 2)
                    .single()
                    .payload,
            )
        assertEquals(8, failed.generation)
        assertEquals(TOKEN, failed.token)
        assertEquals(IdeTargetFailureKind.TargetLost, failed.kind)
        assertFalse(fixture.service.close(OWNER, IdeTerminalClosePayload(TOKEN)))

        fixture.machineId = 7
        fixture.service.open(OWNER, 9, fixture.reference, tick = 3)
        fixture.leases.detach(OWNER, fixture.attached)
        assertFalse(fixture.service.close(OWNER, IdeTerminalClosePayload(TOKEN)))
        assertIs<IdeTerminalFailedPayload>(
            fixture.service
                .publish(tick = 4)
                .single()
                .payload,
        )
    }

    @Test
    fun `targets without terminal support fail without allocating a session`() {
        val fixture = fixture(terminal = false)

        val failed = assertIs<IdeTerminalFailedPayload>(fixture.service.open(OWNER, 1, fixture.reference, tick = 1))

        assertEquals(null, failed.token)
        assertEquals(IdeTargetFailureKind.Unsupported, failed.kind)
        assertFalse(failed.retryable)
    }

    private fun fixture(
        terminal: Boolean = true,
        inputAdmission: (UUID, Long) -> Boolean = { _, _ -> true },
    ): Fixture {
        var machineId = 7L
        var update: TerminalUpdate? = TerminalUpdate.Unchanged(1)
        val inputs = mutableListOf<String>()
        val profile = TargetCompileProfile(toolchain(), emptyList(), WorkerLimits())
        val resolved =
            IdeResolvedTarget(
                machineIdentity = "overworld:1,2,3:7",
                profileId = IdeTargetProfileId(Hash256.zero()),
                profile = profile,
                capabilities = IdeTargetCapabilities(true, true, terminal),
                displayName = "Computer",
                alive = { true },
                deployment = IdeTargetDeploymentOperations(verifyForDeploy = { null }),
                terminal =
                    if (terminal) {
                        IdeTargetTerminalOperations(
                            machineId = { machineId },
                            fullState = { state(1) },
                            changesSince = { update },
                            submitKey = { key, action, modifiers ->
                                inputs += "key:$key:$action:${modifiers.joinToString()}"
                                true
                            },
                            submitText = { text ->
                                inputs += "text:$text"
                                true
                            },
                        )
                    } else {
                        null
                    },
            )
        val leases =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("lease-1") },
            )
        val attached = assertIs<IdeAttachResult.Attached>(leases.attach(OWNER, IdeTargetClaim.of(byteArrayOf(1)), 0)).target
        val service = IdeTargetTerminalSessionService(leases, tokens = { TOKEN }, inputAdmission = inputAdmission)
        return Fixture(
            leases,
            attached,
            IdeTargetReference(attached.id, attached.profile),
            service,
            inputs,
            machineId = { machineId },
            setMachineId = { machineId = it },
            update = { update },
            setUpdate = { update = it },
        )
    }

    private class Fixture(
        val leases: IdeTargetLeaseService,
        val attached: ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget,
        val reference: IdeTargetReference,
        val service: IdeTargetTerminalSessionService,
        val inputs: List<String>,
        private val machineId: () -> Long,
        private val setMachineId: (Long) -> Unit,
        private val update: () -> TerminalUpdate?,
        private val setUpdate: (TerminalUpdate?) -> Unit,
    ) {
        var machineIdValue: Long
            get() = machineId()
            set(value) = setMachineId(value)

        var updateValue: TerminalUpdate?
            get() = update()
            set(value) = setUpdate(value)
    }

    private var Fixture.machineId: Long
        get() = machineIdValue
        set(value) {
            machineIdValue = value
        }

    private var Fixture.update: TerminalUpdate?
        get() = updateValue
        set(value) {
            updateValue = value
        }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000001")

        fun state(revision: Long) =
            TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                TerminalPosition(0, 0),
                true,
            )

        fun toolchain() =
            ToolchainLockIdentity(
                compilerVersion = "2.4.0",
                languageVersion = "2.4",
                codegenAbi = 1u,
                artifactAbi = 2u,
                artifactWriterVersion = 1u,
                payloadHash = Hash256.zero(),
                platformAbi = Hash256.zero(),
            )
    }
}
