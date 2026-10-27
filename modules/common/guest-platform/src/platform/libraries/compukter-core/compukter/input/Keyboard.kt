/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.input

/** Stable bit positions in keyboard capability ABI 1. Escape always releases capture. */
public enum class Key {
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    SPACE,
    ENTER,
    TAB,
    BACKSPACE,
    SHIFT,
    CONTROL,
    ALT,
    UP,
    DOWN,
    LEFT,
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
    DELETE,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
}

/** One observation; press/release edges coalesce since the preceding snapshot. */
public value class KeyboardState(
    public val gameTick: Long,
    public val connected: Boolean,
    private val held: Long,
    private val pressed: Long,
    private val released: Long,
) {
    public fun isDown(key: Key): Boolean = held and (1L shl key.ordinal) != 0L
    public fun wasPressed(key: Key): Boolean = pressed and (1L shl key.ordinal) != 0L
    public fun wasReleased(key: Key): Boolean = released and (1L shl key.ordinal) != 0L
}

public value class KeyboardCapture internal constructor(private val handle: Int) {
    /** No player is captured until that player explicitly activates the configurator's keyboard mode. */
    public fun snapshot(): KeyboardState {
        val bytes = KeyboardBindings.snapshot(handle)
        if (bytes.size != 34 || bytes[0].toInt() != 1) throw IllegalStateException("Invalid keyboard snapshot")
        return KeyboardState(readLong(bytes, 2), bytes[1].toInt() != 0, readLong(bytes, 10), readLong(bytes, 18), readLong(bytes, 26))
    }

    /** Release this program's keyboard and return the player to normal controls. */
    public fun close() { KeyboardBindings.close(handle) }
}

public object Keyboard {
    /** Request exactly these keys for this computer. One program and one explicitly consenting player at a time. */
    public fun open(keys: Set<Key>): KeyboardCapture {
        var mask = 0L
        for (key in keys) mask = mask or (1L shl key.ordinal)
        if (mask == 0L) throw IllegalArgumentException("Choose at least one captured key")
        return KeyboardCapture(KeyboardBindings.open(mask))
    }
}

private fun readLong(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (index in 0 until 8) result = result or ((bytes[offset + index].toLong() and 255L) shl (index * 8))
    return result
}

private object KeyboardBindings {
    external fun open(mask: Long): Int
    external fun snapshot(handle: Int): ByteArray
    external fun close(handle: Int)
}
