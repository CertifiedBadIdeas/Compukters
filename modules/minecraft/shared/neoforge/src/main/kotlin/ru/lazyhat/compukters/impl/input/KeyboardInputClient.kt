/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.input

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.neoforged.neoforge.client.event.ClientTickEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.core.input.KeyboardKeys
import java.util.UUID

/** Only a server-confirmed, explicitly activated capture can intercept world keys. Mouse look stays native. */
object KeyboardInputClient {
    private var token: UUID? = null
    private var mask = 0L
    private var sequence = 0L
    private var connection: Any? = null
    private var level: Any? = null

    internal fun status(payload: KeyboardStatusPayload) {
        val client = Minecraft.getInstance()
        token = payload.token
        mask = payload.mask
        sequence = 0
        connection = client.connection
        level = client.level
        net.minecraft.client.KeyMapping
            .releaseAll()
    }

    @JvmStatic
    fun consumeKey(
        key: Int,
        @Suppress("UNUSED_PARAMETER") action: Int,
    ): Boolean {
        if (token == null) return false
        val client = Minecraft.getInstance()
        if (key == GLFW.GLFW_KEY_ESCAPE || client.screen != null || !client.isWindowActive) {
            release()
            return false
        }
        val index = KeyboardKeys.index(key)
        return index >= 0 && mask and (1L shl index) != 0L
    }

    fun tick(
        @Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post,
    ) {
        val client = Minecraft.getInstance()
        val current = token ?: return
        if (client.connection !== connection || client.level !== level || client.screen != null || !client.isWindowActive ||
            client.player?.isAlive != true
        ) {
            release()
            return
        }
        var held = 0L
        KeyboardKeys.codes.forEachIndexed { index, code ->
            if (mask and (1L shl index) != 0L && down(code)) held = held or (1L shl index)
        }
        client.connection?.send(ServerboundCustomPayloadPacket(KeyboardHeldPayload(current, sequence++, held, false)))
    }

    fun caption(): Component? = token?.let { Component.translatable("input.compukters.keyboard.active") }

    private fun down(code: Int): Boolean {
        val window = KeyboardWindow.handle()
        return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS ||
            (code in 340..342 && GLFW.glfwGetKey(window, code + 4) == GLFW.GLFW_PRESS)
    }

    private fun release() {
        val client = Minecraft.getInstance()
        val current = token ?: return
        token = null
        mask = 0L
        if (client.connection ===
            connection
        ) {
            client.connection?.send(ServerboundCustomPayloadPacket(KeyboardHeldPayload(current, sequence++, 0L, true)))
        }
        net.minecraft.client.KeyMapping
            .releaseAll()
    }
}
