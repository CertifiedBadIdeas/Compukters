package ru.lazyhat.compukterkraft.core.ui.foundation

enum class UiRole {
    Button,
    TerminalSurface,
}

enum class UiAlignment {
    Start,
    Center,
    End,
    Stretch,
}

data class UiPadding(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class UiModifier(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val zIndex: Int = 0,
    val focusable: Boolean = false,
    val role: UiRole? = null,
    val onClick: (() -> Unit)? = null,
    val padding: UiPadding = UiPadding(),
    val alignment: UiAlignment? = null,
    val weight: Float? = null,
    val color: Color? = null,
) {
    fun offset(
        x: Int,
        y: Int,
    ): UiModifier = copy(x = this.x + x, y = this.y + y)

    fun size(
        width: Int,
        height: Int,
    ): UiModifier = copy(width = width, height = height)

    fun zIndex(value: Int): UiModifier = copy(zIndex = value)

    fun focusable(): UiModifier = copy(focusable = true)

    fun role(value: UiRole): UiModifier = copy(role = value)

    fun clickable(onClick: () -> Unit): UiModifier = copy(onClick = onClick)

    fun align(value: UiAlignment): UiModifier = copy(alignment = value)

    fun weight(value: Float): UiModifier {
        require(value > 0f)
        return copy(weight = value)
    }

    fun padding(all: Int): UiModifier = padding(all, all, all, all)

    fun padding(
        horizontal: Int,
        vertical: Int,
    ): UiModifier = padding(horizontal, vertical, horizontal, vertical)

    fun padding(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): UiModifier {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
        return copy(padding = UiPadding(left, top, right, bottom))
    }

    fun color(value: Color): UiModifier = copy(color = value)

    companion object {
        val Empty = UiModifier()
    }
}

val Modifier: UiModifier = UiModifier.Empty
