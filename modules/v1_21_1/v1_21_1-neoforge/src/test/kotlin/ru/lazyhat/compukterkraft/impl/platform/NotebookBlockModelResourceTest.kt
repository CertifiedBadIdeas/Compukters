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

import kotlin.test.Test
import kotlin.test.assertTrue

class NotebookBlockModelResourceTest {
    @Test
    fun notebookUsesGeckoLibModelAssetsInsteadOfVanillaCuboidGeometry() {
        val blockModel =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/models/block/notebook.json"),
            ).readText()
        val geo =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/geo/notebook.geo.json"),
            ) {
                "Notebook should ship a GeckoLib geometry resource."
            }.readText()
        val animation =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/animations/notebook.animation.json"),
            ) {
                "Notebook should ship a GeckoLib animation resource."
            }.readText()
        val itemModel =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/models/item/notebook.json"),
            ).readText()

        assertTrue(
            !blockModel.contains(""""elements""""),
            "Notebook block model should not keep vanilla cuboid geometry once GeckoLib renders the block entity.",
        )
        assertTrue(
            blockModel.contains(""""particle": "compukterkraft:block/notebook/notebook""""),
            "Notebook still needs a particle texture for block breaking particles.",
        )
        assertTrue(
            geo.contains(""""identifier": "geometry.notebook""""),
            "Notebook geometry should use the exported GeckoLib model identifier.",
        )
        assertTrue(
            geo.contains(""""name": "screen""""),
            "Notebook geometry should keep the animated screen bone.",
        )
        assertTrue(
            animation.contains(""""open""""),
            "Notebook animation resource should define the open lid animation.",
        )
        assertTrue(
            javaClass.classLoader.getResource("assets/compukterkraft/textures/block/notebook/notebook.png") != null,
            "Notebook should ship the Blockbench texture used by the GeckoLib model.",
        )
        assertTrue(
            itemModel.contains("minecraft:builtin/entity"),
            "Notebook item should use a built-in entity model so GeckoLib can provide the item renderer.",
        )
    }
}
