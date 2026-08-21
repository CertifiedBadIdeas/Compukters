/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukters.common.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.core.platform.api.PlatformInputProvider

object MinecraftInputProvider : PlatformInputProvider {
    override fun getClipboardString(): String = Minecraft.getInstance().keyboardHandler.clipboard

    override fun isPasteShortcut(keyCode: Int): Boolean = Screen.isPaste(keyCode)

    override fun getPhysicalKeyName(
        keyCode: Int,
        scanCode: Int,
    ): String? = GLFW.glfwGetKeyName(keyCode, scanCode)
}
