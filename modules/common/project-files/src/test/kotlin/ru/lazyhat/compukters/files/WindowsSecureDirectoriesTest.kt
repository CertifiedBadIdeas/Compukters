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

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            (root as ProjectDirectoryStream).atomicReplace(Path.of("replacement.tmp"), Path.of("renamed.kt"))
            root.newByteChannel(Path.of("renamed.kt"), mutableSetOf(StandardOpenOption.READ)).use {
                val buffer = ByteBuffer.allocate(32)
                it.read(buffer)
                assertEquals("updated", String(buffer.array(), 0, buffer.position()))
            }
            root.deleteFile(Path.of("renamed.kt"))
        }
        Files.delete(path)
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface ReparseEditor : StdCallLibrary {
        fun CreateFileW(
            name: WString,
            access: Int,
            sharing: Int,
            security: Pointer?,
            disposition: Int,
            flags: Int,
            template: Pointer?,
        ): Pointer?

        fun DeviceIoControl(
            handle: Pointer,
            code: Int,
            input: Pointer,
            inputBytes: Int,
            output: Pointer?,
            outputBytes: Int,
            returned: IntByReference,
            overlapped: Pointer?,
        ): Int

        fun CloseHandle(handle: Pointer): Int
    }

    @Test
    fun `an opened directory cannot be redirected by installing a junction during an operation`() {
        val path = createTempDirectory("compukters-reparse-race-")
        val outside = createTempDirectory("compukters-reparse-outside-")
        Files.writeString(outside.resolve("secret.kt"), "outside")
        val editor = Native.load("kernel32", ReparseEditor::class.java)
        open(path).use { directory ->
            val handle = editor.CreateFileW(WString(path.toString()), 0x40000000, 7, null, 3, 0x02200000, null)
            check(handle != null && Pointer.nativeValue(handle) != -1L) { "cannot prepare reparse mutation: ${Native.getLastError()}" }
            val substitute = ("\\??\\" + outside.toString()).toByteArray(Charsets.UTF_16LE)
            val display = outside.toString().toByteArray(Charsets.UTF_16LE)
            try {
                Memory(16L + substitute.size + 2 + display.size + 2).use { data ->
                    data.clear()
                    data.setInt(0, 0xa0000003.toInt())
                    data.setShort(4, (data.size() - 8).toShort())
                    data.setShort(10, substitute.size.toShort())
                    data.setShort(12, (substitute.size + 2).toShort())
                    data.setShort(14, display.size.toShort())
                    data.write(16, substitute, 0, substitute.size)
                    data.write(16L + substitute.size + 2, display, 0, display.size)
                    assertEquals(
                        1,
                        editor.DeviceIoControl(handle, 0x900a4, data, data.size().toInt(), null, 0, IntByReference(), null),
                        "reparse setup failed: ${Native.getLastError()}",
                    )
                }
                assertFailsWith<java.nio.file.FileSystemException> {
                    directory.newByteChannel(Path.of("secret.kt"), mutableSetOf(StandardOpenOption.READ)).close()
                }
                assertFailsWith<java.nio.file.FileSystemException> {
                    directory.any { it.fileName.toString() == "secret.kt" }
                }
                assertFailsWith<java.nio.file.FileSystemException> {
                    directory
                        .newByteChannel(
                            Path.of("bounded.kt"),
                            mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                        ).close()
                }
                assertFalse(Files.exists(outside.resolve("bounded.kt")))
                assertEquals("outside", Files.readString(outside.resolve("secret.kt")))
            } finally {
                editor.CloseHandle(handle)
            }
        }
        Files.delete(path)
        Files.delete(outside.resolve("secret.kt"))
        Files.delete(outside)
    }

    @Test
    fun `native file channels retain byte positions force and truncation across large buffers`() {
        val path = createTempDirectory("compukters-native-channel-")
        val bytes = ByteArray(150000) { (it % 251).toByte() }
        open(path).use { directory ->
            val options = mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            directory.newByteChannel(Path.of("bytes.kt"), options).use { channel ->
                val source = ByteBuffer.wrap(bytes)
                while (source.hasRemaining()) channel.write(source)
                assertEquals(bytes.size.toLong(), channel.position())
                channel.truncate(100001)
                assertEquals(100001L, channel.size())
                assertEquals(100001L, channel.position())
                (channel as java.nio.channels.FileChannel).force(true)
            }
            directory.newByteChannel(Path.of("bytes.kt"), mutableSetOf(StandardOpenOption.READ)).use { channel ->
                val target = ByteBuffer.allocate(100001)
                while (target.hasRemaining()) check(channel.read(target) > 0)
                assertContentEquals(bytes.copyOf(100001), target.array())
                assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
                channel.position(1234)
                val middle = ByteBuffer.allocate(23)
                channel.read(middle)
                assertContentEquals(bytes.copyOfRange(1234, 1257), middle.array())
            }
            directory.deleteFile(Path.of("bytes.kt"))
        }
        Files.delete(path)
    }

    @Test
    fun `handle relative mutations work across independently opened directories`() {
        val path = createTempDirectory("compukters-relative-moves-")
        open(path).use { root ->
            (root as ProjectDirectoryStream).createDirectory(Path.of("left"))
            root.createDirectory(Path.of("right"))
            root.newDirectoryStream(Path.of("left"), LinkOption.NOFOLLOW_LINKS).use { left ->
                root.newDirectoryStream(Path.of("right"), LinkOption.NOFOLLOW_LINKS).use { right ->
                    left.newByteChannel(Path.of("test.kt"), mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).use {
                        it.write(ByteBuffer.wrap("cross-parent".toByteArray()))
                        (it as java.nio.channels.FileChannel).force(true)
                    }
                    left.move(Path.of("test.kt"), right, Path.of("moved.kt"))
                    assertEquals("cross-parent", Files.readString(path.resolve("right/moved.kt")))
                    right.deleteFile(Path.of("moved.kt"))
                }
            }
            root.deleteDirectory(Path.of("left"))
            root.deleteDirectory(Path.of("right"))
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
