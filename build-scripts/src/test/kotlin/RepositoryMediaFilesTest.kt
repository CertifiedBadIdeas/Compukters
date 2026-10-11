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


import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RepositoryMediaFilesTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun observesAssetsAddedAfterTheTreeWasConfigured() {
        val root = temporaryDirectory.toFile()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        root.resolve(".git").writeText("gitdir: elsewhere")
        val tree = project.repositoryMediaFiles(root)
        assertEquals(emptySet<String>(), tree.files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())

        root.resolve("new.PNG").writeText("asset")
        assertEquals(setOf("new.PNG"), tree.files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())
    }

    @Test
    fun ignoresGeneratedDirectoriesAndNestedRepositories() {
        val root = temporaryDirectory.toFile()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        listOf(
            "models/owned.png",
            "build/generated.png",
            "module/build/generated.png",
            ".gradle-test/cache.png",
            "docs/guest-api/image.png",
            "vendor/image.png",
        ).forEach { name ->
            root.resolve(name).apply {
                parentFile.mkdirs()
                writeText("asset")
            }
        }
        root.resolve("vendor/.git").writeText("gitdir: elsewhere")
        root.resolve("models/source.kt").writeText("source")

        val paths = project.repositoryMediaFiles(root).files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("models/owned.png"), paths)
    }
}
