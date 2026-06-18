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

package ru.lazyhat.compukterkraft.common.ui.dsl

import net.minecraft.network.chat.contents.TranslatableContents
import ru.lazyhat.compukterkraft.common.localization.CompukterComponents
import ru.lazyhat.compukterkraft.common.localization.CompukterKeys
import ru.lazyhat.compukterkraft.common.localization.CompukterTranslatable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompukterLangGenerationSmokeTest {
    @Test
    fun generatedLocalizationApisAreAvailableToCommonCode() {
        assertEquals(
            "gui.compukterkraft.terminal.connecting",
            CompukterKeys.Gui.Terminal.CONNECTING,
        )
        assertTrue(
            CompukterTranslatable.Gui.Terminal.connecting.value
                .isNotBlank(),
        )
        assertEquals(
            "gui.compukterkraft.terminal.connecting",
            (CompukterComponents.Gui.Terminal.connecting.contents as TranslatableContents).key,
        )
        assertEquals(
            "gui.compukterkraft.tooltip.computer_id",
            (CompukterComponents.Gui.Tooltip.computerId("42").contents as TranslatableContents).key,
        )
        assertEquals(
            "itemGroup.compukterkraft",
            CompukterKeys.ItemGroup.COMPUKTERKRAFT,
        )
    }
}
