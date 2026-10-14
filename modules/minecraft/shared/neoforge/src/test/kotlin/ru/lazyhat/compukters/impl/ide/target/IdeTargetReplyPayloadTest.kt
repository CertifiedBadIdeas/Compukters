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

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class IdeTargetReplyPayloadTest {
    @Test
    fun `peripheral payload rejects invalid registry IDs and oversized entry counts before allocation`() {
        assertFailsWith<IllegalArgumentException> { IdeNamedPeripheral("speed", "../../invalid") }
        assertFailsWith<IllegalArgumentException> { IdeNamedPeripheral("", "minecraft:stone") }
        assertFailsWith<IllegalArgumentException> { IdeNamedPeripheral("x", "minecraft:stone", lookupTypes = listOf("Injected(1)")) }
        assertFailsWith<IllegalArgumentException> { IdeNamedPeripheral("x", "minecraft:stone", lookupTypes = List(9) { "api.Type$it" }) }
        assertFailsWith<IllegalArgumentException> { IdePeripheralReference(java.util.UUID.randomUUID(), "", "motor") }
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        try {
            buffer.writeVarLong(1)
            buffer.writeByte(13)
            buffer.writeBoolean(false)
            buffer.writeVarInt(1025)
            assertFailsWith<IllegalArgumentException> { IdeTargetReplyPayload.STREAM_CODEC.decode(buffer) }
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `all target replies round trip with exact profile and typed failure`() {
        val target = target()
        val replies =
            listOf(
                IdeTargetReply.Attached(target),
                IdeTargetReply.NamedPeripherals(listOf(IdeNamedPeripheral("speed", "create:speedometer", true)), true),
                IdeTargetReply.NamedPeripherals(
                    listOf(
                        IdeNamedPeripheral(
                            "distant",
                            "minecraft:air",
                            availability = ru.lazyhat.compukters.ide.analysis.PeripheralAvailability.OutOfRange,
                        ),
                    ),
                ),
                IdeTargetReply.NamedPeripherals(
                    listOf(
                        IdeNamedPeripheral(
                            "panel",
                            "compukters:display",
                            reference = IdePeripheralReference(java.util.UUID.randomUUID(), "compukters-display", "text"),
                            lookupTypes = listOf("compukter.display.TextDisplay", "compukter.display.GraphicalDisplay"),
                        ),
                    ),
                ),
                IdeTargetReply.UploadAccepted,
                IdeTargetReply.Verified(BinaryValue.of(byteArrayOf(1, 2)), target.reference(), hash(8), artifactBytes = 42),
                IdeTargetReply.RevisionObserved(IdeExecutableRevision.Absent),
                IdeTargetReply.Deployed(IdeExecutableRevision.Present(3)),
                IdeTargetReply.StaleRevision(IdeExecutableRevision.Present(4)),
                IdeTargetReply.Submitted,
                IdeTargetReply.Alive,
                IdeTargetReply.Detached,
                IdeTargetReply.Failed(IdeTargetFailure(IdeTargetFailureKind.InputBusy, "Reader is busy"), retryable = false),
            )

        replies.forEachIndexed { index, reply ->
            val payload = IdeTargetReplyPayload(index.toLong() + 1, reply)
            assertEquals(payload, roundTrip(IdeTargetReplyPayload.STREAM_CODEC, payload))
        }
    }

    private fun target(): IdeAttachedTarget =
        IdeAttachedTarget(
            IdeTargetId("lease-1"),
            IdeTargetProfileId(hash(1)),
            TargetCompileProfile(
                ToolchainLockIdentity("2.4.10", "2.4", 1u, 2u, 3u, hash(2), hash(3)),
                listOf(ResolvedModule(ModuleId("fixture", "api"), ApiMajor(2), "2.7.1", hash(4))),
                WorkerLimits(
                    sourceFiles = 2,
                    sourceFileBytes = 3,
                    sourceBytes = 4,
                    frameBytes = 5,
                    artifactBytes = 6,
                    diagnostics = 7,
                    diagnosticTextBytes = 8,
                    stderrBytes = 9,
                    temporaryBytes = 10,
                    temporaryFiles = 11,
                ),
                listOf(
                    TrustedBundlePayload(
                        TrustedBundleIdentity.of("fixture", hash(4)),
                        BinaryValue.of(byteArrayOf(9, 8, 7)),
                    ),
                ),
            ),
            IdeTargetCapabilities(writableFileSystem = true, canonicalInput = false, terminal = true),
            "Computer 7",
        )

    private fun IdeAttachedTarget.reference() = IdeTargetReference(id, profile)

    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private fun hash(seed: Int): Hash256 = Hash256.of(ByteArray(32) { seed.toByte() })
}
