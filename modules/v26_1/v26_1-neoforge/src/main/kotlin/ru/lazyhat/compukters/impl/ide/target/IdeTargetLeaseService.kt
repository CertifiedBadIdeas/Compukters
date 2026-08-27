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

import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import java.util.UUID

internal fun interface IdeTargetClaimResolver {
    fun resolve(
        player: UUID,
        claim: IdeTargetClaim,
    ): IdeClaimResolution
}

internal sealed interface IdeClaimResolution {
    data class Resolved(
        val target: IdeResolvedTarget,
    ) : IdeClaimResolution

    data class Rejected(
        val failure: IdeTargetFailure,
    ) : IdeClaimResolution
}

internal data class IdeResolvedTarget(
    val machineIdentity: String,
    val profileId: IdeTargetProfileId,
    val profile: TargetCompileProfile,
    val capabilities: IdeTargetCapabilities,
    val displayName: String,
    val alive: () -> Boolean,
) {
    init {
        require(machineIdentity.isNotBlank()) { "machine identity must not be blank" }
    }
}

internal class IdeTargetLeaseService(
    private val resolver: IdeTargetClaimResolver,
    private val targetIds: () -> IdeTargetId = { IdeTargetId(UUID.randomUUID().toString()) },
    private val leaseTicks: Long = DEFAULT_LEASE_TICKS,
) : AutoCloseable {
    private val leases = mutableMapOf<UUID, Lease>()
    private var closed = false

    init {
        require(leaseTicks > 0) { "target lease duration must be positive" }
    }

    fun attach(
        player: UUID,
        claim: IdeTargetClaim,
        tick: Long,
    ): IdeAttachResult {
        checkOpen()
        require(tick >= 0) { "server tick must not be negative" }
        val resolved =
            when (val resolution = resolver.resolve(player, claim)) {
                is IdeClaimResolution.Rejected -> return IdeAttachResult.Rejected(resolution.failure)
                is IdeClaimResolution.Resolved -> resolution.target
            }
        if (!resolved.alive()) return IdeAttachResult.Rejected(targetLost())
        val attached =
            IdeAttachedTarget(
                id = targetIds(),
                profile = resolved.profileId,
                compileProfile = resolved.profile,
                capabilities = resolved.capabilities,
                displayName = resolved.displayName,
            )
        leases[player] = Lease(attached, resolved, expiresAt(tick))
        return IdeAttachResult.Attached(attached)
    }

    fun heartbeat(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): IdeHeartbeatResult {
        checkOpen()
        val lease = liveLease(player, target, tick) ?: return IdeHeartbeatResult.Lost(targetLost())
        leases[player] = lease.copy(expiresAt = expiresAt(tick))
        return IdeHeartbeatResult.Alive
    }

    fun access(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): IdeResolvedTarget? {
        checkOpen()
        return liveLease(player, target, tick)?.resolved
    }

    fun detach(
        player: UUID,
        target: IdeAttachedTarget,
    ) {
        checkOpen()
        if (leases[player]?.target == target) leases.remove(player)
    }

    fun expire(tick: Long) {
        checkOpen()
        require(tick >= 0) { "server tick must not be negative" }
        leases.entries.removeIf { (_, lease) -> tick >= lease.expiresAt || !lease.resolved.alive() }
    }

    override fun close() {
        if (closed) return
        closed = true
        leases.clear()
    }

    private fun liveLease(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): Lease? {
        require(tick >= 0) { "server tick must not be negative" }
        val lease = leases[player] ?: return null
        if (lease.target != target) return null
        if (tick >= lease.expiresAt || !lease.resolved.alive()) {
            leases.remove(player)
            return null
        }
        return lease
    }

    private fun expiresAt(tick: Long): Long =
        try {
            Math.addExact(tick, leaseTicks)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun checkOpen() = check(!closed) { "target lease service is closed" }

    private data class Lease(
        val target: IdeAttachedTarget,
        val resolved: IdeResolvedTarget,
        val expiresAt: Long,
    )

    private companion object {
        const val DEFAULT_LEASE_TICKS = 300L

        fun targetLost() = IdeTargetFailure(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
    }
}
