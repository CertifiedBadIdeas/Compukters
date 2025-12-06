// SPDX-FileCopyrightText: 2025 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import org.lwjgl.glfw.GLFW

/**
 * Supports for converting/translating key codes.
 */
object KeyConverter {
    /**
     * GLFW's key events refer to the physical key code, rather than the "actual" key code (with keyboard layout
     * applied).
     *
     *
     * This makes sense for WASD-style input, but is a right pain for keyboard shortcuts — this function attempts to
     * translate those keys back to their "actual" key code. See also
     * [ this discussion on GLFW's GitHub.](https://github.com/glfw/glfw/issues/1502)
     *
     * @param key      The current key code.
     * @param scanCode The current scan code.
     * @return The translated key code.
     */
    fun physicalToActual(
        key: Int,
        scanCode: Int,
    ): Int {
        val name = GLFW.glfwGetKeyName(key, scanCode)
        if (name == null || name.length != 1) return key

        // If we've got a single character as the key name, treat that as the ASCII value of the key,
        // and map that back to a key code.
        val character = name[0]

        // 0-9 and A-Z map directly to their GLFW key (they're the same ASCII code).
        if ((character in '0'..'9') || (character in 'A'..'Z')) return character.code
        // a-z map to GLFW_KEY_{A,Z}
        if (character in 'a'..'z') return GLFW.GLFW_KEY_A + (character.code - 'a'.code)

        return key
    }
}
