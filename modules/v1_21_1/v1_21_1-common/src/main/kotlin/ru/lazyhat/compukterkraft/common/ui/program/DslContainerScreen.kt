package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
import ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor

/**
 * Base class for Minecraft container screens whose content is described with
 * the UI DSL.
 *
 * The content tree is compiled the first time [renderBg] runs and re-compiled
 * whenever the root bounds (`leftPos`, `topPos`, `imageWidth`,
 * `imageHeight`) change — for example when a subclass mutates them from
 * [containerTick] to track dynamic content size. Per-frame rendering only
 * walks the pre-baked render ops; there is no per-frame layout or allocation
 * while the root bounds are stable.
 *
 * Subclasses can additionally force a rebuild via [invalidate] when the
 * shape of [content] itself would differ (rare: most dynamic values should
 * live inside `ValueExpression`s that are evaluated each frame).
 */
abstract class DslContainerScreen<T : AbstractContainerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<T>(menu, inventory, title) {
    private var executor: ScreenRuntimeExecutor? = null
    private var compiledLeft: Int = Int.MIN_VALUE
    private var compiledTop: Int = Int.MIN_VALUE
    private var compiledWidth: Int = Int.MIN_VALUE
    private var compiledHeight: Int = Int.MIN_VALUE

    /**
     * The focused node id last seen on the previous executor. Captured
     * eagerly by [invalidate] so a recompile can restore focus even though
     * the cached executor itself is dropped.
     */
    private var lastFocusedNodeId: String? = null

    abstract fun content(): UiElement

    /**
     * Discard the cached executor so the next [renderBg] recompiles from
     * scratch. Call this when the *shape* of [content] would differ — most
     * dynamic content should instead use [ru.lazyhat.compukterkraft.core.ui.foundation.Value]s and not require
     * a rebuild.
     */
    protected fun invalidate() {
        // Rebuild eagerly rather than nulling out the executor. Input events
        // (charTyped, keyPressed, mouseClicked) arrive between renderBg
        // calls; if we left the executor null until the next render, every
        // event delivered in that window would fall through to super and
        // be lost. The most visible symptom was: the first key after
        // focusing the editor typed correctly, then state mutation
        // triggered invalidate(), and every subsequent key in the same
        // input batch went nowhere because the executor was null.
        // rebuildExecutor() preserves focus via lastFocusedNodeId, so this
        // is safe.
        if (compiledWidth == Int.MIN_VALUE) {
            // init() hasn't run yet; nothing to rebuild against.
            executor = null
            return
        }
        rebuildExecutor()
    }

    /**
     * Whether any focusable element currently has focus. Mirrors
     * [ScreenRuntimeExecutor.isFocused] of the latest executor and returns
     * `false` before the first render or when there is no focusable element.
     */
    protected val isDslFocused: Boolean
        get() = executor?.isFocused ?: false

    override fun init() {
        super.init()
        rebuildExecutor()
    }

    override fun renderLabels(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
    }

    override fun renderBg(
        guiGraphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (
            executor == null ||
            leftPos != compiledLeft ||
            topPos != compiledTop ||
            imageWidth != compiledWidth ||
            imageHeight != compiledHeight
        ) {
            rebuildExecutor()
        }
        executor?.updateMouse(mouseX, mouseY)
        executor?.render(GuiGraphicsRenderBackend(guiGraphics, font))
        executor?.activeTooltip?.let { text ->
            setTooltipForNextRenderPass(Component.literal(text))
        }
    }

    private fun rebuildExecutor() {
        val previousFocused = executor?.focusedNodeId ?: lastFocusedNodeId
        val program =
            ScreenProgramCompiler(fontMetrics = { text -> font.width(text) })
                .compile(
                    root = content(),
                    rootX = leftPos,
                    rootY = topPos,
                    rootWidth = imageWidth,
                    rootHeight = imageHeight,
                )
        executor =
            ScreenRuntimeExecutor(program).also {
                it.restoreFocus(previousFocused)
            }
        lastFocusedNodeId = previousFocused
        compiledLeft = leftPos
        compiledTop = topPos
        compiledWidth = imageWidth
        compiledHeight = imageHeight
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.mouseClicked(x.toInt(), y.toInt())) ||
            super.mouseClicked(x, y, button)
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.keyPressed(keyCode, modifiers)) ||
            super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.keyReleased(keyCode)) ||
            super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.charTyped(codePoint)) ||
            super.charTyped(codePoint, modifiers)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.mouseScrolled(mouseX.toInt(), mouseY.toInt(), scrollY)) ||
            super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.mouseDragged(mouseX.toInt(), mouseY.toInt())) ||
            super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val executor = executor
        // Forward to the executor first so any active drag is finalised even
        // if the parent class would otherwise consume the event.
        val handled = executor != null && executor.mouseReleased(mouseX.toInt(), mouseY.toInt())
        return super.mouseReleased(mouseX, mouseY, button) || handled
    }
}
