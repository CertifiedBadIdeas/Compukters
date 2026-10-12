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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeripheralConstantInsertionTest {
    private fun apply(
        source: String,
        name: String,
    ): String {
        val edit = checkNotNull(PeripheralConstantInsertion.plan(source, name))
        return source.substring(0, edit.offset) + edit.text + source.substring(edit.offset)
    }

    @Test
    fun `declaration documentation stays with declaration and multiline file annotations stay above imports`() {
        val header = "@file:Suppress(\n    \"Unused\",\n)\n\nimport foo.Bar\n\n"
        val body = "/** Entry point */\nfun main() {}"
        assertEquals(header + "const val SPEED = \"speed\"\n\n" + body, apply(header + body, "speed"))
    }

    @Test
    fun `insert below imports preserving header and source`() {
        val header = "/* License */\n\npackage demo\n\nimport create.kinetics.Speedometer\n\n"
        val body = "const val SCREEN = \"scada\"\n\nfun main() {}\n"
        assertEquals(header + "const val DRIVE_SPEED = \"drive_speed\"\n" + body, apply(header + body, "drive_speed"))
    }

    @Test
    fun `existing constant reused and identifier collisions suffixed`() {
        val source = "const val DRIVE_SPEED = \"other\"\nfun main() {}"
        val inserted = apply(source, "drive-speed")
        assertTrue(inserted.startsWith("const val DRIVE_SPEED_2 = \"drive-speed\""))
        assertNull(PeripheralConstantInsertion.plan(inserted, "drive-speed"))
        assertNull(PeripheralConstantInsertion.plan("const val SPEED: String = \"drive_speed\"", "drive_speed"))
    }

    @Test
    fun `comments and strings cannot impersonate imports or constants`() {
        val source =
            "/*\nimport fake.Type\nconst val DRIVE_SPEED = \"drive_speed\"\n*/\n" +
                "fun main() { val text = \"\"\"\nconst val DRIVE_SPEED = \"drive_speed\"\n\"\"\" }"
        val inserted = apply(source, "drive_speed")
        assertEquals(
            source.substringBefore("fun main") + "const val DRIVE_SPEED = \"drive_speed\"\n\n" +
                source.substring(source.indexOf("fun main")),
            inserted,
        )
        assertTrue(inserted.startsWith("/*\nimport fake.Type"))
    }

    @Test
    fun `preserve CRLF and undo insertion as one edit`() {
        val source = "import foo.Bar\r\n\r\nfun main() {}"
        val edit = checkNotNull(PeripheralConstantInsertion.plan(source, "drive_speed"))
        val document = EditorDocument(source)
        document.setCaret(source.length)
        document.replaceAll(apply(source, "drive_speed"), source.length + edit.text.length)
        assertEquals(source.length + edit.text.length, document.caretOffset)
        assertTrue(document.materialize().contains("\r\nconst val DRIVE_SPEED = \"drive_speed\"\r\n\r\nfun"))
        document.undo()
        assertEquals(source, document.materialize())
        assertEquals(source.length, document.caretOffset)
    }

    @Test
    fun `escape literals and sanitize unusual names`() {
        val edit = checkNotNull(PeripheralConstantInsertion.plan("", "123 dollar$\"\\\n"))
        assertEquals("PERIPHERAL_123_DOLLAR", edit.identifier)
        assertTrue(edit.text.contains("\\$\\\"\\\\\\n"))
    }
}
