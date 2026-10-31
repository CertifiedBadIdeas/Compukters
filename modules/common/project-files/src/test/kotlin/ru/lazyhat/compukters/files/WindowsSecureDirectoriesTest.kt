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

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** These tests must execute on Windows; a Linux skip is not Windows acceptance evidence. */
@EnabledOnOs(OS.WINDOWS)
class WindowsSecureDirectoriesTest {
    private fun open(path: Path): SecureDirectoryStream<Path> {
        var current = SecureDirectories.openRoot(path.root)
        try {
            for (component in path.root.relativize(path)) {
                val child = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                current.close()
                current = child
            }
            return current
        } catch (failure: Throwable) {
            current.close()
            throw failure
        }
    }

    @Test
    fun `Unicode project files support read create rename atomic replacement and deletion`() {
        val path = createTempDirectory("compukters-проект-")
        open(path).use { root ->
            root.newByteChannel(Path.of("main.kt"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).use {
                it.write(ByteBuffer.wrap("fun main() {}".toByteArray()))
            }
            root.move(Path.of("main.kt"), root, Path.of("renamed.kt"))
            Files.writeString(path.resolve("replacement.tmp"), "updated")
            Files.move(
                path.resolve("replacement.tmp"),
                path.resolve("renamed.kt"),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            root.newByteChannel(Path.of("renamed.kt"), mutableSetOf(StandardOpenOption.READ)).use {
                val buffer = ByteBuffer.allocate(32)
                it.read(buffer)
                assertEquals("updated", String(buffer.array(), 0, buffer.position()))
            }
            root.deleteFile(Path.of("renamed.kt"))
        }
        Files.delete(path)
    }

    @Test
    fun `an open child prevents ancestor substitution until its final close`() {
        val parent = createTempDirectory("compukters-pinned-parent-")
        val child = Files.createDirectory(parent.resolve("child"))
        val moved = parent.resolveSibling(parent.fileName.toString() + "-moved")
        open(child).use {
            assertFailsWith<Exception> { Files.move(parent, moved) }
            assertEquals(true, Files.isDirectory(child))
        }
        Files.move(parent, moved)
        Files.delete(moved.resolve("child"))
        Files.delete(moved)
    }

    @Test
    fun `native file identity follows the object and distinguishes a replacement root`() {
        val parent = createTempDirectory("compukters-identity-")
        val original = Files.createDirectory(parent.resolve("project"))
        val originalKey = open(original).use { it.getFileAttributeView(BasicFileAttributeView::class.java)!!.readAttributes().fileKey() }
        assertNotNull(originalKey)
        val moved = Files.move(original, parent.resolve("moved"))
        assertEquals(
            originalKey,
            open(moved).use { it.getFileAttributeView(BasicFileAttributeView::class.java)!!.readAttributes().fileKey() },
        )
        Files.createDirectory(original)
        val replacementKey = open(original).use { it.getFileAttributeView(BasicFileAttributeView::class.java)!!.readAttributes().fileKey() }
        assertNotNull(replacementKey)
        assertNotEquals(originalKey, replacementKey)
        Files.delete(original)
        Files.delete(moved)
        Files.delete(parent)
    }

    @Test
    fun `junction source roots and intermediate directories cannot escape the project`() {
        val parent = createTempDirectory("compukters-junction-")
        val outside = createTempDirectory("compukters-outside-")
        Files.writeString(outside.resolve("secret.kt"), "outside")
        val junction = parent.resolve("src")
        val process =
            ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                junction.toString(),
                outside.toString(),
            ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        try {
            assertFailsWith<Exception> { open(junction).close() }
            open(parent).use { root ->
                assertFailsWith<Exception> { root.newDirectoryStream(Path.of("src"), LinkOption.NOFOLLOW_LINKS).close() }
            }
            assertEquals("outside", Files.readString(outside.resolve("secret.kt")))
        } finally {
            Files.delete(junction)
            Files.delete(parent)
            Files.delete(outside.resolve("secret.kt"))
            Files.delete(outside)
        }
    }
}
