package ru.lazyhat.compukterkraft.core.ui.foundation

import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.clickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size

sealed interface UiElement {
    val modifier: Modifier

    data class Box(
        override val modifier: Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Row(
        override val modifier: Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Column(
        override val modifier: Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Text(
        override val modifier: Modifier,
        val color: Color,
        val value: ValueExpression<String>,
    ) : UiElement

    data class TerminalSurface(
        override val modifier: Modifier,
        val snapshot: ValueExpression<Any?>,
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
        val condition: ValueExpression<Boolean>,
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
        val anchor: ValueExpression<Position>?,
        val visible: ValueExpression<Boolean>?,
        val children: List<UiElement>,
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
        block: UiScope.() -> Unit,
    ) {
        children += UiElement.Row(modifier, UiScope().apply(block).build())
    }

    fun column(
        modifier: Modifier = Modifier,
        block: UiScope.() -> Unit,
    ) {
        children += UiElement.Column(modifier, UiScope().apply(block).build())
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
        text: ValueExpression<String>,
    ) {
        children += UiElement.Text(modifier, color, text)
    }

    fun terminalSurface(
        snapshot: ValueExpression<Any?>,
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

    @Suppress("FunctionName")
    fun If(
        condition: ValueExpression<Boolean>,
        block: UiScope.() -> Unit,
    ) {
        children += UiElement.IfNode(modifier = Modifier, condition = condition, children = UiScope().apply(block).build())
    }

    fun overlay(
        modifier: Modifier = Modifier,
        anchor: ValueExpression<Position>? = null,
        visible: ValueExpression<Boolean>? = null,
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
