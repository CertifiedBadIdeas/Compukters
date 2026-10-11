/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeTextFieldTest {
    private var clipboard = ""

    private fun IdeTextField.key(
        code: Int,
        modifiers: Int = 0,
    ) = keyPressed(IdeKeyInput(code, modifiers), { clipboard }, { clipboard = it })

    private val bounds = IdeRect(0, 0, 100, 22)

    @Test
    fun `selection replacement clipboard and history preserve middle edits`() {
        IdeTextField("hello world", 256).use { field ->
            field.key(IdeKeyCode.HOME)
            repeat(5) { field.key(IdeKeyCode.RIGHT, IdeModifier.SHIFT) }
            field.key(IdeKeyCode.C, IdeModifier.CONTROL)
            assertEquals("hello", clipboard)
            field.type("goodbye")
            assertEquals("goodbye world", field.view().text)
            field.key(IdeKeyCode.Z, IdeModifier.CONTROL)
            assertEquals("hello world", field.view().text)
            field.key(IdeKeyCode.Z, IdeModifier.CONTROL or IdeModifier.SHIFT)
            assertEquals("goodbye world", field.view().text)
            field.key(IdeKeyCode.A, IdeModifier.CONTROL)
            clipboard = "new\nvalue\t!"
            field.key(IdeKeyCode.V, IdeModifier.CONTROL)
            assertEquals("new value !", field.view().text)
            field.key(IdeKeyCode.A, IdeModifier.CONTROL)
            field.key(IdeKeyCode.X, IdeModifier.CONTROL)
            assertEquals("new value !", clipboard)
            assertEquals("", field.view().text)
            field.key(IdeKeyCode.Z, IdeModifier.CONTROL)
            assertEquals("new value !", field.view().text)
        }
    }

    @Test
    fun `Unicode limits replacement and caret never split a code point`() {
        IdeTextField("a😀b", 4).use { field ->
            field.key(IdeKeyCode.LEFT)
            assertEquals(3, field.view().caretUtf16)
            field.key(IdeKeyCode.LEFT, IdeModifier.SHIFT)
            assertEquals(EditorRange(1, 3), field.view().selection)
            field.type("😁")
            assertEquals("a😁b", field.view().text)
            field.key(IdeKeyCode.END)
            field.type("x")
            assertEquals("a😁b", field.view().text)
            field.key(IdeKeyCode.A, IdeModifier.CONTROL)
            field.type("ab😀c")
            assertEquals("ab😀", field.view().text)
        }
        IdeTextField("a".repeat(255), 256).use { field ->
            field.type("😀")
            assertEquals(255, field.view().text.length)
        }
    }

    @Test
    fun `mouse placement and dragging use code point columns`() {
        IdeTextField("a😀bcd", 256).use { field ->
            val layout = field.layout(bounds)
            val cell = IdeCodeFontProfile.DEFAULT.cellWidth
            assertTrue(field.press((layout.textX + cell).toDouble(), 10.0, bounds, false, false))
            assertEquals(1, field.view().caretUtf16)
            field.drag((layout.textX + 3 * cell).toDouble(), bounds)
            assertEquals(EditorRange(1, 4), field.view().selection)
            field.release()
            assertFalse(field.drag(80.0, bounds))
            field.type("X")
            assertEquals("aXcd", field.view().text)
        }
    }

    @Test
    fun `word movement deletion and double click share the editor semantics`() {
        IdeTextField("one two", 256).use { field ->
            field.key(IdeKeyCode.LEFT, IdeModifier.CONTROL)
            assertEquals(4, field.view().caretUtf16)
            field.key(IdeKeyCode.DELETE, IdeModifier.CONTROL)
            assertEquals("one ", field.view().text)
            field.key(IdeKeyCode.BACKSPACE, IdeModifier.CONTROL)
            assertEquals("", field.view().text)
        }
        IdeTextField("😀", 256).use { field ->
            val layout = field.layout(bounds)
            field.press((layout.textX + IdeCodeFontProfile.DEFAULT.cellWidth).toDouble(), 10.0, bounds, false, true)
            assertEquals(EditorRange(0, 2), field.view().selection)
        }
    }

    @Test
    fun `long input follows caret and manual scroll is reset by editing`() {
        IdeTextField("0123456789".repeat(8), 256).use { field ->
            val end = field.layout(bounds)
            assertTrue(end.firstColumn > 0)
            assertNotNull(end.caret)
            field.key(IdeKeyCode.HOME)
            assertEquals(0, field.layout(bounds).firstColumn)
            field.scroll(12)
            val scrolled = field.layout(bounds)
            assertEquals(12, scrolled.firstColumn)
            assertNull(scrolled.caret)
            field.key(IdeKeyCode.RIGHT)
            assertNotNull(field.layout(bounds).caret)
            val layout = field.layout(bounds)
            field.press(layout.textX.toDouble(), 10.0, bounds, false, false)
            repeat(20) { field.drag(150.0, bounds) }
            assertTrue(field.view().caretUtf16 > layout.columns)
            assertNotNull(field.layout(bounds).caret)
        }
    }

    @Test
    fun `masked fields draw only bullets and cannot copy or cut tokens`() {
        clipboard = "existing"
        IdeTextField("secret😀", 4096, masked = true).use { field ->
            field.key(IdeKeyCode.A, IdeModifier.CONTROL)
            field.key(IdeKeyCode.C, IdeModifier.CONTROL)
            field.key(IdeKeyCode.X, IdeModifier.CONTROL)
            assertEquals("existing", clipboard)
            assertEquals("secret😀", field.view().text)
            val draw = IdeTextFields.draw(field.view(), bounds, 100)
            assertEquals("•".repeat(7), draw.text.value)
            assertFalse(field.view().toString().contains("secret"))
            assertTrue(draw.fills.any { it.kind == IdeFillKind.Selection })
            assertTrue(draw.fills.all { it.bounds.left >= bounds.left && it.bounds.right <= bounds.right })
        }
    }
}
