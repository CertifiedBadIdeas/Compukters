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

val repositoryRoot = rootProject.projectDir
val inventoryFile = rootProject.file("licenses/media-assets.tsv")
val excludedDirectoryNames =
    setOf(
        ".git",
        ".gradle",
        ".gradle-sandbox",
        ".agents",
        ".idea",
        ".toolchain",
        "build",
        "out",
        "target",
        "run",
    )

fun discoverRepositoryMedia(): List<File> =
    repositoryRoot
        .walkTopDown()
        .onEnter { directory ->
            directory == repositoryRoot ||
                (directory.name !in excludedDirectoryNames &&
                    !directory.name.startsWith(".gradle-") &&
                    !directory.resolve(".git").exists())
        }.filter(File::isFile)
        .filter { file -> file.extension.lowercase() in MediaLicenseInventory.extensions }
        .sortedBy { file -> file.relativeTo(repositoryRoot).invariantSeparatorsPath }
        .toList()

val repositoryMedia = providers.provider(::discoverRepositoryMedia)
val mediaProvenance =
    providers.provider {
        if (!inventoryFile.isFile) {
            emptyList()
        } else {
            MediaLicenseInventory
                .parse(inventoryFile.readText())
                .map { record -> repositoryRoot.resolve(record.provenance) }
                .distinct()
        }
    }

val verifyMediaLicenseInventory =
    tasks.register("verifyMediaLicenseInventory") {
        description = "Verifies that every repository media asset has complete licensing metadata."
        group = "verification"
        inputs.file(inventoryFile)
        inputs.files(repositoryMedia)
        inputs.files(mediaProvenance)

        doLast {
            check(inventoryFile.isFile) {
                "media license inventory is missing: ${inventoryFile.relativeTo(repositoryRoot)}"
            }
            val records = MediaLicenseInventory.parse(inventoryFile.readText())
            val discoveredPaths =
                discoverRepositoryMedia()
                    .mapTo(mutableSetOf()) { file -> file.relativeTo(repositoryRoot).invariantSeparatorsPath }
            val referencedPaths = records.flatMap { record -> listOf(record.path, record.provenance) }.toSet()
            val existingPaths =
                referencedPaths
                    .filterTo(mutableSetOf()) { path -> repositoryRoot.resolve(path).isFile }

            MediaLicenseInventory.verify(records, discoveredPaths, existingPaths)
            logger.lifecycle("Verified ${records.size} classified media assets.")
        }
    }

tasks.named("check") {
    dependsOn(verifyMediaLicenseInventory)
}

tasks.matching { it.name == "verifyLocalFast" }.configureEach {
    dependsOn(verifyMediaLicenseInventory)
}
