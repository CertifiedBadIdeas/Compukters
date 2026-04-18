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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationParityResourceTest {
    @Test
    fun allLocalizationsContainSameKeysAsEnglish() {
        val langRoot =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/lang"),
            )
        val langDirectory = Paths.get(langRoot.toURI())
        val englishKeys = localizationKeys(langDirectory.resolve("en_us.json"))

        Files.list(langDirectory).use { localePaths ->
            val locales = localePaths.filter { it.fileName.toString().endsWith(".json") }.toList()
            val localeNames = locales.map { it.fileName.toString() }

            assertTrue(localeNames.contains("ru_ru.json"), "Expected Russian localization resource to exist")

            locales
                .filter { it.fileName.toString() != "en_us.json" }
                .forEach { localePath ->
                    val localizedKeys = localizationKeys(localePath)
                    assertEquals(
                        englishKeys,
                        localizedKeys,
                        "Expected ${localePath.fileName} to contain the same keys as en_us.json",
                    )
                }
        }
    }

    private fun localizationKeys(localePath: Path): Set<String> =
        KEY_PATTERN.findAll(localePath.readText()).map { it.groupValues[1] }.toSet()

    private companion object {
        val KEY_PATTERN = Regex("\"([^\"]+)\"\\s*:")
    }
}
