/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.display

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** A handle to one exact display block. Coordinates are zero-based. */
public value class TextDisplay internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<TextDisplay>("compukter:text_display") {
        override fun wrap(handle: Int): TextDisplay = TextDisplay(handle)
    }

    /** Writes within one row of the display's 20 by 10 character grid. */
    public fun writeAt(x: Int, y: Int, text: String) {
        DisplayBindings.writeAt(handle, x, y, text)
    }

    public fun clear() {
        DisplayBindings.clear(handle)
    }
}

private object DisplayBindings {
    external fun acquireSide(side: Int): Int

    external fun acquireNamed(name: String): Int

    external fun writeAt(handle: Int, x: Int, y: Int, text: String)

    external fun clear(handle: Int)
}
