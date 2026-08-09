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
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NotebookGeckoLibArchitectureTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun neoforgeModuleDeclaresGeckoLibForNotebookRenderer() {
        val versions = root.resolve("gradle/libs.versions.toml").readText()
        val build = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()

        assertTrue(
            versions.contains("geckolib-neoforge-v1211"),
            "Version catalog should expose the 1.21.1 NeoForge GeckoLib artifact.",
        )
        assertTrue(
            build.contains("libs.geckolib.neoforge.v1211"),
            "NeoForge module should depend on GeckoLib for the notebook block entity renderer.",
        )
    }

    @Test
    fun notebookRendererIsRegisteredOnTheClientEventBus() {
        val clientRegistry = root
            .resolve("modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ForgeClientRegistry.kt")
            .readText()
        val renderer = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookBlockEntityRenderer.kt",
            )
            .readText()
        val model = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookGeoModel.kt",
            )
            .readText()
        val itemModel = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookItemGeoModel.kt",
            )
            .readText()
        val block = root
            .resolve(
                "modules/v1_21_1/v1_21_1-common/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlock.kt",
            )
            .readText()
        val blockEntity = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/notebook/block/NeoForgeNotebookBlockEntity.kt",
            )
            .readText()
        val item = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/notebook/item/NeoForgeNotebookItem.kt",
            )
            .readText()

        assertTrue(
            clientRegistry.contains("RegisterRenderers"),
            "Client registry should listen for entity renderer registration.",
        )
        assertTrue(
            clientRegistry.contains("NotebookBlockEntityRenderer"),
            "Notebook block entity renderer should be registered for the notebook block entity type.",
        )
        assertTrue(
            renderer.contains("GeoBlockRenderer<NeoForgeNotebookBlockEntity>"),
            "Notebook renderer should be a GeckoLib block renderer over the NeoForge notebook block entity.",
        )
        assertTrue(
            block.contains("RenderShape.ENTITYBLOCK_ANIMATED"),
            "Notebook block must use entity-block render shape so vanilla blockstate geometry is not rendered.",
        )
        assertTrue(
            model.contains("geo/notebook.geo.json") &&
                model.contains("textures/block/notebook/notebook.png") &&
                model.contains("textures/block/notebook/advanced_notebook.png") &&
                model.contains("DeviceFamily.NORMAL") &&
                model.contains("DeviceFamily.ADVANCED") &&
                model.contains("DeviceFamily.COMMAND") &&
                model.contains("animations/notebook.animation.json"),
            "Notebook block model should select a dedicated texture for every supported family and reject COMMAND.",
        )
        assertTrue(
            itemModel.contains("geo/notebook.geo.json") &&
                itemModel.contains("notebookTexture(animatable.deviceFamily)") &&
                itemModel.contains("animations/notebook.animation.json"),
            "Notebook item model should use the same strict family texture selection as the block renderer.",
        )
        assertTrue(
            blockEntity.contains("GeoBlockEntity") &&
                blockEntity.contains("AnimationController") &&
                blockEntity.contains("""thenPlay("open").thenLoop("opened")""") &&
                blockEntity.contains("""thenPlay("close").thenLoop("closed")""") &&
                blockEntity.contains("triggerableAnim") &&
                blockEntity.contains("triggerAnim"),
            "NeoForge notebook block entity should animate the lid through GeckoLib triggerable animations.",
        )
        assertTrue(
            item.contains("GeoItem") &&
                item.contains("NotebookItemRenderer") &&
                item.contains("""thenLoop("closed")""") &&
                !item.contains("""thenLoop("opened")"""),
            "NeoForge notebook item should default to the closed GeckoLib animation.",
        )
    }
}
