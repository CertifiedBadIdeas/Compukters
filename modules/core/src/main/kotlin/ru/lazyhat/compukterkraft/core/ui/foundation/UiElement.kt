package ru.lazyhat.compukterkraft.core.ui.foundation

import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
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
    ) : UiElement

    data class IfNode(
        override val modifier: Modifier,
        val condition: ValueExpression<Boolean>,
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
    ) {
        children += UiElement.TerminalSurface(modifier, snapshot, onKey)
    }

    @Suppress("FunctionName")
    fun If(
        condition: ValueExpression<Boolean>,
        block: UiScope.() -> Unit,
    ) {
        children += UiElement.IfNode(modifier = Modifier, condition = condition, children = UiScope().apply(block).build())
    }

    fun build(): List<UiElement> = children
}

fun ui(
    modifier: Modifier = Modifier.size(20, 20),
    block: UiScope.() -> Unit,
): UiElement =
    UiElement.Box(
        modifier = modifier,
        children = UiScope().apply(block).build(),
    )
