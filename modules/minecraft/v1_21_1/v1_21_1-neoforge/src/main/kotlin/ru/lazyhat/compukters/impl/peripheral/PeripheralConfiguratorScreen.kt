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

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSnapshot
import java.util.Locale

internal class PeripheralConfiguratorScreen(
    private val hand: InteractionHand,
    private val snapshot: PeripheralConfiguratorSnapshot,
) : Screen(Component.translatable("screen.compukters.peripheral_configurator")) {
    private lateinit var nameBox: EditBox
    private var saveResult: PeripheralConfiguratorSaveResult? = null
    private var rowOffset = 0

    override fun init() {
        val panelWidth = 260
        val left = (width - panelWidth) / 2
        nameBox =
            EditBox(font, left + 10, 48, panelWidth - 20, 20, Component.translatable("screen.compukters.peripheral_configurator.name"))
        nameBox.setMaxLength(32)
        nameBox.value = snapshot.configuredName
        addRenderableWidget(nameBox)
        addRenderableWidget(
            Button
                .builder(Component.translatable("gui.done")) { save() }
                .bounds(left + 10, height - 38, 115, 20)
                .build(),
        )
        addRenderableWidget(
            Button
                .builder(Component.translatable("gui.cancel")) { onClose() }
                .bounds(left + 135, height - 38, 115, 20)
                .build(),
        )
        setInitialFocus(nameBox)
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val left = (width - 260) / 2
        graphics.drawCenteredString(font, title, width / 2, 24, 0xffffff)
        graphics.drawString(font, status(), left + 10, 74, statusColor(), false)
        var y = 94
        if (snapshot.topologyLimitExceeded) {
            graphics.drawString(
                font,
                Component.translatable("screen.compukters.peripheral_configurator.limit"),
                left + 10,
                y,
                0xff5555,
                false,
            )
        } else {
            snapshot.entries.drop(rowOffset).take(VISIBLE_ROWS).forEach { entry ->
                val type = entry.deviceKey.replace('_', ' ')
                val color = if (entry.duplicate) 0xff5555 else 0xaaaaaa
                graphics.drawString(font, "${entry.name}  $type", left + 10, y, color, false)
                y += 11
            }
            if (snapshot.entries.size > VISIBLE_ROWS || snapshot.truncated) {
                graphics.drawString(
                    font,
                    Component.translatable(
                        "screen.compukters.peripheral_configurator.more",
                        rowOffset + 1,
                        minOf(rowOffset + VISIBLE_ROWS, snapshot.entries.size),
                        snapshot.totalNamedDevices,
                    ),
                    left + 10,
                    y,
                    0xaaaaaa,
                    false,
                )
            }
        }
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xcc101010.toInt())
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val maximum = (snapshot.entries.size - VISIBLE_ROWS).coerceAtLeast(0)
        if (maximum == 0 || scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        rowOffset = (rowOffset + if (scrollY > 0.0) -1 else 1).coerceIn(0, maximum)
        return true
    }

    internal fun handleSave(result: PeripheralConfiguratorSaveResult) {
        saveResult = result
        if (result == PeripheralConfiguratorSaveResult.NAMED_DEVICE) {
            onClose()
        }
    }

    private fun save() {
        PacketDistributor.sendToServer(SavePayload(hand, snapshot.context.toWire(), nameBox.value))
    }

    private fun status(): Component {
        saveResult?.let { return Component.translatable("screen.compukters.peripheral_configurator.result.${it.name.lowercase()}") }
        val normalized = nameBox.value.trim().lowercase(Locale.ROOT)
        if (!Regex("[a-z][a-z0-9_-]{0,31}").matches(normalized)) {
            return Component.translatable("screen.compukters.peripheral_configurator.invalid")
        }
        val count = snapshot.nameCounts[normalized] ?: 0
        return when {
            count == 0 -> Component.translatable("screen.compukters.peripheral_configurator.free")
            snapshot.targetName == normalized && count == 1 -> Component.translatable("screen.compukters.peripheral_configurator.current")
            else -> Component.translatable("screen.compukters.peripheral_configurator.conflict")
        }
    }

    private fun statusColor(): Int {
        val normalized = nameBox.value.trim().lowercase(Locale.ROOT)
        val count = snapshot.nameCounts[normalized] ?: 0
        val valid = Regex("[a-z][a-z0-9_-]{0,31}").matches(normalized)
        val available = count == 0 || (snapshot.targetName == normalized && count == 1)
        return if (valid && available) {
            0x55ff55
        } else {
            0xff5555
        }
    }

    private companion object {
        const val VISIBLE_ROWS = 10
    }
}
