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

package ru.lazyhat.kraftui.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class UiDslStandaloneConsumerFixtureTest {
    private val root = locateRoot()

    @Test
    fun standaloneConsumerFixtureUsesDocumentedCompositeBuildCoordinate() {
        val settings = root.resolve("fixtures/ui-dsl-consumer/settings.gradle.kts")
        val build = root.resolve("fixtures/ui-dsl-consumer/build.gradle.kts")
        val docs = root.resolve("docs/ui-dsl-source-consumption.md").readText()

        assertTrue(Files.exists(settings), "standalone UI DSL consumer fixture settings must exist")
        assertTrue(Files.exists(build), "standalone UI DSL consumer fixture build file must exist")

        val settingsSource = settings.readText()
        val buildSource = build.readText()

        assertTrue(settingsSource.contains("includeBuild(\"../..\")"))
        assertTrue(settingsSource.contains("module(\"ru.lazyhat:kraft-ui-dsl\")"))
        assertTrue(settingsSource.contains("project(\":ui-dsl\")"))
        assertTrue(buildSource.contains("implementation(\"ru.lazyhat:kraft-ui-dsl\")"))
        assertTrue(docs.contains("./gradlew-sandbox-dev -p fixtures/ui-dsl-consumer test"))
    }

    private fun locateRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (cursor.parent != null) {
            if (Files.exists(cursor.resolve("settings.gradle.kts")) && Files.isDirectory(cursor.resolve("modules/ui-dsl"))) {
                return cursor
            }
            cursor = cursor.parent
        }
        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }
}
