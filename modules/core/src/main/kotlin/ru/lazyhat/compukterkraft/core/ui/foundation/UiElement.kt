package ru.lazyhat.compukterkraft.core.ui.foundation

sealed interface UiElement {
    val modifier: UiModifier

    data class Box(
        override val modifier: UiModifier = Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Text(
        override val modifier: UiModifier = Modifier,
        val value: UiExpression<String>,
        val color: Int = 0xFFFFFF,
    ) : UiElement

    data class TerminalSurface(
        override val modifier: UiModifier = Modifier,
        val snapshot: UiExpression<Any?>,
        val onFocus: () -> Unit = {},
        val onKey: (Int) -> Boolean = { false },
    ) : UiElement

    data class IfNode(
        override val modifier: UiModifier = Modifier,
        val condition: UiExpression<Boolean>,
        val children: List<UiElement>,
    ) : UiElement
}

class UiScope {
    private val children = mutableListOf<UiElement>()

    fun box(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Box(modifier, UiScope().apply(block).build())
    }

    fun text(value: UiExpression<String>, modifier: UiModifier = Modifier, color: Int = 0xFFFFFF) {
        children += UiElement.Text(modifier, value, color)
    }

    fun terminalSurface(
        snapshot: UiExpression<Any?>,
        modifier: UiModifier = Modifier,
        onFocus: () -> Unit = {},
        onKey: (Int) -> Boolean = { false },
    ) {
        children += UiElement.TerminalSurface(modifier.role(UiRole.TerminalSurface), snapshot, onFocus, onKey)
    }

    fun if_(condition: UiExpression<Boolean>, block: UiScope.() -> Unit) {
        children += UiElement.IfNode(condition = condition, children = UiScope().apply(block).build())
    }

    fun button(
        text: UiExpression<String>,
        modifier: UiModifier = Modifier,
        onClick: () -> Unit = {},
    ) {
        box(modifier.role(UiRole.Button).focusable().clickable(onClick)) {
            this.text(value = text, modifier = Modifier.offset(8, 6))
        }
    }

    fun build(): List<UiElement> = children
}

fun ui(block: UiScope.() -> Unit): UiElement = UiElement.Box(children = UiScope().apply(block).build())
