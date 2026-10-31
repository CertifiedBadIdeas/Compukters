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

package ru.lazyhat.compukters.files

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/** Protected direct-entry operations shared by local project storage and compiler snapshots. */
object SecureDirectories {
    fun openRoot(root: Path): SecureDirectoryStream<Path> {
        require(root.isAbsolute && root.normalize() == root.root) { "expected a filesystem root" }
        if (root.fileSystem == FileSystems.getDefault() && root.fileSystem.separator == "\\") {
            return try {
                PinnedDirectoryStream.open(root)
            } catch (failure: LinkageError) {
                throw java.nio.file.FileSystemException(root.toString(), null, "native protected project access is unavailable").also {
                    it.initCause(failure)
                }
            }
        }
        val directory = Files.newDirectoryStream(root)
        if (directory is SecureDirectoryStream<Path>) return directory
        directory.close()
        throw UnsupportedOperationException("filesystem does not support protected project traversal")
    }
}
