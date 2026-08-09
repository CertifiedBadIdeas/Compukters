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

package ru.lazyhat.compukterkraft.impl.platform

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NotebookBlockOcclusionArchitectureTest {
    @Test
    fun notebookUsesNonOccludingPropertiesBecauseItsModelIsNotAFullCube() {
        val source =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt")
                .readText()

        assertTrue(
            source.contains("private fun notebookProperties(): BlockBehaviour.Properties ="),
            "Notebook needs explicit non-occluding properties instead of reusing full-cube computer properties.",
        )
        assertTrue(
            source.contains("notebookProperties(): BlockBehaviour.Properties = noRedstoneConductor().noOcclusion()"),
            "Notebook model is partial-height, so the placed block must not occlude adjacent block faces.",
        )
        assertTrue(
            source.contains("NotebookBlock(notebookProperties().mapColor(MapColor.METAL), DeviceFamily.NORMAL)"),
            "Notebook registration should use the non-occluding property factory.",
        )
    }
}
