package ru.lazyhat.kraftui.foundation

/**
 * A mutable handle that a UI author hands to `Modifier.hoverable(...)` to
 * observe whether the mouse is currently over the owning element.
 *
 * The runtime writes to [isHovered] once per render tick based on the
 * latest mouse position; authors read it from inside
 * [Value]s or from a canvas draw lambda:
 *
 * ```
 * val buttonHover = HoverState()
 * canvas(modifier = Modifier.size(16, 16).hoverable(buttonHover)) {
 *     fillRect(0, 0, 16, 16, if (buttonHover.isHovered) Color.White else Color.Gray)
 * }
 * ```
 *
 * A single [HoverState] may be used by at most one element (the compiler
 * enforces this by having the last `hoverable` call win — reusing a state
 * across elements would produce ambiguous readings).
 */
class HoverState {
    var isHovered: Boolean = false
        internal set
}
