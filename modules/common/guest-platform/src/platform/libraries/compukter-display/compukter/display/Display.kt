/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.display

/** A side of the computer, relative to its front face. */
public value class DisplaySide internal constructor(internal val index: Int) {
    init {
        require(index in 0..5)
    }

    public fun open(): TextDisplay = TextDisplay(DisplayBindings.acquireSide(index))
}

/** A handle to one exact display block. Coordinates are zero-based. */
public value class TextDisplay internal constructor(private val handle: Int) {
    /** Writes within one row of the display's 20 by 10 character grid. */
    public fun writeAt(x: Int, y: Int, text: String) {
        DisplayBindings.writeAt(handle, x, y, text)
    }

    public fun clear() {
        DisplayBindings.clear(handle)
    }
}

public object Display {
    public fun open(name: String): TextDisplay = TextDisplay(DisplayBindings.acquireNamed(name))

    public val front: DisplaySide
        get() = DisplaySide(0)

    public val back: DisplaySide
        get() = DisplaySide(1)

    public val left: DisplaySide
        get() = DisplaySide(2)

    public val right: DisplaySide
        get() = DisplaySide(3)

    public val top: DisplaySide
        get() = DisplaySide(4)

    public val bottom: DisplaySide
        get() = DisplaySide(5)
}

private object DisplayBindings {
    external fun acquireSide(side: Int): Int

    external fun acquireNamed(name: String): Int

    external fun writeAt(handle: Int, x: Int, y: Int, text: String)

    external fun clear(handle: Int)
}
