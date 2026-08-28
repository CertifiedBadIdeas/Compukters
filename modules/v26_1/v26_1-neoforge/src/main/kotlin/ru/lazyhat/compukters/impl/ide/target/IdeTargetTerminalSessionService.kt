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

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.terminal.TerminalInputAdmission
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID

internal data class IdeTerminalDelivery(
    val player: UUID,
    val payload: CustomPacketPayload,
)

internal class IdeTargetTerminalSessionService(
    private val leases: IdeTargetLeaseService,
    private val tokens: () -> UUID = UUID::randomUUID,
    private val inputAdmission: (UUID, Long) -> Boolean,
) : AutoCloseable {
    private val sessionsByPlayer = mutableMapOf<UUID, Session>()
    private val playersByToken = mutableMapOf<UUID, UUID>()
    private val removalObservation = leases.observeRemovals(::targetRemoved)
    private var closed = false

    constructor(
        leases: IdeTargetLeaseService,
        tokens: () -> UUID = UUID::randomUUID,
    ) : this(leases, tokens, TerminalInputAdmission::accept)

    fun open(
        player: UUID,
        generation: Long,
        target: IdeTargetReference,
        tick: Long,
    ): CustomPacketPayload {
        checkOpen()
        val attached = leases.attached(player, target, tick)
            ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable", true)
        val resolved = leases.access(player, attached, tick)
            ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable", true)
        val terminal = resolved.terminal
        if (!resolved.capabilities.terminal || terminal == null) {
            return failure(generation, null, IdeTargetFailureKind.Unsupported, "Target does not provide a terminal", false)
        }
        val machineId = terminal.machineId()
            ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target terminal is unavailable", true)
        val state = terminal.fullState()
            ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target terminal is unavailable", true)
        remove(player)
        val token = tokens()
        require(token.mostSignificantBits != 0L || token.leastSignificantBits != 0L) { "terminal session token must not be zero" }
        require(token !in playersByToken) { "terminal session token must be unique" }
        sessionsByPlayer[player] = Session(generation, token, attached, resolved, terminal, machineId, state.revision)
        playersByToken[token] = player
        return IdeTerminalOpenedPayload(generation, token, machineId, state)
    }

    fun publish(tick: Long): List<IdeTerminalDelivery> {
        checkOpen()
        val deliveries = mutableListOf<IdeTerminalDelivery>()
        sessionsByPlayer.keys.toList().forEach { player ->
            val session = sessionsByPlayer[player] ?: return@forEach
            if (!isLive(player, session, tick) || session.terminal.machineId() != session.machineId) {
                deliveries += IdeTerminalDelivery(player, lost(session))
                remove(player)
                return@forEach
            }
            when (val update = session.terminal.changesSince(session.revision)) {
                is TerminalUpdate.Delta -> {
                    if (update.targetRevision <= session.revision) return@forEach
                    if (update.baseRevision != session.revision) {
                        full(session)?.let { payload ->
                            session.revision = payload.state.revision
                            deliveries += IdeTerminalDelivery(player, payload)
                        }
                    } else {
                        session.revision = update.targetRevision
                        deliveries += IdeTerminalDelivery(player, IdeTerminalDeltaPayload(session.token, session.machineId, update))
                    }
                }

                is TerminalUpdate.Full -> {
                    session.revision = update.state.revision
                    deliveries += IdeTerminalDelivery(player, IdeTerminalFullPayload(session.token, session.machineId, update.state))
                }

                is TerminalUpdate.Unchanged,
                null,
                -> {}
            }
        }
        return deliveries
    }

    fun resync(
        player: UUID,
        payload: IdeTerminalResyncPayload,
        tick: Long,
    ): CustomPacketPayload? {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return null
        return when (val update = session.terminal.changesSince(payload.revision)) {
            is TerminalUpdate.Delta ->
                IdeTerminalDeltaPayload(session.token, session.machineId, update).also {
                    session.revision = update.targetRevision
                }
            is TerminalUpdate.Full ->
                IdeTerminalFullPayload(session.token, session.machineId, update.state).also {
                    session.revision = update.state.revision
                }
            is TerminalUpdate.Unchanged -> null
            null ->
                full(session)?.also {
                    session.revision = it.state.revision
                }
        }
    }

    fun key(
        player: UUID,
        payload: IdeTerminalKeyPayload,
        tick: Long,
    ): Boolean {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return false
        if (!inputAdmission(player, tick)) return false
        return session.terminal.submitKey(payload.key, payload.action, payload.modifiers)
    }

    fun text(
        player: UUID,
        payload: IdeTerminalTextPayload,
        tick: Long,
    ): Boolean {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return false
        if (!inputAdmission(player, tick)) return false
        return session.terminal.submitText(payload.text)
    }

    fun close(
        player: UUID,
        payload: IdeTerminalClosePayload,
    ): Boolean {
        checkOpen()
        if (playersByToken[payload.token] != player) return false
        remove(player)
        return true
    }

    override fun close() {
        if (closed) return
        removalObservation.close()
        sessionsByPlayer.clear()
        playersByToken.clear()
        closed = true
    }

    private fun matching(
        player: UUID,
        token: UUID,
        machineId: Long,
        tick: Long,
    ): Session? {
        if (playersByToken[token] != player) return null
        val session = sessionsByPlayer[player] ?: return null
        if (session.token != token || session.machineId != machineId) return null
        if (!isLive(player, session, tick) || session.terminal.machineId() != machineId) {
            remove(player)
            return null
        }
        return session
    }

    private fun isLive(
        player: UUID,
        session: Session,
        tick: Long,
    ): Boolean = leases.access(player, session.attached, tick) === session.resolved

    private fun full(session: Session): IdeTerminalFullPayload? =
        session.terminal.fullState()?.let { IdeTerminalFullPayload(session.token, session.machineId, it) }

    private fun targetRemoved(
        player: UUID,
        attached: IdeAttachedTarget,
    ) {
        if (sessionsByPlayer[player]?.attached == attached) remove(player)
    }

    private fun remove(player: UUID) {
        val session = sessionsByPlayer.remove(player) ?: return
        playersByToken.remove(session.token)
    }

    private fun lost(session: Session) =
        failure(session.generation, session.token, IdeTargetFailureKind.TargetLost, "Target terminal session ended", true)

    private fun failure(
        generation: Long,
        token: UUID?,
        kind: IdeTargetFailureKind,
        detail: String,
        retryable: Boolean,
    ) = IdeTerminalFailedPayload(generation, token, kind, detail, retryable)

    private fun checkOpen() = check(!closed) { "target terminal session service is closed" }

    private data class Session(
        val generation: Long,
        val token: UUID,
        val attached: IdeAttachedTarget,
        val resolved: IdeResolvedTarget,
        val terminal: IdeTargetTerminalOperations,
        val machineId: Long,
        var revision: Long,
    )
}
