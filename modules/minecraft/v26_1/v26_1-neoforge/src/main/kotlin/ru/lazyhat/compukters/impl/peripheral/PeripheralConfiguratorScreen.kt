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

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorMode
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSnapshot
import java.util.Locale

internal class PeripheralConfiguratorScreen(
    private val hand: InteractionHand,
    private val snapshot: PeripheralConfiguratorSnapshot,
) : Screen(
        Component.translatable(
            if (snapshot.mode == PeripheralConfiguratorMode.EDIT_DEVICE) {
                "screen.compukters.peripheral_configurator"
            } else {
                "screen.compukters.peripheral_configurator.network"
            },
        ),
    ) {
    private var nameBox: EditBox? = null
    private var saveResult: PeripheralConfiguratorSaveResult? = null
    private var rowOffset = 0

    override fun init() {
        val panelWidth = 260
        val left = (width - panelWidth) / 2
        val editor =
            EditBox(
                font,
                left + 10,
                48,
                panelWidth - 20,
                20,
                Component.translatable(
                    if (snapshot.mode ==
                        PeripheralConfiguratorMode.EDIT_DEVICE
                    ) {
                        "screen.compukters.peripheral_configurator.name"
                    } else {
                        "screen.compukters.peripheral_configurator.network_name"
                    },
                ),
            )
        editor.setMaxLength(32)
        editor.value = nameBox?.value ?: snapshot.configuredName
        nameBox = editor
        addRenderableWidget(editor)
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
        setInitialFocus(editor)
        if (snapshot.mode == PeripheralConfiguratorMode.INSPECT_NETWORK) {
            snapshot.entries.drop(rowOffset).take(visibleRows()).forEachIndexed { index, entry ->
                entry.instance?.let { instance ->
                    addRenderableWidget(
                        Button
                            .builder(Component.literal("×")) {
                                ClientPacketDistributor.sendToServer(RemoveMemberPayload(hand, snapshot.context.toWire(), instance))
                            }.bounds(left + panelWidth - 28, 94 + index * 11, 18, 11)
                            .build(),
                    )
                }
            }
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (nameBox != null && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            save()
            return true
        }
        return super.keyPressed(event)
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xcc101010.toInt())
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val left = (width - 260) / 2
        graphics.text(font, title, width / 2 - font.width(title) / 2, 24, 0xffffff, false)
        var y = 48
        if (nameBox != null) {
            graphics.text(font, status(), left + 10, 74, statusColor(), false)
            y = 94
        } else {
            graphics.text(
                font,
                Component.translatable(
                    "screen.compukters.peripheral_configurator.summary",
                    snapshot.totalDevices,
                    snapshot.nameCounts.values.sum(),
                ),
                left + 10,
                y,
                0xaaaaaa,
                false,
            )
            y += 20
        }
        if (snapshot.topologyLimitExceeded) {
            graphics.text(
                font,
                Component.translatable("screen.compukters.peripheral_configurator.limit"),
                left + 10,
                y,
                0xff5555,
                false,
            )
        } else {
            if (snapshot.entries.isEmpty()) {
                graphics.text(
                    font,
                    Component.translatable("screen.compukters.peripheral_configurator.empty"),
                    left + 10,
                    y,
                    0xaaaaaa,
                    false,
                )
            }
            snapshot.entries.drop(rowOffset).take(visibleRows()).forEach { entry ->
                val name =
                    entry.name?.let(Component::literal) ?: Component.translatable("screen.compukters.peripheral_configurator.unnamed")
                val type = "${entry.providerId}:${entry.deviceKey.replace('_', ' ')}"
                val state =
                    Component.translatable(
                        "screen.compukters.peripheral_configurator.availability.${entry.availability.name.lowercase(Locale.ROOT)}",
                    )
                val color =
                    if (entry.duplicate ||
                        entry.availability !in
                        setOf(
                            ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAvailability.AVAILABLE,
                            ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAvailability.COMPUTER,
                        )
                    ) {
                        0xff5555
                    } else if (entry.name == null) {
                        0x777777
                    } else {
                        0xaaaaaa
                    }
                graphics.text(
                    font,
                    font.plainSubstrByWidth("${state.string} · ${name.string} ($type)", 214),
                    left + 10,
                    y,
                    color,
                    false,
                )
                y += 11
            }
            if (snapshot.entries.size > visibleRows() || snapshot.truncated) {
                graphics.text(
                    font,
                    Component.translatable(
                        "screen.compukters.peripheral_configurator.more",
                        rowOffset + 1,
                        minOf(rowOffset + visibleRows(), snapshot.entries.size),
                        snapshot.totalDevices,
                    ),
                    left + 10,
                    y,
                    0xaaaaaa,
                    false,
                )
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val maximum = (snapshot.entries.size - visibleRows()).coerceAtLeast(0)
        if (maximum == 0 || scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        rowOffset = (rowOffset + if (scrollY > 0.0) -1 else 1).coerceIn(0, maximum)
        rebuildWidgets()
        return true
    }

    internal fun handleSave(result: PeripheralConfiguratorSaveResult) {
        if (nameBox == null) return
        saveResult = result
        if (result == PeripheralConfiguratorSaveResult.NAMED_DEVICE || result == PeripheralConfiguratorSaveResult.NAMED_NETWORK ||
            result == PeripheralConfiguratorSaveResult.MEMBER_REMOVED
        ) {
            onClose()
        }
    }

    private fun save() {
        val editor = nameBox ?: return
        ClientPacketDistributor.sendToServer(SavePayload(hand, snapshot.context.toWire(), editor.value))
    }

    private fun status(): Component {
        saveResult?.let { return Component.translatable("screen.compukters.peripheral_configurator.result.${it.name.lowercase()}") }
        val normalized =
            nameBox
                ?.value
                .orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
        if (!Regex("[a-z][a-z0-9_-]{0,31}").matches(normalized)) {
            return Component.translatable("screen.compukters.peripheral_configurator.invalid")
        }
        val count = if (snapshot.mode == PeripheralConfiguratorMode.INSPECT_NETWORK) 0 else snapshot.nameCounts[normalized] ?: 0
        return when {
            count == 0 -> Component.translatable("screen.compukters.peripheral_configurator.free")
            snapshot.targetName == normalized && count == 1 -> Component.translatable("screen.compukters.peripheral_configurator.current")
            else -> Component.translatable("screen.compukters.peripheral_configurator.conflict")
        }
    }

    private fun statusColor(): Int {
        val normalized =
            nameBox
                ?.value
                .orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
        val count = if (snapshot.mode == PeripheralConfiguratorMode.INSPECT_NETWORK) 0 else snapshot.nameCounts[normalized] ?: 0
        val valid = Regex("[a-z][a-z0-9_-]{0,31}").matches(normalized)
        val available = count == 0 || (snapshot.targetName == normalized && count == 1)
        return if (valid && available) {
            0x55ff55
        } else {
            0xff5555
        }
    }

    private fun visibleRows(): Int = ((height - 148) / 11).coerceIn(1, VISIBLE_ROWS)

    private companion object {
        const val VISIBLE_ROWS = 10
    }
}
