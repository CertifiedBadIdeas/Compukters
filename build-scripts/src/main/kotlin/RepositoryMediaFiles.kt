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


import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import java.io.File
import java.io.Serializable
import java.util.Locale

private class MediaAssetPaths : Spec<FileTreeElement>, Serializable {
    override fun isSatisfiedBy(element: FileTreeElement): Boolean =
        element.isDirectory || element.file.extension.lowercase(Locale.ROOT) in MediaLicenseInventory.extensions
}

private class ExcludedMediaDirectories(private val root: File) : Spec<FileTreeElement>, Serializable {
    override fun isSatisfiedBy(element: FileTreeElement): Boolean {
        if (!element.isDirectory || element.file == root) return false
        val directory = element.file
        return directory.name in
            setOf(
                ".git", ".gradle", ".gradle-sandbox", ".agents", ".idea", ".toolchain",
                "build", "out", "target", "run",
            ) ||
            directory.name.startsWith(".gradle-") ||
            element.relativePath.pathString == "docs/guest-api" ||
            directory.resolve(".git").exists()
    }
}

// Preserve the tree itself in Configuration Cache so newly added assets become task inputs on replay.
fun Project.repositoryMediaFiles(root: File): FileTree =
    fileTree(root) {
        include(MediaAssetPaths())
        exclude(ExcludedMediaDirectories(root))
    }
