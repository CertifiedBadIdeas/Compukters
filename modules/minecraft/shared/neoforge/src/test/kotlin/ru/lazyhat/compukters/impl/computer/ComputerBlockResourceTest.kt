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

package ru.lazyhat.compukters.impl.computer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputerBlockResourceTest {
    @Test
    fun `blue workbench textures are restored byte for byte`() {
        EXPECTED_TEXTURE_HASHES.forEach { (name, expectedHash) ->
            val bytes = resourceBytes("/assets/compukters/textures/block/compukter/$name.png")
            val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

            assertEquals(16, image.width, name)
            assertEquals(16, image.height, name)
            assertEquals(expectedHash, bytes.sha256(), name)
        }
    }

    @Test
    fun `model assigns distinct front back and side textures`() {
        val model = resourceJson("/assets/compukters/models/block/compukter.json")
        val textures = model.getAsJsonObject("textures")
        assertEquals("compukters:block/compukter/front", textures["front"].asString)
        assertEquals("compukters:block/compukter/back", textures["back"].asString)
        assertEquals("compukters:block/compukter/side", textures["side"].asString)

        val faces = model.getAsJsonArray("elements")[0].asJsonObject.getAsJsonObject("faces")
        assertEquals("#front", faces.textureFor("north"))
        assertEquals("#back", faces.textureFor("south"))
        listOf("east", "west", "up", "down").forEach { face ->
            assertEquals("#side", faces.textureFor(face), face)
        }
    }

    @Test
    fun `blockstate rotates the front for every horizontal facing`() {
        val variants =
            resourceJson("/assets/compukters/blockstates/compukter.json")
                .getAsJsonObject("variants")

        mapOf("north" to 0, "east" to 90, "south" to 180, "west" to 270).forEach { (facing, rotation) ->
            val variant = variants.getAsJsonObject("facing=$facing")
            assertEquals("compukters:block/compukter", variant["model"].asString, facing)
            assertEquals(rotation, variant["y"].asInt, facing)
        }
    }

    @Test
    fun `creative tab has a localized title`() {
        val translations = resourceJson("/assets/compukters/lang/en_us.json")

        assertEquals("Compukters", translations["itemGroup.compukters"].asString)
    }

    @Test
    fun `peripheral cable resources describe one passive block`() {
        val model = resourceJson("/assets/compukters/models/block/peripheral_cable.json")
        assertEquals("compukters:block/compukter/side", model.getAsJsonObject("textures")["cable"].asString)
        assertEquals(7, model.getAsJsonArray("elements").size())
        assertEquals(
            listOf(5, 5, 5) to listOf(11, 11, 11),
            model.getAsJsonArray("elements")[0].asJsonObject.bounds(),
        )

        val multipart = resourceJson("/assets/compukters/blockstates/peripheral_cable.json").getAsJsonArray("multipart")
        assertEquals(7, multipart.size())
        assertEquals(
            "compukters:block/peripheral_cable_center",
            multipart[0].asJsonObject.getAsJsonObject("apply")["model"].asString,
        )
        val expectedArms =
            linkedMapOf(
                "down" to (listOf(5, 0, 5) to listOf(11, 5, 11)),
                "up" to (listOf(5, 11, 5) to listOf(11, 16, 11)),
                "north" to (listOf(5, 5, 0) to listOf(11, 11, 5)),
                "south" to (listOf(5, 5, 11) to listOf(11, 11, 16)),
                "west" to (listOf(0, 5, 5) to listOf(5, 11, 11)),
                "east" to (listOf(11, 5, 5) to listOf(16, 11, 11)),
            )
        expectedArms.entries.forEachIndexed { index, (direction, expectedBounds) ->
            val part = multipart[index + 1].asJsonObject
            assertEquals("true", part.getAsJsonObject("when")[direction].asString, direction)
            assertEquals(
                "compukters:block/peripheral_cable_$direction",
                part.getAsJsonObject("apply")["model"].asString,
                direction,
            )
            val arm = resourceJson("/assets/compukters/models/block/peripheral_cable_$direction.json")
            assertEquals("compukters:block/peripheral_cable_component", arm["parent"].asString, direction)
            assertEquals(1, arm.getAsJsonArray("elements").size(), direction)
            assertEquals(expectedBounds, arm.getAsJsonArray("elements")[0].asJsonObject.bounds(), direction)
        }

        val loot = resourceJson("/data/compukters/loot_table/blocks/peripheral_cable.json")
        val entry =
            loot
                .getAsJsonArray("pools")[0]
                .asJsonObject
                .getAsJsonArray("entries")[0]
                .asJsonObject
        assertEquals("compukters:peripheral_cable", entry["name"].asString)

        val translations = resourceJson("/assets/compukters/lang/en_us.json")
        assertEquals("Peripheral Cable", translations["block.compukters.peripheral_cable"].asString)
    }

    @Test
    fun `peripheral configurator has an item model and instructions`() {
        val model = resourceJson("/assets/compukters/models/item/peripheral_configurator.json")
        assertEquals("minecraft:item/generated", model["parent"].asString)
        assertEquals("compukters:block/compukter/front", model.getAsJsonObject("textures")["layer0"].asString)

        val translations = resourceJson("/assets/compukters/lang/en_us.json")
        assertEquals("Peripheral Configurator", translations["item.compukters.peripheral_configurator"].asString)
        assertNotNull(translations["screen.compukters.peripheral_configurator.name"])
        assertNotNull(translations["item.compukters.peripheral_configurator.cleared"])
    }

    private fun resourceBytes(path: String): ByteArray = assertNotNull(javaClass.getResourceAsStream(path), path).use { it.readAllBytes() }

    private fun resourceJson(path: String): JsonObject =
        resourceBytes(path).inputStream().reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonObject.textureFor(face: String): String = getAsJsonObject(face)["texture"].asString

    private fun JsonObject.bounds(): Pair<List<Int>, List<Int>> =
        getAsJsonArray("from").map { it.asInt } to getAsJsonArray("to").map { it.asInt }

    companion object {
        private val EXPECTED_TEXTURE_HASHES =
            mapOf(
                "front" to "5c3f69e98125aef9809647c73ae59d1fb25e8891c94cd0e366881519b37785a0",
                "side" to "0881e37bb6164825e7355b5b1304cb2837762fa7b9c45340bbf3b927369b7c49",
                "back" to "8d4d20e411022ead50de036fcf15f59cc1661e10fdcd889a5d487cc5279e6fc2",
            )
    }
}
