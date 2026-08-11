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

plugins {
    id("kotlin-convention")
}

// project.version is already set by the loader-specific convention plugin
// (e.g. neoforge-convention -> computeModArchiveVersion). Reuse it here so the
// mod_version placeholder in mods.toml / fabric.mod.json matches the jar version.
val modVersion = project.version.toString()

val modProperties =
    readVersionedModProperties()
        .toMutableMap()
        .apply {
            this["mod_version"] = modVersion
        }.toMap()

base.archivesName = modProperties.getValue("mod_id").replace(" ", "")

val generateCklResourceIndexes =
    tasks.register("generateCklResourceIndexes") {
        val resourcesRoot = layout.projectDirectory.dir("src/main/resources")
        val outputRoot = layout.buildDirectory.dir("generated/ckl-resource-indexes")
        val indexedRoots = listOf("rom")

        inputs.files(
            indexedRoots.map { rootName -> fileTree(resourcesRoot.dir(rootName)) },
        )
        outputs.dir(outputRoot)

        doLast {
            delete(outputRoot)
            for (rootName in indexedRoots) {
                val sourceRoot = resourcesRoot.dir(rootName).asFile
                val files =
                    if (sourceRoot.isDirectory) {
                        fileTree(sourceRoot)
                            .files
                            .filter { it.extension == "ck" }
                            .map {
                                sourceRoot
                                    .toPath()
                                    .relativize(it.toPath())
                                    .toString()
                                    .replace(File.separatorChar, '/')
                            }.sorted()
                    } else {
                        emptyList()
                    }
                val indexFile = outputRoot.get().file("$rootName/$rootName.index").asFile
                indexFile.parentFile.mkdirs()
                indexFile.writeText(files.joinToString(separator = "\n", postfix = if (files.isEmpty()) "" else "\n"))
            }
        }
    }

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val replaceProperties = modProperties.toMap()
        val from = file("src/main/resources")
        val intoDir = file("build/generated/resources")

        inputs.properties(replaceProperties)
        inputs.dir(from)

        outputs.dir(intoDir)

        from(from) {
            exclude("rom/rom.index", "firmware/firmware.index")
            exclude { element ->
                element.name.contains(".png") ||
                    element.name.endsWith(".ck")
            }
            expand(replaceProperties)
        }
        from(from) {
            include("**/*.ck")
        }
        from(generateCklResourceIndexes)

        into(intoDir)
    }

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    dependsOn(generateModMetadata)
    dependsOn(generateCklResourceIndexes)
}

sourceSets.main {
    resources.srcDir(generateModMetadata.get().outputs.files)
}
