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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class GenerateLocalizationApiTaskTest {
    @Test
    fun removesSourcesFromPreviousPackage(@TempDir tempDir: Path) {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val task = project.tasks.create("generateLocalizationApi", GenerateLocalizationApiTask::class.java)
        val langFile = tempDir.resolve("en_us.json")
        val outputDirectory = tempDir.resolve("generated")
        val staleSource = outputDirectory.resolve("ru/lazyhat/old/Obsolete.kt")
        staleSource.parent.createDirectories()
        staleSource.writeText("package ru.lazyhat.old")
        langFile.writeText("""{"itemGroup.compukters":"Compukters"}""")

        task.langFile.set(langFile.toFile())
        task.packageName.set("ru.lazyhat.compukters.common.localization")
        task.outputDirectory.set(outputDirectory.toFile())

        task.generateSources()

        assertFalse(staleSource.exists())
        assertTrue(
            outputDirectory
                .resolve("ru/lazyhat/compukters/common/localization/CompukterKeys.kt")
                .exists(),
        )
    }
}
