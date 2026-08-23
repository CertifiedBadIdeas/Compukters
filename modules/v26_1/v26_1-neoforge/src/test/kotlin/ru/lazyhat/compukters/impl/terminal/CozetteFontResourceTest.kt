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
 */

package ru.lazyhat.compukters.impl.terminal

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CozetteFontResourceTest {
    @Test
    fun `committed Cozette resources match the terminal font contract`() {
        val root = repositoryRoot()
        val resources = root.resolve("modules/v26_1/v26_1-neoforge/src/main/resources")
        val jsonPath = resources.resolve("assets/compukters/font/terminal/cozette.json")
        val pngPath = resources.resolve("assets/compukters/textures/font/terminal/cozette.png")
        val json = jsonPath.readText()
        val provider =
            JsonParser
                .parseString(json)
                .asJsonObject
                .getAsJsonArray("providers")
                .single()
                .asJsonObject
        val characterRows = provider.getAsJsonArray("chars").map { it.asString }
        val codePoints = characterRows.flatMap { it.codePoints().boxed().toList() }.filterNot { it == 0 }.toSet()
        val image = pngPath.inputStream().use(ImageIO::read)

        assertEquals("bitmap", provider.get("type").asString)
        assertEquals("compukters:font/terminal/cozette.png", provider.get("file").asString)
        assertEquals(13, provider.get("height").asInt)
        assertEquals(10, provider.get("ascent").asInt)
        assertEquals(characterRows.first().codePointCount(0, characterRows.first().length) * 6, image.width)
        assertEquals(characterRows.size * 13, image.height)
        assertTrue(
            listOf('A', 'z', '0', 'Ё', 'ё', 'Ж', 'я', '←', '↑', '→', '↓', '─', '│', '┼', '█')
                .map(Char::code)
                .plus(0xFFFD)
                .all(codePoints::contains),
        )
        assertFalse(json.contains("minecraft:uniform"))
        assertFalse(json.contains("minecraft:default"))
    }

    @Test
    fun `pinned upstream BDF has the reviewed digest`() {
        val source = repositoryRoot().resolve("tools/fonts/cozette/v.1.30.0/cozette.bdf")
        val digest = source.inputStream().use { MessageDigest.getInstance("SHA-256").digest(it.readAllBytes()) }

        assertEquals(
            "8d740166af3a14053773ac7a8846bf288c8818a44f73ab28d4725d91b03a5639",
            digest.joinToString(separator = "") { "%02x".format(it) },
        )
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Could not locate repository root")
}
