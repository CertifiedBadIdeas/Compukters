package ru.lazyhat.kraftui.foundation

import ru.lazyhat.kraftui.editor.EditorViewModel
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.Position
import ru.lazyhat.kraftui.foundation.modifier.UiAlignment
import ru.lazyhat.kraftui.foundation.modifier.clickable
import ru.lazyhat.kraftui.foundation.modifier.focusable

sealed interface UiElement {
    val modifier: Modifier

    data class Box(
        override val modifier: Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Row(
        override val modifier: Modifier,
        val children: List<UiElement>,
        val gap: Int = 0,
        val verticalAlignment: UiAlignment? = null,
    ) : UiElement

    data class Column(
        override val modifier: Modifier,
        val children: List<UiElement>,
        val gap: Int = 0,
        val horizontalAlignment: UiAlignment? = null,
    ) : UiElement

    data class Text(
        override val modifier: Modifier,
        val color: Value<Color>,
        val text: Value<String>,
    ) : UiElement

    data class TerminalSurface(
        override val modifier: Modifier,
        val snapshot: Value<Any>,
        val onKey: (Int) -> Boolean = { false },
        val onKeyReleased: (Int) -> Boolean = { false },
        val onCharTyped: (Char) -> Boolean = { false },
    ) : UiElement

    /**
     * A fixed-size drawing surface with no children. [onDraw] is invoked each
     * frame with a [CanvasScope] whose origin is the canvas's top-left.
     *
     * The canvas must carry a `size` modifier; its bounds come from the
     * layout pass, not from anything the draw lambda reports.
     */
    data class Canvas(
        override val modifier: Modifier,
        val onDraw: CanvasScope.() -> Unit,
    ) : UiElement

    data class IfNode(
        override val modifier: Modifier,
        val condition: Value<Boolean>,
        val children: List<UiElement>,
    ) : UiElement

    /**
     * A detached, floating subtree. Its children are laid out inside their own
     * coordinate frame starting at `(0, 0)` and sized by this element's size
     * modifier. At render time the frame is translated by [anchor] (evaluated
     * each tick) and skipped entirely when [visible] evaluates to `false`.
     *
     * Overlays do not take part in their parent's flow layout.
     */
    data class Overlay(
        override val modifier: Modifier,
        val anchor: Value<Position>?,
        val visible: Value<Boolean>?,
        val children: List<UiElement>,
    ) : UiElement

    /**
     * A clipped, scrollable container. Children are laid out as if the area
     * had its full content size, then translated by `(-scrollX, -scrollY)`
     * each frame and clipped to the area's outer rectangle.
     *
     * The `modifier` must carry a fixed `size` (the viewport size). Content
     * is laid out using the same width/height — for now the DSL does not
     * require an "intrinsic content size" larger than the viewport; users
     * supply children that overflow naturally and rely on `scrollX/scrollY`
     * to bring the desired part into view.
     *
     * [onScroll] receives the wheel delta when the cursor is inside the
     * viewport. Returning `true` consumes the event.
     */
    data class ScrollArea(
        override val modifier: Modifier,
        val scrollX: Value<Int>,
        val scrollY: Value<Int>,
        val onScroll: (Double) -> Boolean = { false },
        val children: List<UiElement>,
    ) : UiElement

    /**
     * A first-class code editor element. The editor reads its visible state
     * (text, cursor, highlights, diagnostics, scroll) from the supplied
     * [viewModel] and delegates input back through it.
     *
     * The editor automatically claims a focus node and a hit region; the
     * host does not need to wire `Modifier.focusable` or `clickable`.
     *
     * [fontWidth] and [fontHeight] describe a single monospace glyph in
     * pixels and are used both for layout (visible-lines / columns) and
     * for click → (line, column) translation.
     */
    data class CodeEditor(
        override val modifier: Modifier,
        val viewModel: Value<EditorViewModel>,
        val fontWidth: Int,
        val fontHeight: Int,
    ) : UiElement
}

class UiScope {
    private val children = mutableListOf<UiElement>()

    fun box(
        modifier: Modifier = Modifier,
        block: UiScope.() -> Unit = {},
    ) {
        children += UiElement.Box(modifier, UiScope().apply(block).build())
    }

    fun row(
        modifier: Modifier = Modifier,
        gap: Int = 0,
        verticalAlignment: UiAlignment? = null,
        block: UiScope.() -> Unit,
    ) {
        require(gap >= 0)
        children += UiElement.Row(modifier, UiScope().apply(block).build(), gap, verticalAlignment)
    }

