/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.neoforge.client.event.ClientTickEvent
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOutline
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayGeometry
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlaySnapshot
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem

internal object ConfiguratorOverlayClient {
    private var level: ClientLevel? = null
    private var hand: InteractionHand? = null
    private var token = 0L
    private var minimumToken = 0L
    private var received = -1L
    private var ticks = 0
    private var lastReply = -100
    private var snapshot = ConfiguratorOverlaySnapshot(emptyList(), emptyList())

    fun held(): InteractionHand? {
        val player = Minecraft.getInstance().player ?: return null
        return InteractionHand.entries.firstOrNull { player.getItemInHand(it).item is PeripheralConfiguratorItem }
    }

    fun tick(
        @Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post,
    ) {
        val minecraft = Minecraft.getInstance()
        val active = held()
        if (level !== minecraft.level || hand != active) {
            level = minecraft.level
            hand = active
            minimumToken = ++token
            snapshot = ConfiguratorOverlaySnapshot(emptyList(), emptyList())
            ticks = 0
            lastReply = -100
        }
        if (active == null || level == null) return
        if (ticks++ % 10 == 0) minecraft.connection?.send(ServerboundCustomPayloadPacket(OverlayRequest(active, ++token)))
    }

    fun receive(reply: OverlayReply) {
        val current = Minecraft.getInstance().level ?: return
        if (current !== level || held() != hand || hand == null || reply.dimension != current.dimension().toString() ||
            reply.token < minimumToken || reply.token > token || reply.token <= received
        ) {
            return
        }
        received = reply.token
        lastReply = ticks
        snapshot = reply.snapshot
    }

    private fun active(): Boolean = held() != null && held() == hand && Minecraft.getInstance().level === level && ticks - lastReply <= 40

    fun outlines(): List<ConfiguratorOutline> {
        if (!active()) return emptyList()
        val minecraft = Minecraft.getInstance()
        val stack = minecraft.player?.getItemInHand(hand!!) ?: return emptyList()
        val item = stack.item as? PeripheralConfiguratorItem ?: return emptyList()
        val hover = (minecraft.hitResult as? BlockHitResult)?.blockPos
        val result =
            ConfiguratorOverlayGeometry
                .outlines(
                    snapshot,
                    item.configuratorMode(stack),
                    hover,
                    item.selectedNetwork(stack),
                    item.selectedScreen(stack).takeIf { item.selectionDimension(stack) == level?.dimension().toString() },
                ).toMutableList()
        if (item.configuratorMode(stack) == 2) {
            val first = item.selectedCorner(stack).takeIf { item.selectionDimension(stack) == level?.dimension().toString() }
            val clientLevel = level!!
            if (first != null && hover != null && clientLevel.hasChunkAt(first) && clientLevel.hasChunkAt(hover)) {
                val a = clientLevel.getBlockState(first)
                val b = clientLevel.getBlockState(hover)
                if (a.block is DisplayBlock && b.block is DisplayBlock) {
                    val preview =
                        ConfiguratorOverlayGeometry
                            .preview(
                                first,
                                a.getValue(DisplayBlock.FACING),
                                hover,
                                b.getValue(DisplayBlock.FACING),
                            )
                    result +=
                        preview ?: ConfiguratorOutline(AABB(hover).inflate(0.012), ConfiguratorOverlayGeometry.UNJOINED_COLOR, true, true)
                }
            }
        }
        return result
    }

    fun labels(): List<Component> {
        if (!active()) return emptyList()
        val minecraft = Minecraft.getInstance()
        val stack = minecraft.player?.getItemInHand(hand!!) ?: return emptyList()
        val item = stack.item as? PeripheralConfiguratorItem ?: return emptyList()
        val mode = item.configuratorMode(stack)
        val matchingDimension = item.selectionDimension(stack) == level?.dimension().toString()
        val selectedScreen = item.selectedScreen(stack).takeIf { matchingDimension }
        val selectedCorner = item.selectedCorner(stack).takeIf { matchingDimension }
        val result =
            mutableListOf<Component>(
                Component.translatable("overlay.compukters.configurator.mode." + listOf("name", "network", "display")[mode]),
            )
        val hover = (minecraft.hitResult as? BlockHitResult)?.blockPos
        val device = snapshot.devices.firstOrNull { it.position == hover }
        if (device != null) {
            result += Component.translatable(device.title)
            device.network?.let { result += Component.translatable("overlay.compukters.configurator.network", device.networkName) }
            val screen = snapshot.screens.firstOrNull { it.id == device.screen }
            screen?.let {
                val name = it.name.ifEmpty { Component.translatable("overlay.compukters.configurator.unnamed").string }
                result +=
                    Component.translatable(
                        "overlay.compukters.configurator.screen",
                        name,
                        it.columns,
                        it.rows,
                        it.panels.size,
                        it.columns * it.rows - it.panels.size,
                    )
            }
            val unjoined = ConfiguratorOverlayGeometry.unjoined(snapshot, device)
            if (unjoined != null) result += Component.translatable("overlay.compukters.configurator.unjoined")
            val action =
                when (mode) {
                    1 -> {
                        "binding"
                    }

                    2 -> {
                        when {
                            hover == null || level?.getBlockState(hover)?.block !is DisplayBlock -> "display_only"
                            selectedCorner != null -> "second_corner"
                            selectedScreen != null && device.screen == selectedScreen -> "member"
                            selectedScreen != null -> "join"
                            else -> "first_corner"
                        }
                    }

                    else -> {
                        if (device.title == "block.compukters.computer") "computer" else "name"
                    }
                }
            result += Component.translatable("overlay.compukters.configurator.action.$action")
            if (mode == 2 && action == "second_corner") {
                val first = selectedCorner
                if (first != null && hover != null && level?.hasChunkAt(first) == true) {
                    val a = level!!.getBlockState(first)
                    val b = level!!.getBlockState(hover)
                    if (a.block is DisplayBlock && b.block is DisplayBlock &&
                        ConfiguratorOverlayGeometry.preview(
                            first,
                            a.getValue(DisplayBlock.FACING),
                            hover,
                            b.getValue(DisplayBlock.FACING),
                        ) ==
                        null
                    ) {
                        result += Component.translatable("overlay.compukters.configurator.invalid_preview")
                    }
                }
            }
        }
        if (snapshot.truncated) result += Component.translatable("overlay.compukters.configurator.truncated")
        result += Component.translatable("overlay.compukters.configurator.controls")
        return result
    }
}
