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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class UiDslSubmoduleBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun uiDslIsConsumedFromRootSubmodule() {
        val rootSettings = root.resolve("settings.gradle.kts").readText()
        val gitmodules = root.resolve(".gitmodules")
        val fixtureSettings = root.resolve("fixtures/ui-dsl-consumer/settings.gradle.kts")
        val fixtureBuild = root.resolve("fixtures/ui-dsl-consumer/build.gradle.kts")
        val docs = root.resolve("docs/ui-dsl-source-consumption.md").readText()

        assertTrue(Files.exists(gitmodules), "Compukter Kraft should track external UI DSL as a git submodule")
        assertTrue(Files.exists(fixtureSettings), "standalone UI DSL consumer fixture settings must exist")
        assertTrue(Files.exists(fixtureBuild), "standalone UI DSL consumer fixture build file must exist")

        val gitmodulesSource = gitmodules.readText()
        val fixtureSettingsSource = fixtureSettings.readText()
        val fixtureBuildSource = fixtureBuild.readText()

        assertTrue(rootSettings.contains("project(\":ui-dsl\").projectDir = rootDir.resolve(\"vendor/ui-dsl\")"))
        assertTrue(gitmodulesSource.contains("[submodule \"vendor/ui-dsl\"]"))
        assertTrue(gitmodulesSource.contains("url = https://github.com/CertifiedBadIdeas/ui-dsl"))
        assertTrue(fixtureSettingsSource.contains("includeBuild(\"../../vendor/ui-dsl\")"))
        assertTrue(fixtureBuildSource.contains("implementation(\"ru.lazyhat:kraft-ui-dsl\")"))
        assertTrue(docs.contains("git submodule update --init vendor/ui-dsl"))
        assertTrue(docs.contains("./gradlew-sandbox-dev -p fixtures/ui-dsl-consumer test"))
    }
}