    fun column(
        modifier: Modifier = Modifier,
        gap: Int = 0,
        horizontalAlignment: UiAlignment? = null,
        block: UiScope.() -> Unit,
    ) {
        require(gap >= 0)
        children += UiElement.Column(modifier, UiScope().apply(block).build(), gap, horizontalAlignment)
    }

    fun button(
        onClick: () -> Unit,
        block: UiScope.() -> Unit,
    ) {
        button(Modifier, onClick, block)
    }

    fun button(
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        block: UiScope.() -> Unit,
    ) {
        box(modifier.clickable(onClick), block)
    }

    fun text(
        modifier: Modifier = Modifier,
        color: Color = Color.White,
        text: Value<String>,
    ) {
        children += UiElement.Text(modifier, value(color), text)
    }

    fun text(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.White,
    ) {
        children += UiElement.Text(modifier, value(color), value(text))
    }

    fun text(
        modifier: Modifier = Modifier,
        color: Color = Color.White,
        text: () -> String,
    ) {
        children += UiElement.Text(modifier, value(color), value(text))
    }

    fun text(
        color: () -> Color,
        modifier: Modifier = Modifier,
        text: () -> String,
    ) {
        children += UiElement.Text(modifier, value(color), value(text))
    }

    /**
     * Variant that accepts a reactive [color]. The runtime re-reads the color
     * every frame, so callers can drive it from a [Value] without forcing a
     * screen rebuild (handy for things like disabled-button dimming).
     */
    fun text(
        color: Value<Color>,
        text: Value<String>,
        modifier: Modifier = Modifier,
    ) {
        children += UiElement.Text(modifier, color, text)
    }

    fun terminalSurface(
        snapshot: Value<Any>,
        modifier: Modifier = Modifier,
        onKey: (Int) -> Boolean = { false },
        onKeyReleased: (Int) -> Boolean = { false },
        onCharTyped: (Char) -> Boolean = { false },
    ) {
        children += UiElement.TerminalSurface(modifier, snapshot, onKey, onKeyReleased, onCharTyped)
    }

    fun canvas(
        modifier: Modifier = Modifier,
        onDraw: CanvasScope.() -> Unit,
    ) {
        children += UiElement.Canvas(modifier, onDraw)
    }

    fun keySurface(
        modifier: Modifier = Modifier,
        id: String,
        tabOrder: Int = 0,
        onKeyPressed: (Int) -> Boolean = { false },
        onKeyReleased: (Int) -> Boolean = { false },
        onCharTyped: (Char) -> Boolean = { false },
    ) {
        canvas(
            modifier =
                modifier.focusable(
                    id = id,
                    tabOrder = tabOrder,
                    onKeyPressed = onKeyPressed,
                    onKeyReleased = onKeyReleased,
                    onCharTyped = onCharTyped,
                ),
        ) {
        }
    }

    @Suppress("FunctionName")
    fun If(
        condition: Value<Boolean>,
        block: UiScope.() -> Unit,
    ) {
        children += UiElement.IfNode(modifier = Modifier, condition = condition, children = UiScope().apply(block).build())
    }

    fun overlay(
        modifier: Modifier = Modifier,
        anchor: Value<Position>? = null,
        visible: Value<Boolean>? = null,
        block: UiScope.() -> Unit,
    ) {
        children +=
            UiElement.Overlay(
                modifier = modifier,
                anchor = anchor,
                visible = visible,
                children = UiScope().apply(block).build(),
            )
    }

    fun scrollArea(
        modifier: Modifier = Modifier,
        scrollX: Value<Int> = value { 0 },
        scrollY: Value<Int> = value { 0 },
        onScroll: (Double) -> Boolean = { false },
        block: UiScope.() -> Unit,
    ) {
        children +=
            UiElement.ScrollArea(
                modifier = modifier,
                scrollX = scrollX,
                scrollY = scrollY,
                onScroll = onScroll,
                children = UiScope().apply(block).build(),
            )
    }

    fun codeEditor(
        viewModel: Value<EditorViewModel>,
        modifier: Modifier = Modifier,
        fontWidth: Int = 6,
        fontHeight: Int = 9,
    ) {
        children +=
            UiElement.CodeEditor(
                modifier = modifier,
                viewModel = viewModel,
                fontWidth = fontWidth,
                fontHeight = fontHeight,
            )
    }

    fun build(): List<UiElement> = children
}

fun ui(
    modifier: Modifier = Modifier,
    block: UiScope.() -> Unit,
): UiElement =
    UiElement.Box(
        modifier = modifier,
        children = UiScope().apply(block).build(),
    )
