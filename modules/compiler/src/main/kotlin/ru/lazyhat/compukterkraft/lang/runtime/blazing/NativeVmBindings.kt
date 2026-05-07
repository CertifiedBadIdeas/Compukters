/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

internal object NativeVmBindings {
    private val lock = Any()
    private var loadedPath: String? = null

    fun createImage(
        libraryPath: String,
        image: ByteArray,
        instructionBudget: Int,
    ): Long {
        load(libraryPath)
        val handle = createImageNative(image, instructionBudget.coerceAtLeast(1))
        check(handle != 0L) { "Native image VM create returned a zero handle" }
        return handle
    }

    fun runImageUntilSignal(handle: Long): ByteArray {
        require(handle != 0L) { "Native image VM handle is zero" }
        return runImageUntilSignalForHandleNative(handle)
    }

    fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    ) {
        require(handle != 0L) { "Native image VM handle is zero" }
        resumeImageWithNative(handle, value)
    }

    fun freeImage(handle: Long) {
        if (handle != 0L) {
            freeImageNative(handle)
        }
    }

    private fun load(libraryPath: String) {
        synchronized(lock) {
            val current = loadedPath
            if (current == libraryPath) {
                return
            }
            require(current == null) {
                "Native VM library already loaded from $current; cannot load $libraryPath in the same JVM"
            }
            System.load(libraryPath)
            loadedPath = libraryPath
        }
    }

    @JvmStatic
    private external fun createImageNative(
        image: ByteArray,
        instructionBudget: Int,
    ): Long

    @JvmStatic
    private external fun runImageUntilSignalForHandleNative(handle: Long): ByteArray

    @JvmStatic
    private external fun resumeImageWithNative(
        handle: Long,
        value: ByteArray,
    )

    @JvmStatic
    private external fun freeImageNative(handle: Long)
}
