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

package ru.lazyhat.compukters.lang.runtime.integration

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreOpenException
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreOpenFailure
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.JniBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmCheckpointException
import ru.lazyhat.compukters.lang.runtime.vm.VmCheckpointFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JniCheckpointIntegrationTest {
    @Test
    fun `snapshot restores exact retired work and host bytes after store reopen`() {
        val root = Files.createTempDirectory("compukters-checkpoint-").toRealPath()
        try {
            val bridge = JniBridge.open(Path.of(requiredProperty("compukter.jni.library")))
            val id = ComputerId.fromLongs(71, 72)
            val rom = bootRom(Files.readAllBytes(Path.of(requiredProperty("compukters.shell.artifact"))))
            val host = byteArrayOf(0, 127, -128, -1)
            val before =
                WorldFileSystemStore.open(root, bridge).use { store ->
                    VmSession.bootInStore(store, id, rom).use { session ->
                        session.advanceWithRetirementLimit(64, 64, 64, 1)
                        val resources = session.resourceSnapshot()
                        session.checkpoint(store, id, host)
                        assertEquals(
                            VmCheckpointFailure.INCOMPATIBLE,
                            assertFailsWith<VmCheckpointException> { VmSession.restoreInStore(store, id, rom, boot = false) }.failure,
                        )
                        resources
                    }
                }
            WorldFileSystemStore.open(root, bridge).use { store ->
                val restored = requireNotNull(VmSession.restoreInStore(store, id, rom))
                restored.session.use { session ->
                    assertEquals(before, session.resourceSnapshot())
                    assertContentEquals(host, restored.hostStateBytes())
                    restored.hostStateBytes().fill(3)
                    assertContentEquals(host, restored.hostStateBytes())
                    store.discardCheckpoint(id)
                    assertNull(VmSession.restoreInStore(store, id, rom))
                    val result = session.advanceWithRetirementLimit(64, 64, 64, 1)
                    assertEquals(1L, result.retiredInstructions)
                    assertEquals(before.executedInstructions + 1, session.resourceSnapshot().executedInstructions)
                    session.checkpoint(store, id, host)
                    val checkpoint =
                        root
                            .resolve(
                                "computers",
                            ).resolve(id.toByteArray().joinToString("") { "%02x".format(it) })
                            .resolve("execution")
                    val corrupted = Files.readAllBytes(checkpoint)
                    corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
                    Files.write(checkpoint, corrupted)
                    assertEquals(
                        VmCheckpointFailure.CORRUPT,
                        assertFailsWith<VmCheckpointException> { VmSession.restoreInStore(store, id, rom) }.failure,
                    )
                    store.discardCheckpoint(id)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }

    private fun emptyRom(): ByteArray {
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .array()
        return header + MessageDigest.getInstance("SHA-256").digest(header)
    }

    private fun bootRom(artifact: ByteArray): ByteArray {
        val path = "/rom/boot".encodeToByteArray()
        val unsigned =
            ByteBuffer
                .allocate(16 + Int.SIZE_BYTES + path.size + 1 + 1 + Short.SIZE_BYTES + Long.SIZE_BYTES + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(1)
                .putInt(path.size)
                .put(path)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0.toShort())
                .putLong(artifact.size.toLong())
                .put(artifact)
                .array()
        return unsigned + MessageDigest.getInstance("SHA-256").digest(unsigned)
    }
}
