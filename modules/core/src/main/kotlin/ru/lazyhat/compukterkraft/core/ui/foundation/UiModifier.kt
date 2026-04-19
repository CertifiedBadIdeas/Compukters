package ru.lazyhat.compukterkraft.core.ui.foundation

enum class UiRole {
    Button,
    TerminalSurface,
}

data class UiModifier(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val zIndex: Int = 0,
    val focusable: Boolean = false,
    val role: UiRole? = null,
    val onClick: (() -> Unit)? = null,
) {
    fun offset(x: Int, y: Int): UiModifier = copy(x = this.x + x, y = this.y + y)

    fun size(width: Int, height: Int): UiModifier = copy(width = width, height = height)

    fun zIndex(value: Int): UiModifier = copy(zIndex = value)

    fun focusable(): UiModifier = copy(focusable = true)

    fun role(value: UiRole): UiModifier = copy(role = value)

    fun clickable(onClick: () -> Unit): UiModifier = copy(onClick = onClick)

    companion object {
        val Empty = UiModifier()
    }
}

val Modifier: UiModifier = UiModifier.Empty
