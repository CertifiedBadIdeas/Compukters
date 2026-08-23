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

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CozetteFontResourceTest {
    @Test
    fun `committed Cozette resources match the terminal font contract`() {
        val json = runtimeResource("/assets/compukters/font/terminal/cozette.json").reader().use { it.readText() }
        val minecraftFontGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val provider =
            minecraftFontGson
                .fromJson(json, JsonObject::class.java)
                .asJsonObject
                .getAsJsonArray("providers")
                .single()
                .asJsonObject
        val characterRows = provider.getAsJsonArray("chars").map { it.asString }
        val codePoints = characterRows.flatMap { it.codePoints().boxed().toList() }.filterNot { it == 0 }.toSet()
        val image = runtimeResource("/assets/compukters/textures/font/terminal/cozette.png").use(ImageIO::read)

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
        assertTrue('"'.code in codePoints)
        assertTrue('\\'.code in codePoints)
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

    private fun runtimeResource(path: String) =
        requireNotNull(CozetteFontResourceTest::class.java.getResourceAsStream(path)) {
            "Missing runtime resource $path"
        }
}
