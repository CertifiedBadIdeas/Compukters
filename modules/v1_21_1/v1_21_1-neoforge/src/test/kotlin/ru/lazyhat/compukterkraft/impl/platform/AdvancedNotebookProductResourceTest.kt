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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvancedNotebookProductResourceTest {
    private val loader = javaClass.classLoader

    @Test
    fun advancedNotebookUsesSharedGeometryWithDedicatedProductTexture() {
        val blockState = resourceText("assets/compukterkraft/blockstates/advanced_notebook.json")
        val blockModel = resourceText("assets/compukterkraft/models/block/advanced_notebook.json")
        val itemModel = resourceText("assets/compukterkraft/models/item/advanced_notebook.json")

        assertTrue(blockState.contains("compukterkraft:block/advanced_notebook"))
        assertFalse(blockModel.contains("\"elements\""))
        assertTrue(blockModel.contains("compukterkraft:block/notebook/advanced_notebook"))
        assertTrue(itemModel.contains("builtin/entity"))
        assertNotNull(loader.getResource("assets/compukterkraft/textures/block/notebook/advanced_notebook.png"))
    }

    @Test
    fun cProgrammingSdkHasARegisteredItemModelAndTexture() {
        val itemModel = resourceText("assets/compukterkraft/models/item/c_programming_sdk.json")

        assertTrue(itemModel.contains("minecraft:item/generated"))
        assertTrue(itemModel.contains("compukterkraft:item/c_programming_sdk"))
        assertNotNull(loader.getResource("assets/compukterkraft/textures/item/c_programming_sdk.png"))
    }

    @Test
    fun advancedNotebookLootAndRecipesUseRegisteredProducts() {
        val loot = resourceText("data/compukterkraft/loot_table/blocks/advanced_notebook.json")
        val advancedRecipe = resourceText("data/compukterkraft/recipe/advanced_notebook.json")
        val sdkRecipe = resourceText("data/compukterkraft/recipe/c_programming_sdk.json")

        assertTrue(loot.contains("\"name\": \"compukterkraft:computer\""))
        assertTrue(loot.contains("compukterkraft:block_named"))
        assertTrue(loot.contains("compukterkraft:has_id"))
        assertTrue(loot.contains("compukterkraft:player_creative"))
        assertTrue(advancedRecipe.contains("compukterkraft:notebook"))
        assertTrue(advancedRecipe.contains("\"id\": \"compukterkraft:advanced_notebook\""))
        assertTrue(sdkRecipe.contains("\"id\": \"compukterkraft:c_programming_sdk\""))
    }

    @Test
    fun productNamesAreLocalizedAndLegacyAdvancedComputerResourcesAreGone() {
        val english = resourceText("assets/compukterkraft/lang/en_us.json")
        val russian = resourceText("assets/compukterkraft/lang/ru_ru.json")

        assertTrue(english.contains("\"block.compukterkraft.advanced_notebook\": \"Advanced Notebook\""))
        assertTrue(english.contains("\"item.compukterkraft.c_programming_sdk\": \"C Programming SDK\""))
        assertTrue(russian.contains("\"block.compukterkraft.advanced_notebook\": \"Продвинутый ноутбук\""))
        assertTrue(russian.contains("\"item.compukterkraft.c_programming_sdk\": \"SDK для программирования на C\""))

        val legacyResources =
            listOf(
                "assets/compukterkraft/blockstates/computer_advanced.json",
                "assets/compukterkraft/models/block/computer_advanced.json",
                "assets/compukterkraft/models/item/computer_advanced.json",
                "data/compukterkraft/loot_table/blocks/computer_advanced.json",
            )
        legacyResources.forEach { path -> assertNull(loader.getResource(path), "$path should be removed") }
        assertFalse(english.contains("computer_advanced"))
        assertFalse(russian.contains("computer_advanced"))
    }

    @Test
    fun advancedTextureKeepsNotebookAtlasDimensions() {
        val normal = assertNotNull(loader.getResourceAsStream(NORMAL_TEXTURE)).use { it.readBytes() }
        val advanced = assertNotNull(loader.getResourceAsStream(ADVANCED_TEXTURE)).use { it.readBytes() }

        assertEquals(pngDimensions(normal), pngDimensions(advanced))
        assertEquals(256 to 256, pngDimensions(advanced))
    }

    private fun resourceText(path: String): String = assertNotNull(loader.getResource(path), path).readText()

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.copyOfRange(1, 4).contentEquals("PNG".encodeToByteArray()))
        fun u32(offset: Int): Int =
            (bytes[offset].toInt() and 0xff shl 24) or
                (bytes[offset + 1].toInt() and 0xff shl 16) or
                (bytes[offset + 2].toInt() and 0xff shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        return u32(16) to u32(20)
    }

    private companion object {
        const val NORMAL_TEXTURE = "assets/compukterkraft/textures/block/notebook/notebook.png"
        const val ADVANCED_TEXTURE = "assets/compukterkraft/textures/block/notebook/advanced_notebook.png"
    }
}
