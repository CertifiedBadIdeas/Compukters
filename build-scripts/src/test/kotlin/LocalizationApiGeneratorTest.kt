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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalizationApiGeneratorTest {
    @Test
    fun generatesSplitApisForPlainStrings() {
        val rendered =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukters.common.ui.dsl",
            ).generate(
                mapOf(
                    "gui.compukters.terminal.connecting" to "Connecting...",
                ),
            )

        val keys = rendered.getValue("CompukterKeys.kt")
        val values = rendered.getValue("CompukterTranslatable.kt")
        val components = rendered.getValue("CompukterComponents.kt")

        assertTrue(keys.contains("object CompukterKeys"))
        assertTrue(keys.contains("object Gui"))
        assertTrue(keys.contains("const val CONNECTING = \"gui.compukters.terminal.connecting\""))

        assertTrue(values.contains("object CompukterTranslatable"))
        assertTrue(values.contains("val connecting: Value<String>"))
        assertTrue(values.contains("translatable(CompukterKeys.Gui.Terminal.CONNECTING)"))

        assertTrue(components.contains("object CompukterComponents"))
        assertTrue(components.contains("val connecting: MutableComponent"))
        assertTrue(components.contains("Component.translatable(CompukterKeys.Gui.Terminal.CONNECTING)"))
    }

    @Test
    fun skipsTranslatableForParameterizedEntriesAndGeneratesComponentFactory() {
        val rendered =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukters.common.ui.dsl",
            ).generate(
                mapOf(
                    "gui.compukters.tooltip.computer_id" to "Computer ID: %s",
                ),
            )

        val values = rendered.getValue("CompukterTranslatable.kt")
        val components = rendered.getValue("CompukterComponents.kt")

        assertFalse(values.contains("computerId"))
        assertTrue(components.contains("fun computerId(vararg args: Any): MutableComponent"))
        assertTrue(components.contains("Component.translatable(CompukterKeys.Gui.Tooltip.COMPUTER_ID, *args)"))
    }

    @Test
    fun preservesFirstSegmentForNonModidPrefixedKeys() {
        val rendered =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukters.common.ui.dsl",
            ).generate(
                mapOf(
                    "itemGroup.compukters" to "Compukters",
                ),
            )

        val keys = rendered.getValue("CompukterKeys.kt")

        assertTrue(keys.contains("object ItemGroup"))
        assertTrue(keys.contains("const val COMPUKTERS = \"itemGroup.compukters\""))
    }

    @Test
    fun failsWhenComponentNamesCollapseInsideOneObject() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                LocalizationApiGenerator(
                    packageName = "ru.lazyhat.compukters.common.ui.dsl",
                ).generate(
                    mapOf(
                        "gui.compukters.terminal.foo-bar" to "A",
                        "gui.compukters.terminal.foo_bar" to "B",
                    ),
                )
            }

        assertTrue((error.message ?: "").contains("foo-bar"))
        assertTrue((error.message ?: "").contains("foo_bar"))
    }
}
