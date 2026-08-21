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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateLocalizationApiTask : DefaultTask() {
    @get:InputFile
    abstract val langFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generateSources() {
        outputDirectory.get().asFile.deleteRecursively()

        val entries = parseLangEntries(langFile.get().asFile.readText())
        val renderedFiles = LocalizationApiGenerator(packageName.get()).generate(entries)
        val relativePackagePath = packageName.get().replace('.', '/')

        renderedFiles.forEach { (fileName, source) ->
            val outputFile = outputDirectory.file("$relativePackagePath/$fileName").get().asFile
            outputFile.parentFile.mkdirs()
            outputFile.writeText(source)
        }
    }

    private fun parseLangEntries(json: String): Map<String, String> =
        ENTRY_PATTERN.findAll(json).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }

    private companion object {
        val ENTRY_PATTERN = Regex("\"((?:\\\\.|[^\"])*)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
    }
}
