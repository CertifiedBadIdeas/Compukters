/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.terminal

import kotlin.math.Position2i
import kotlin.math.Rect2i
import kotlin.math.Size2i
import kotlin.math.Vector2i

/** An owned input snapshot. Receiving it releases the native event before returning. */
public sealed interface TerminalEvent {
    public data class Text(
        val text: String,
    ) : TerminalEvent

    /** An atomic complete line from the host, admitted only by an opted-in receiver. */
    public data class SubmittedLine(
        val text: String,
    ) : TerminalEvent

    public data class Key(
        val key: TerminalKey,
        val action: TerminalKeyAction,
        val modifiers: TerminalModifiers,
    ) : TerminalEvent
}

public enum class TerminalKey {
    ESCAPE,
    BACKSPACE,
    TAB,
    ENTER,
    C,
    S,
    T,
    U,
    X,
    INSERT,
    DELETE,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    UP,
    LEFT,
    DOWN,
    RIGHT,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

public enum class TerminalKeyAction { PRESS, REPEAT }

public value class TerminalModifiers internal constructor(
    private val bits: Int,
) {
    public val shift: Boolean get() = bits and 1 != 0
    public val control: Boolean get() = bits and 2 != 0
    public val alt: Boolean get() = bits and 4 != 0
    public val superKey: Boolean get() = bits and 8 != 0
}

public enum class TerminalColor {
    BLACK,
    RED,
    GREEN,
    BROWN,
    BLUE,
    MAGENTA,
    CYAN,
    LIGHT_GRAY,
    DARK_GRAY,
    LIGHT_RED,
    LIGHT_GREEN,
    YELLOW,
    LIGHT_BLUE,
    LIGHT_MAGENTA,
    LIGHT_CYAN,
    WHITE,
}

public object Terminal {
    public val cursor: Position2i get() = Vector2i(TerminalBindings.cursorX(), TerminalBindings.cursorY())
    public val size: Size2i get() = Vector2i(TerminalBindings.width(), TerminalBindings.height())

    public fun write(text: String) {
        TerminalBindings.write(text)
    }

    public fun erasePrevious() {
        TerminalBindings.erasePrevious()
    }

    public fun clear() {
        TerminalBindings.clear()
    }

    public fun setCursor(position: Position2i) {
        TerminalBindings.setCursor(position.x, position.y)
    }

    public fun setCursorVisible(visible: Boolean) {
        TerminalBindings.setCursorVisible(visible)
    }

    public fun setColors(
        foreground: TerminalColor,
        background: TerminalColor,
    ) {
        TerminalBindings.setColors(terminalColor(foreground), terminalColor(background))
    }

    public fun writeAt(
        position: Position2i,
        text: String,
    ) {
        TerminalBindings.writeAt(position.x, position.y, text)
    }

    public fun fill(rectangle: Rect2i) {
        fill(rectangle, ' ')
    }

    public fun fill(
        rectangle: Rect2i,
        character: Char,
    ) {
        if (!rectangle.isEmpty) TerminalBindings.fill(rectangle.left, rectangle.top, rectangle.size.x, rectangle.size.y, character)
    }

    public fun readEvent(): TerminalEvent = decodeTerminalEvent(TerminalBindings.awaitEvent())

    /**
     * Also accepts an atomic host-submitted line, bounded in UTF-16 units.
     * A zero limit marks a line editor with a draft; submissions are rejected.
     * The caller must enable reception only when a complete line can be accepted.
     */
    public fun readEvent(submittedLineLimit: Int): TerminalEvent {
        require(submittedLineLimit in 0..4096)
        return decodeTerminalEvent(TerminalBindings.awaitSubmittedLineEvent(submittedLineLimit))
    }

    private fun decodeTerminalEvent(kind: Int): TerminalEvent {
        try {
            return when (kind) {
                1 -> {
                    TerminalEvent.Text(TerminalBindings.eventText())
                }

                2 -> {
                    TerminalEvent.Key(
                        terminalKey(TerminalBindings.eventKey()),
                        when (TerminalBindings.eventAction()) {
                            1 -> TerminalKeyAction.PRESS
                            2 -> TerminalKeyAction.REPEAT
                            else -> throw IllegalStateException("Invalid terminal key action")
                        },
                        TerminalModifiers(TerminalBindings.eventModifiers()),
                    )
                }

                3 -> {
                    TerminalEvent.SubmittedLine(TerminalBindings.eventText())
                }

                else -> {
                    throw IllegalStateException("Invalid terminal event kind")
                }
            }
        } finally {
            TerminalBindings.finishEvent()
        }
    }
}

private fun terminalKey(code: Int): TerminalKey =
    when (code) {
        1 -> TerminalKey.ESCAPE
        8 -> TerminalKey.BACKSPACE
        9 -> TerminalKey.TAB
        13 -> TerminalKey.ENTER
        67 -> TerminalKey.C
        83 -> TerminalKey.S
        84 -> TerminalKey.T
        85 -> TerminalKey.U
        88 -> TerminalKey.X
        256 -> TerminalKey.INSERT
        257 -> TerminalKey.DELETE
        258 -> TerminalKey.HOME
        259 -> TerminalKey.END
        260 -> TerminalKey.PAGE_UP
        261 -> TerminalKey.PAGE_DOWN
        262 -> TerminalKey.UP
        263 -> TerminalKey.LEFT
        264 -> TerminalKey.DOWN
        265 -> TerminalKey.RIGHT
        272 -> TerminalKey.F1
        273 -> TerminalKey.F2
        274 -> TerminalKey.F3
        275 -> TerminalKey.F4
        276 -> TerminalKey.F5
        277 -> TerminalKey.F6
        278 -> TerminalKey.F7
        279 -> TerminalKey.F8
        280 -> TerminalKey.F9
        281 -> TerminalKey.F10
        282 -> TerminalKey.F11
        283 -> TerminalKey.F12
        else -> throw IllegalStateException("Invalid terminal key")
    }

private fun terminalColor(color: TerminalColor): Int =
    when (color) {
        TerminalColor.BLACK -> 0
        TerminalColor.RED -> 1
        TerminalColor.GREEN -> 2
        TerminalColor.BROWN -> 3
        TerminalColor.BLUE -> 4
        TerminalColor.MAGENTA -> 5
        TerminalColor.CYAN -> 6
        TerminalColor.LIGHT_GRAY -> 7
        TerminalColor.DARK_GRAY -> 8
        TerminalColor.LIGHT_RED -> 9
        TerminalColor.LIGHT_GREEN -> 10
        TerminalColor.YELLOW -> 11
        TerminalColor.LIGHT_BLUE -> 12
        TerminalColor.LIGHT_MAGENTA -> 13
        TerminalColor.LIGHT_CYAN -> 14
        TerminalColor.WHITE -> 15
    }

private object TerminalBindings {
    external fun write(text: String)

    external fun erasePrevious()

    external fun clear()

    external fun awaitEvent(): Int

    external fun awaitSubmittedLineEvent(limit: Int): Int

    external fun eventText(): String

    external fun eventKey(): Int

    external fun eventAction(): Int

    external fun eventModifiers(): Int

    external fun finishEvent()

    external fun setCursor(
        x: Int,
        y: Int,
    )

    external fun setCursorVisible(visible: Boolean)

    external fun setColors(
        foreground: Int,
        background: Int,
    )

    external fun writeAt(
        x: Int,
        y: Int,
        text: String,
    )

    external fun fill(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        character: Char,
    )

    external fun cursorX(): Int

    external fun cursorY(): Int

    external fun width(): Int

    external fun height(): Int
}
