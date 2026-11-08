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
import ru.lazyhat.compukters.core.input.KeyboardInputSender
import ru.lazyhat.compukters.core.input.KeyboardKeys
import ru.lazyhat.compukters.core.input.KeyboardPerformanceTrace
import java.util.UUID

/** Only a server-confirmed, explicitly activated capture can intercept world keys. Mouse look stays native. */
object KeyboardInputClient {
    private var token: UUID? = null
    private var mask = 0L
    private var sequence = -1L
    private var sender: KeyboardInputSender? = null
    private var connection: Any? = null
    private var level: Any? = null

    internal fun status(payload: KeyboardStatusPayload) {
        val client = Minecraft.getInstance()
        token = payload.token
        mask = payload.mask
        sequence = -1
        connection = client.connection
        level = client.level
        sender =
            payload.token?.let { current ->
                KeyboardInputSender(mask) { update ->
                    sequence = update.sequence
                    KeyboardPerformanceTrace.begin("keyboard.send")?.apply {
                        capture = current.toString()
                        sequence = update.sequence
                        keys = update.keys
                        pressed = update.pressed
                        released = update.released
                        commit()
                    }
                    client.connection?.send(
                        ServerboundCustomPayloadPacket(
                            KeyboardHeldPayload(
                                current,
                                update.sequence,
                                update.keys,
                                false,
                                update.pressed,
                                update.released,
                            ),
                        ),
                    )
                }
            }
        net.minecraft.client.KeyMapping
            .releaseAll()
    }

    @JvmStatic
    fun consumeKey(
        key: Int,
        action: Int,
    ): Boolean {
        if (token == null) return false
        val client = Minecraft.getInstance()
        if (key == GLFW.GLFW_KEY_ESCAPE || !valid(client)) {
            release()
            return false
        }
        val index = KeyboardKeys.index(key)
        val captured = index >= 0 && mask and (1L shl index) != 0L
        if (captured) {
            KeyboardPerformanceTrace.begin("keyboard.raw")?.apply {
                capture = token.toString()
                this.key = key
                this.action = action
                commit()
            }
            when (action) {
                GLFW.GLFW_PRESS -> sender?.keyDown(key)
                GLFW.GLFW_RELEASE -> sender?.keyUp(key)
            }
        }
        return captured
    }

    fun tick(
        @Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post,
    ) {
        val client = Minecraft.getInstance()
        if (token == null) return
        if (!valid(client)) {
            release()
            return
        }
        val down = mutableSetOf<Int>()
        val window = KeyboardWindow.handle()
        KeyboardKeys.codes.forEachIndexed { index, code ->
            if (mask and (1L shl index) != 0L) {
                if (GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS) down.add(code)
                if (code in 340..342 && GLFW.glfwGetKey(window, code + 4) == GLFW.GLFW_PRESS) down.add(code + 4)
            }
        }
        sender?.tick(down)
    }

    fun caption(): Component? = token?.let { Component.translatable("input.compukters.keyboard.active") }

    private fun valid(client: Minecraft): Boolean =
        client.connection === connection && client.level === level && client.screen == null && client.isWindowActive &&
            client.player?.isAlive == true

    private fun release() {
        val client = Minecraft.getInstance()
        val current = token ?: return
        token = null
        mask = 0L
        sender = null
        sequence++
        if (client.connection ===
            connection
        ) {
            KeyboardPerformanceTrace.begin("keyboard.send")?.apply {
                capture = current.toString()
                this.sequence = KeyboardInputClient.sequence
                release = true
                commit()
            }
            client.connection?.send(ServerboundCustomPayloadPacket(KeyboardHeldPayload(current, sequence, 0L, true)))
        }
        net.minecraft.client.KeyMapping
            .releaseAll()
    }
}
