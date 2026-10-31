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

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PinnedDirectoryStreamTest {
    @Test
    fun `Windows admission rejects traversal streams devices and trailing aliases`() {
        for (name in listOf(
            ".",
            "..",
            "a/b",
            "a\\b",
            "a:stream",
            "CON",
            "con.txt",
            "CON .kt",
            "COM0",
            "LPT¹.kt",
            "aux",
            "name.",
            "name ",
        )) {
            assertFailsWith<IllegalArgumentException>(name) { PinnedDirectoryStream.validateName(Path.of(name)) }
        }
        for (name in listOf("main.kt", "проект.kt", ".compukter-save.tmp", "normal")) PinnedDirectoryStream.validateName(Path.of(name))
    }

    @Test
    fun `default factory retains independent children and supports direct file operations`() {
        val path = createTempDirectory("project-default-stream-")
        var current = SecureDirectories.openRoot(path.root)
        try {
            for (name in path.root.relativize(path)) {
                val child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                current.close()
                current = child
            }
            current.newByteChannel(Path.of("first.kt"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).use {
                it.write(ByteBuffer.wrap("data".toByteArray()))
            }
            current.move(Path.of("first.kt"), current, Path.of("last.kt"))
            assertEquals(listOf("last.kt"), current.map { it.fileName.toString() })
            current.deleteFile(Path.of("last.kt"))
        } finally {
            current.close()
            Files.delete(path)
        }
    }
}
