package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerScreen
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
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
abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    private var executor: ScreenRuntimeExecutor? = null
    private var compiledLeft: Int = Int.MIN_VALUE
    private var compiledTop: Int = Int.MIN_VALUE
    private var compiledWidth: Int = Int.MIN_VALUE
    private var compiledHeight: Int = Int.MIN_VALUE

    abstract fun content(): UiElement

    /**
     * Discard the cached executor so the next [renderBg] recompiles from
     * scratch. Call this when the *shape* of [content] would differ — most
     * dynamic content should instead use [ValueExpression]s and not require
     * a rebuild.
     */
    protected fun invalidate() {
        executor = null
    }

    /**
     * Whether the single focusable element (if any) currently has focus.
     * Mirrors [ScreenRuntimeExecutor.isFocused] of the latest executor and
     * returns `false` before the first render or when there is no
     * focusable element at all.
     */
    protected val isDslFocused: Boolean
        get() = executor?.isFocused == true

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
        val program =
            ScreenProgramCompiler(fontMetrics = FontMetrics { text -> font.width(text) })
                .compile(
                    root = content(),
                    rootX = leftPos,
                    rootY = topPos,
                    rootWidth = imageWidth,
                    rootHeight = imageHeight,
                )
        executor = ScreenRuntimeExecutor(program)
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
        return (executor != null && executor.keyPressed(keyCode)) ||
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
}
