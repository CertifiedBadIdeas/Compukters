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

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val workspaceCleanSkippedDirectories =
    setOf(
        ".git",
        ".gradle",
        ".gradle-sandbox",
        ".idea",
        ".toolchain",
    )

fun workspaceCleanTargets(repositoryRoot: Path): List<Path> {
    val root = repositoryRoot.toAbsolutePath().normalize()
    require(Files.isDirectory(root)) { "workspace root is not a directory: $root" }
    val targets = mutableListOf<Path>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                if (directory != root && shouldSkip(directory)) return FileVisitResult.SKIP_SUBTREE
                if (directory != root && isBuildOutput(directory)) {
                    targets.add(directory)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exception: java.io.IOException,
            ): FileVisitResult =
                if (exception is NoSuchFileException) {
                    FileVisitResult.CONTINUE
                } else {
                    throw exception
                }
        },
    )
    return targets.sortedBy(Path::toString)
}

private fun shouldSkip(directory: Path): Boolean =
    directory.fileName.toString() in workspaceCleanSkippedDirectories || Files.exists(directory.resolve(".git"))

private fun isBuildOutput(directory: Path): Boolean {
    val parent = directory.parent ?: return false
    return when (directory.fileName.toString()) {
        "build" -> Files.isRegularFile(parent.resolve("build.gradle.kts")) || Files.isRegularFile(parent.resolve("build.gradle"))
        "target" -> Files.isRegularFile(parent.resolve("Cargo.toml"))
        else -> false
    }
}
