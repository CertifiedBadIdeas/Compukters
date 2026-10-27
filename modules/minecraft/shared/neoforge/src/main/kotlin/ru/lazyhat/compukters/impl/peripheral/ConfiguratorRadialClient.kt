/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem

/** Suppress ordinary item use until a short click is distinguished from a mode-selection hold. */
internal object ConfiguratorRadialClient {
    private val gesture = ConfiguratorUseGesture()
    private var hand: InteractionHand? = null
    private var target: BlockHitResult? = null
    private var stack: net.minecraft.world.item.ItemStack? = null

    fun mouse(event: InputEvent.MouseButton.Pre) {
        if (event.button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return
        val client = Minecraft.getInstance()
        if (client.screen is ConfiguratorRadialScreen) return
        if (client.screen != null) {
            cancel()
            return
        }
        if (event.action == GLFW.GLFW_PRESS) {
            val selected = ConfiguratorOverlayClient.held() ?: return
            hand = selected
            stack = client.player?.getItemInHand(selected)
            target = (client.hitResult as? BlockHitResult)?.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }
            gesture.press(System.nanoTime())
            event.isCanceled = true
            client.options.keyUse.isDown = false
        } else if (event.action == GLFW.GLFW_RELEASE && gesture.active) {
            event.isCanceled = true
            val selected = hand
            if (valid() && gesture.release(System.nanoTime()) && selected != null) {
                send(ConfiguratorActionPayload(selected, -1, target?.blockPos, target?.direction ?: net.minecraft.core.Direction.NORTH))
            }
            cancel()
        }
    }

    fun tick(
        @Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post,
    ) {
        val client = Minecraft.getInstance()
        if (client.screen is ConfiguratorRadialScreen) return
        if (!gesture.active) return
        if (!valid() || client.screen != null || !client.isWindowActive) {
            cancel()
            return
        }
        client.options.keyUse.isDown = false
        if (GLFW.glfwGetMouseButton(ConfiguratorWindow.handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            cancel()
            return
        }
        if (gesture.open(System.nanoTime())) client.setScreen(ConfiguratorRadialScreen())
    }

    fun valid(): Boolean {
        val client = Minecraft.getInstance()
        val current = hand?.let { client.player?.getItemInHand(it) }
        return client.connection != null && current === stack && current?.item is PeripheralConfiguratorItem
    }

    fun choose(mode: Int?) {
        if (mode != null && mode in ConfiguratorRadialGeometry.modes.indices && valid()) {
            send(ConfiguratorActionPayload(checkNotNull(hand), mode))
        }
        cancel()
    }

    fun cancel() {
        gesture.cancel()
        hand = null
        stack = null
        target = null
    }

    private fun send(payload: ConfiguratorActionPayload) {
        Minecraft.getInstance().connection?.send(
            net.minecraft.network.protocol.common
                .ServerboundCustomPayloadPacket(payload),
        )
    }
}
