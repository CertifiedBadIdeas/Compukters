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

import java.util.zip.ZipFile
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

// Loom dev runs need the bundled mods on their classpath before loader discovery.
abstract class AeronauticsEmbeddedMods : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val expected = setOf(
            "dev.eriksonn.aeronautics.aeronautics-neoforge-1.21.1-1.3.2.jar",
            "dev.ryanhcode.offroad.offroad-neoforge-1.21.1-1.3.2.jar",
            "dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.2.jar",
        )
        ZipFile(artifact.get().asFile).use { bundle ->
            val entries = bundle.entries().asSequence()
                .filter { it.name.startsWith("META-INF/jarjar/") && it.name.endsWith(".jar") }.toList()
            check(entries.map { it.name.substringAfterLast('/') }.toSet() == expected) {
                "unexpected embedded mod layout in pinned Aeronautics 1.3.2"
            }
            entries.forEach { entry ->
                bundle.getInputStream(entry).use { input ->
                    outputs.file(entry.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

val embeddedType = "compukters-aeronautics-mods"
dependencies.registerTransform(AeronauticsEmbeddedMods::class.java) {
    from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
    to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, embeddedType)
}
val embeddedMods = configurations.create("aeronauticsNestedMods") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, embeddedType)
}
dependencies.add(embeddedMods.name, "maven.modrinth:create-aeronautics:44pLdPGg")
