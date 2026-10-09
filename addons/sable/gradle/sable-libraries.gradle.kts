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

import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

// Resolve pinned embedded artifacts before Loom assembles its dev classpath, including on clean builds.
abstract class SableEmbeddedLibraries : TransformAction<SableEmbeddedLibraries.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input val kind: Property<String>
    }
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val kind = parameters.kind.get()
        var count = 0
        ZipFile(artifact.get().asFile).use { sable ->
            val entries = sable.entries().asSequence().filter { it.name.startsWith("META-INF/jarjar/") && it.name.endsWith(".jar") }
            entries.forEach { entry ->
                val bytes = sable.getInputStream(entry).use { it.readBytes() }
                if (kind == "mods" || (kind == "companion" && entry.name.contains("sable-companion-common"))) {
                    outputs.file(entry.name.substringAfterLast('/')).writeBytes(bytes)
                    count++
                }
                if (kind == "libraries" && entry.name.substringAfterLast('/').startsWith("veil-")) {
                    ZipInputStream(ByteArrayInputStream(bytes)).use { veil ->
                        while (true) {
                            val nested = veil.nextEntry ?: break
                            if (nested.name.startsWith("META-INF/jarjar/") && nested.name.endsWith(".jar")) {
                                outputs.file(nested.name.substringAfterLast('/')).writeBytes(veil.readBytes())
                                count++
                            }
                        }
                    }
                }
            }
        }
        check(count == when (kind) { "companion" -> 1; "mods" -> 3; "libraries" -> 2; else -> error("unknown library kind") }) {
            "unexpected embedded library layout in pinned Sable 2.0.6: $kind ($count)"
        }
    }
}

mapOf("sableCompanion" to "companion", "sableNestedMods" to "mods", "sableRuntimeLibraries" to "libraries").forEach { (name, kind) ->
    val artifactType = "compukters-sable-$kind"
    dependencies.registerTransform(SableEmbeddedLibraries::class.java) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType)
        parameters.kind.set(kind)
    }
    val configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType)
    }
    dependencies.add(configuration.name, "maven.modrinth:sable:fg9dTRz9")
}
