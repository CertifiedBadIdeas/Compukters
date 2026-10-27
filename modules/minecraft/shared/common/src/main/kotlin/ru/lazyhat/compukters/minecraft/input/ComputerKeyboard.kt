/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.input

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import ru.lazyhat.compukters.core.input.KeyboardEndpoint
import ru.lazyhat.compukters.core.input.KeyboardFrame
import ru.lazyhat.compukters.core.input.KeyboardInputState
import ru.lazyhat.compukters.core.input.KeyboardUnavailable
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
import java.util.UUID

/** Server-thread authority for explicitly consenting player input. Nothing here is saved to the world. */
object ComputerKeyboard {
    private class Entry(
        val computer: ComputerBlockEntity,
        val owner: Any,
        val input: KeyboardInputState,
    ) {
        var epoch: Long? = computer.terminalMachineId
        var player: UUID? = null
        var token: UUID? = null
    }

    private class Store {
        val computers = linkedMapOf<ComputerBlockEntity, Entry>()
        val players = linkedMapOf<UUID, Entry>()
    }

    private val stores = java.util.WeakHashMap<MinecraftServer, Store>()
    var sendStatus: (ServerPlayer, UUID?, Long) -> Unit = { _, _, _ -> }

    fun endpoint(computer: ComputerBlockEntity): KeyboardEndpoint =
        object : KeyboardEndpoint {
            private val level get() = computer.level as? ServerLevel ?: throw KeyboardUnavailable("Computer is unavailable")

            private fun store(): Store {
                check(level.server.isSameThread)
                return stores.getOrPut(level.server) { Store() }
            }

            override fun acquire(
                owner: Any,
                mask: Long,
            ) {
                val store = store()
                if (computer.isRemoved ||
                    level.getBlockEntity(computer.blockPos) !== computer
                ) {
                    throw KeyboardUnavailable("Computer was replaced")
                }
                val old = store.computers[computer]
                if (old != null && old.owner !== owner) throw KeyboardUnavailable("Keyboard is owned by another program")
                if (old == null) {
                    if (store.computers.size >= 256) throw KeyboardUnavailable("Server keyboard limit reached")
                    store.computers[computer] = Entry(computer, owner, KeyboardInputState(mask))
                }
            }

            override fun snapshot(owner: Any): KeyboardFrame {
                val entry =
                    store().computers[computer]?.takeIf { it.owner === owner && valid(it) }
                        ?: throw KeyboardUnavailable("Keyboard computer is unavailable or changed")
                return entry.input.poll(level.server.overworld().gameTime)
            }

            override fun release(owner: Any) {
                val server = (computer.level as? ServerLevel)?.server ?: return
                check(server.isSameThread)
                val store = stores[server] ?: return
                val entry = store.computers[computer]?.takeIf { it.owner === owner } ?: return
                stop(server, store, entry)
                store.computers.remove(computer)
            }
        }

    /** The configurator's short-click action; programs can request keys but cannot consent for the player. */
    fun toggle(
        player: ServerPlayer,
        position: BlockPos,
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        check(level.server.isSameThread)
        if (player.isSpectator || !player.isAlive || !level.hasChunkAt(position) || !level.mayInteract(player, position) ||
            !PeripheralWorldPositions.within(level, player.position(), position.center, 8.0)
        ) {
            return false
        }
        val computer = level.getBlockEntity(position) as? ComputerBlockEntity ?: return false
        val store = stores[level.server] ?: return false
        val entry = store.computers[computer]?.takeIf(::valid) ?: return false
        if (entry.player != null && entry.player != player.uuid) return false
        if (entry.player == player.uuid) {
            stop(level.server, store, entry)
            return true
        }
        store.players[player.uuid]?.let { stop(level.server, store, it) }
        entry.player = player.uuid
        entry.token = UUID.randomUUID()
        entry.input.connect(level.server.overworld().gameTime)
        store.players[player.uuid] = entry
        sendStatus(player, entry.token, entry.input.mask)
        return true
    }

    fun heartbeat(
        player: ServerPlayer,
        token: UUID,
        sequence: Long,
        keys: Long,
        release: Boolean,
    ) {
        val level = player.level() as? ServerLevel ?: return
        check(level.server.isSameThread)
        val store = stores[level.server]
        val entry = store?.players?.get(player.uuid)
        if (entry == null || entry.token != token) {
            sendStatus(player, null, 0)
            return
        }
        if (release || !validPlayer(entry, player)) {
            stop(level.server, store, entry)
            return
        }
        entry.input.update(sequence, keys, level.server.overworld().gameTime)
    }

    fun tick(server: MinecraftServer) {
        check(server.isSameThread)
        val store = stores[server] ?: return
        for (entry in store.computers.values.toList()) {
            if (!valid(entry)) {
                stop(server, store, entry)
                store.computers.remove(entry.computer)
                continue
            }
            entry.input.expire(server.overworld().gameTime)
            val player = entry.player?.let(server.playerList::getPlayer)
            if (entry.player != null &&
                (player == null || !entry.input.connected || !validPlayer(entry, player))
            ) {
                stop(server, store, entry)
            }
        }
    }

    fun disconnect(player: ServerPlayer) {
        val server = (player.level() as? ServerLevel)?.server ?: return
        val store = stores[server] ?: return
        store.players[player.uuid]?.let { stop(server, store, it) }
    }

    fun shutdown(server: MinecraftServer) {
        val store = stores.remove(server) ?: return
        store.computers.values.forEach { stop(server, store, it) }
    }

    private fun valid(entry: Entry): Boolean {
        val c = entry.computer
        val level = c.level as? ServerLevel ?: return false
        if (c.isRemoved || !level.hasChunkAt(c.blockPos) || level.getBlockEntity(c.blockPos) !== c) return false
        val epoch = c.terminalMachineId ?: return c.peripheralCheckpointPending
        if (entry.epoch == null) entry.epoch = epoch
        return entry.epoch == epoch
    }

    private fun validPlayer(
        entry: Entry,
        player: ServerPlayer,
    ): Boolean =
        valid(entry) && player.isAlive && !player.isSpectator &&
            player.level() === entry.computer.level &&
            PeripheralWorldPositions.within(player.level(), player.position(), entry.computer.blockPos.center, 64.0)

    private fun stop(
        server: MinecraftServer,
        store: Store,
        entry: Entry,
    ) {
        entry.player?.let { id ->
            store.players.remove(id)
            server.playerList.getPlayer(id)?.let { sendStatus(it, null, 0L) }
        }
        entry.player = null
        entry.token = null
        entry.input.disconnect()
    }
}
