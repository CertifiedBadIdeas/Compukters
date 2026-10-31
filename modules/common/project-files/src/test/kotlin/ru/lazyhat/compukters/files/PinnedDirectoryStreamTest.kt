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

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.ByteBuffer
import java.nio.file.ClosedDirectoryStreamException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PinnedDirectoryStreamTest {
    private class RecordingPins : EntryPinning {
        val held = mutableMapOf<Path, Int>()

        override fun open(path: Path): EntryPin {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            held[path] = (held[path] ?: 0) + 1
            return object : EntryPin {
                override val directory = attrs.isDirectory
                override val reparse = attrs.isSymbolicLink

                override fun close() {
                    val remaining = held.getValue(path) - 1
                    if (remaining == 0) held.remove(path) else held[path] = remaining
                }
            }
        }
    }

    @Test
    fun `child retains ancestor pins after the parent stream closes`() {
        val path = createTempDirectory("project-pins-")
        val childPath = Files.createDirectory(path.resolve("child"))
        Files.writeString(childPath.resolve("main.kt"), "fun main() {}")
        val pins = RecordingPins()
        val parent = PinnedDirectoryStream.open(path, pins)
        val child = parent.newDirectoryStream(Path.of("child"), LinkOption.NOFOLLOW_LINKS)
        parent.close()
        assertEquals(setOf(path, childPath), pins.held.keys)
        child.newByteChannel(Path.of("main.kt"), mutableSetOf(StandardOpenOption.READ)).use { channel ->
            val bytes = ByteBuffer.allocate(32)
            channel.read(bytes)
            assertEquals("fun main() {}", String(bytes.array(), 0, bytes.position()))
        }
        child.close()
        child.close()
        assertTrue(pins.held.isEmpty())
        assertFailsWith<ClosedDirectoryStreamException> { child.iterator() }
    }

    @Test
    fun `exclusive creation rename attributes enumeration and deletion retain data`() {
        val path = createTempDirectory("project-entries-")
        val pins = RecordingPins()
        PinnedDirectoryStream.open(path, pins).use { root ->
            root.newByteChannel(Path.of("first.kt"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).use {
                it.write(ByteBuffer.wrap("hello".toByteArray()))
            }
            assertFailsWith<Exception> {
                root.newByteChannel(Path.of("first.kt"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).close()
            }
            root.move(Path.of("first.kt"), root, Path.of("last.kt"))
            assertEquals(5L, root.getFileAttributeView(Path.of("last.kt"), BasicFileAttributeView::class.java)!!.readAttributes().size())
            assertEquals(listOf("last.kt"), root.map { it.fileName.toString() })
            root.deleteFile(Path.of("last.kt"))
            val directory = Files.createDirectory(path.resolve("empty"))
            root.deleteDirectory(directory.fileName)
        }
        assertTrue(pins.held.isEmpty())
        assertEquals(0L, Files.list(path).use { it.count() })
    }

    @Test
    fun `failed child opens and attribute operations release temporary pins`() {
        val path = createTempDirectory("project-failed-pins-")
        Files.writeString(path.resolve("file"), "value")
        val pins = RecordingPins()
        val root = PinnedDirectoryStream.open(path, pins)
        assertFailsWith<Exception> { root.newDirectoryStream(Path.of("absent")) }
        assertFailsWith<Exception> { root.newDirectoryStream(Path.of("file")) }
        assertEquals(mapOf(path to 1), pins.held)
        val view = root.getFileAttributeView(BasicFileAttributeView::class.java)!!
        root.close()
        assertTrue(pins.held.isEmpty())
        assertFailsWith<ClosedDirectoryStreamException> { view.readAttributes() }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `linked entries cannot be traversed or read but cleanup preserves their target`() {
        val path = createTempDirectory("project-link-cleanup-")
        val outside = createTempDirectory("project-link-outside-")
        Files.writeString(outside.resolve("keep"), "outside")
        Files.createSymbolicLink(path.resolve("link"), outside)
        val pins = RecordingPins()
        PinnedDirectoryStream.open(path, pins).use { root ->
            val attrs = root.getFileAttributeView(Path.of("link"), BasicFileAttributeView::class.java)!!.readAttributes()
            assertTrue(attrs.isOther)
            assertEquals(false, attrs.isDirectory)
            assertFailsWith<Exception> { root.newDirectoryStream(Path.of("link")).close() }
            assertFailsWith<Exception> { root.newByteChannel(Path.of("link"), mutableSetOf(StandardOpenOption.READ)).close() }
            root.deleteFile(Path.of("link"))
        }
        assertTrue(pins.held.isEmpty())
        assertEquals("outside", Files.readString(outside.resolve("keep")))
    }

    @Test
    fun `Windows aliases traversal streams and destructive channel modes are refused`() {
        val path = createTempDirectory("project-entry-admission-")
        val pins = RecordingPins()
        PinnedDirectoryStream.open(path, pins).use { root ->
            for (name in listOf(
                "..",
                ".",
                "a/b",
                "a\\b",
                "a:stream",
                "file.",
                "file ",
                "NUL",
                "con.kt",
                "con .kt",
                "COM0.log",
                "COM1.txt",
                "LPT²",
                "CONOUT$",
            )) {
                assertFailsWith<IllegalArgumentException>(name) {
                    root.newByteChannel(Path.of(name), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).close()
                }
            }
            Files.writeString(path.resolve("keep"), "original")
            assertFailsWith<UnsupportedOperationException> {
                root.newByteChannel(Path.of("keep"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)).close()
            }
            assertEquals("original", Files.readString(path.resolve("keep")))
        }
        assertTrue(pins.held.isEmpty())
    }
}
