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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeVmBindingsImageOnlyTest {
    @Test
    fun nativeBindingsExposeOnlyImageVmLifecycle() {
        val methodNames =
            NativeVmBindings::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("createImage" in methodNames)
        assertTrue("runImageUntilSignal" in methodNames)
        assertTrue("resumeImageWith" in methodNames)
        assertTrue("freeImage" in methodNames)

        assertFalse("runUntilSignal" in methodNames)
        assertFalse("create" in methodNames)
        assertFalse("resumeWith" in methodNames)
        assertFalse("free" in methodNames)
        assertFalse(("runUntilSignal" + "Native") in methodNames)
        assertFalse(("create" + "Native") in methodNames)
        assertFalse(("resumeWith" + "Native") in methodNames)
        assertFalse(("free" + "Native") in methodNames)
    }
}
