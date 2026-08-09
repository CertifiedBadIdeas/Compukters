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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedNotebookAndCSdkRegistryArchitectureTest {
    @Test
    fun registryOwnsBothNotebookFamiliesAndTheVersionedCSdkItem() {
        val source = Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt").readText()

        assertTrue(source.contains("const val ADVANCED_NOTEBOOK = \"advanced_notebook\""))
        assertTrue(source.contains("const val C_PROGRAMMING_SDK = \"c_programming_sdk\""))
        assertTrue(source.contains("val ADVANCED_NOTEBOOK: DeferredHolder<Block, NotebookBlock>"))
        assertTrue(source.contains("DeviceFamily.ADVANCED"))
        assertTrue(source.contains("val C_PROGRAMMING_SDK: DeferredHolder<Item, CProgrammingSdkItem>"))
        assertTrue(source.contains("DataComponents.SDK_ARTIFACT_IDENTITY.get()"))
        assertTrue(source.contains("C_PROGRAMMING_SDK_ARTIFACT_IDENTITY"))
        assertTrue(source.contains("Blocks.ADVANCED_NOTEBOOK.get()"))
        assertTrue(source.contains("out.accept(Items.ADVANCED_NOTEBOOK.get().defaultInstance)"))
        assertTrue(source.contains("out.accept(Items.C_PROGRAMMING_SDK.get().defaultInstance)"))
    }

    @Test
    fun bothNotebookBlocksShareOneBlockEntityTypeAndRetainedPaths() {
        val registry = Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt").readText()
        val blockEntity =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/impl/notebook/block/NeoForgeNotebookBlockEntity.kt")
                .readText()

        assertTrue(registry.contains("Blocks.NOTEBOOK.get(),"))
        assertTrue(registry.contains("Blocks.ADVANCED_NOTEBOOK.get(),"))
        assertTrue(registry.contains("NeoForgeNotebookBlockEntity(NOTEBOOK.get(), pos, state)"))
        assertFalse(registry.contains("ADVANCED_NOTEBOOK_BLOCK_ENTITY"))
        assertTrue(blockEntity.contains("NotebookBlockEntity(type, pos, state)"))
    }
}
