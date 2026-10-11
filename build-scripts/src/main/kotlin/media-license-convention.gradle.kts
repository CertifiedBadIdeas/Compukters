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
val repositoryMedia = repositoryMediaFiles(repositoryRoot)
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

        val inventory = inventoryFile
        val root = repositoryRoot
        val mediaFiles = objects.fileCollection().from(repositoryMedia)
        doLast {
            check(inventory.isFile) {
                "media license inventory is missing: ${inventory.relativeTo(root)}"
            }
            val records = MediaLicenseInventory.parse(inventory.readText())
            val discoveredPaths =
                mediaFiles.files
                    .mapTo(mutableSetOf()) { file -> file.relativeTo(root).invariantSeparatorsPath }
            val referencedPaths = records.flatMap { record -> listOf(record.path, record.provenance) }.toSet()
            val existingPaths =
                referencedPaths
                    .filterTo(mutableSetOf()) { path -> root.resolve(path).isFile }

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
