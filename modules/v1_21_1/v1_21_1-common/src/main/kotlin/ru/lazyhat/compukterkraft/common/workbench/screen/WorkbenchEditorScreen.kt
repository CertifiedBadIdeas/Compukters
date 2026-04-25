/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package ru.lazyhat.compukterkraft.common.workbench.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.infrastructure.coroutines.minecraft
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.LanguageWorkbenchIdeFacade
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.MenuWorkspaceUpdateSource
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.NetworkWorkbenchControlGateway
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.NetworkWorkbenchWorkspaceGateway
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.WorkbenchTargetCatalogSource
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.common.workbench.input.WorkbenchClientInputHandler
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuWithoutInventory
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchPositionableSlot
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.computer.workbench.screen.WorkbenchEditorViewModel
import ru.lazyhat.compukterkraft.core.computer.workbench.screen.WorkbenchInventoryLayout
import ru.lazyhat.compukterkraft.core.computer.workbench.screen.buildWorkbenchUi
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement

/**
 * Thin Minecraft-side adapter for the Workbench IDE screen.
 *
 * The whole UI tree lives in [buildWorkbenchUi] (in `:core`), driven by a
 * [WorkbenchStore] and a small [WorkbenchEditorViewModel] adapter. This
 * subclass only owns the lifecycle (binding/disposing the store, scoping
 * coroutines) and Minecraft-specific plumbing (full-screen sizing,
 * relocating the menu's slots so the right-hand inventory panel is
 * functional).
 */
class WorkbenchEditorScreen(
    container: WorkbenchMenuWithoutInventory,
    player: Inventory,
    title: Component,
) : DslContainerScreen<WorkbenchMenuWithoutInventory>(container, player, title) {
    private val inputHandler = WorkbenchClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val store =
        WorkbenchStore(
            workspaceGateway = NetworkWorkbenchWorkspaceGateway(container),
            controlGateway = NetworkWorkbenchControlGateway(container),
            ideFacade = LanguageWorkbenchIdeFacade(WorkbenchTargetCatalogSource(container.workspaceStateFlow.value.target)),
        )
    private val viewModel = WorkbenchEditorViewModel(store)
    private var screenScope: CoroutineScope? = null
    private var lastShapeSignature: Long = Long.MIN_VALUE

    override fun init() {
        imageWidth = width
        imageHeight = height
        leftPos = 0
        topPos = 0
        super.init()
        leftPos = 0
        topPos = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.minecraft)
        screenScope = scope
        store.bind(scope, MenuWorkspaceUpdateSource(menu.workspaceStateFlow))
        if (store.state.mode != WorkbenchMode.EDITOR) {
            store.toggleMode()
        }
        store.initialize()
        repositionInventorySlots()
    }

    override fun removed() {
        store.dispose()
        screenScope?.cancel()
        screenScope = null
        super.removed()
    }

    override fun containerTick() {
        super.containerTick()
        if (!store.state.terminalVisible) {
            terminalInput.focused = false
        }
        syncFullscreenWindowSize()
        terminalInput.update()
        // Keep the Minecraft slots aligned with the DSL inventory panel even
        // when the window is resized at runtime.
        repositionInventorySlots()
        // Only recompile when the *shape* of the tree would differ. Per-frame
        // text / cursor / snapshot changes flow through Value<T>s and don't
        // need a recompile. The signature includes everything the builder
        // branches on with plain Kotlin (if / forEach / list size).
        val shape = currentShapeSignature()
        if (shape != lastShapeSignature) {
            lastShapeSignature = shape
            invalidate()
        }
    }

    private fun currentShapeSignature(): Long {
        val s = store.state
        val ed = s.editor
        var h = 1L
        h = 31L * h + imageWidth
        h = 31L * h + imageHeight
        h = 31L * h + s.terminalVisible.hashCode()
        h = 31L * h + s.browserPath.hashCode()
        h = 31L * h + s.entries.size
        h = 31L * h + s.entries.hashCode()
        h = 31L * h + ed.completionItems.size
        h = 31L * h + ed.importPickerVisible.hashCode()
        h = 31L * h + ed.importPickerItems.size
        h = 31L * h + (s.openDocument?.path?.hashCode() ?: 0)
        return h
    }

    override fun content(): UiElement =
        buildWorkbenchUi(
            store = store,
            viewport = IntSize(imageWidth, imageHeight),
            viewModel = viewModel,
            terminalSnapshot = { menu.screenSnapshot },
            onTerminalKey = { keyCode ->
                terminalInput.focused = true
                terminalInput.keyPressed(keyCode, 0, 0)
            },
            onTerminalKeyReleased = { keyCode ->
                terminalInput.keyReleased(keyCode, 0)
            },
            onTerminalCharTyped = { ch ->
                terminalInput.focused = true
                terminalInput.charTyped(ch)
            },
        )

    private fun syncFullscreenWindowSize() {
        if (imageWidth != width || imageHeight != height || leftPos != 0 || topPos != 0) {
            imageWidth = width
            imageHeight = height
            leftPos = 0
            topPos = 0
        }
    }

    /**
     * Move the menu's 37 slots (1 target + 27 main inventory + 9 hotbar)
     * to match the absolute positions painted by the DSL inventory panel.
     * Coordinates come from [WorkbenchInventoryLayout] so the chrome and
     * the slots can never drift apart.
     */
    private fun repositionInventorySlots() {
        if (imageWidth <= 0 || imageHeight <= 0) return
        val layout =
            WorkbenchInventoryLayout.compute(
                viewport = IntSize(imageWidth, imageHeight),
                panelTop = TOOLBAR_HEIGHT,
            )
        menu.slots.forEachIndexed { index, slot ->
            val positionable = slot as? WorkbenchPositionableSlot ?: return@forEachIndexed
            val (slotX, slotY) =
                when (index) {
                    0 -> {
                        layout.targetSlotX to layout.targetSlotY
                    }

                    in 1..27 -> {
                        val zeroBased = index - 1
                        val rowIdx = zeroBased / 9
                        val colIdx = zeroBased % 9
                        (layout.mainGridX + colIdx * WorkbenchInventoryLayout.SLOT_SIZE) to
                            (layout.mainGridY + rowIdx * WorkbenchInventoryLayout.SLOT_SIZE)
                    }

                    in 28..36 -> {
                        val colIdx = index - 28
                        (layout.hotbarX + colIdx * WorkbenchInventoryLayout.SLOT_SIZE) to layout.hotbarY
                    }

                    else -> {
                        return@forEachIndexed
                    }
                }
            positionable.relocate(slotX, slotY)
        }
    }

    private companion object {
        // Mirrors `TOOLBAR_HEIGHT` in WorkbenchUiBuilder; kept in sync by hand
        // since the DSL builder treats it as private.
        private const val TOOLBAR_HEIGHT = 22
    }
}
