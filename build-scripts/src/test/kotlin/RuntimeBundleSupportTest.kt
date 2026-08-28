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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class RuntimeBundleSupportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun validatesAndStagesTheExactLinuxAndWindowsRuntimePair() {
        val fixture = Fixture(temporary)

        val staged = RuntimeBundleSupport.validateAndStage(fixture.bundles, fixture.staging, fixture.contract)

        assertEquals(
            listOf(
                "META-INF/natives/linux/x86_64/libcompukter_ffi.so",
                "META-INF/natives/windows/x86_64/compukter_ffi.dll",
            ),
            staged.map { it.resourcePath },
        )
        assertArrayEquals(Fixture.LINUX_NATIVE, fixture.staging.resolve(staged[0].resourcePath).readBytes())
        assertArrayEquals(Fixture.WINDOWS_NATIVE, fixture.staging.resolve(staged[1].resourcePath).readBytes())
    }

    @Test
    fun rejectsWrongOuterChecksumBeforeStagingAnything() {
        val fixture = Fixture(temporary)
        fixture.checksums.writeText(fixture.checksums.toFile().readText().replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)))

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(fixture.bundles, fixture.staging, fixture.contract)
        }
        assertEquals(false, Files.exists(fixture.staging))
    }

    @Test
    fun rejectsManifestIdentityAndUnexpectedArchiveEntries() {
        val wrongCommit = Fixture(temporary.resolve("wrong-commit"), vmCommit = "1".repeat(40))
        val extraEntry = Fixture(temporary.resolve("extra-entry"), extraWindowsEntry = "../escape.dll")

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(wrongCommit.bundles, wrongCommit.staging, Fixture.CONTRACT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(extraEntry.bundles, extraEntry.staging, extraEntry.contract)
        }
    }

    @Test
    fun rejectsMissingInputsAndEmptyNativePayloads() {
        val missing = Fixture(temporary.resolve("missing"))
        val empty = Fixture(temporary.resolve("empty"), linuxNative = byteArrayOf())
        Files.delete(missing.checksums)

        assertThrows(Exception::class.java) {
            RuntimeBundleSupport.validateAndStage(missing.bundles, missing.staging, missing.contract)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(empty.bundles, empty.staging, empty.contract)
        }
    }

    private class Fixture(
        root: Path,
        vmCommit: String = COMMIT,
        extraWindowsEntry: String? = null,
        linuxNative: ByteArray = LINUX_NATIVE,
    ) {
        val bundles: Path = root.resolve("bundles")
        val staging: Path = root.resolve("staging")
        val contract: RuntimeBundleContract = CONTRACT.copy(vmCommit = COMMIT)
        val checksums: Path = bundles.resolve("compukter-runtime-5.0-checksums.sha256")

        init {
            Files.createDirectories(bundles)
            val linux = bundles.resolve("compukter-runtime-5.0-linux-x86_64.tar.gz")
            val windows = bundles.resolve("compukter-runtime-5.0-windows-x86_64.zip")
            writeTar(linux, entries("x86_64-unknown-linux-gnu", "libcompukter_ffi.so", linuxNative, vmCommit))
            writeZip(
                windows,
                entries("x86_64-pc-windows-msvc", "compukter_ffi.dll", WINDOWS_NATIVE, vmCommit) +
                    listOfNotNull(extraWindowsEntry?.let { it to byteArrayOf(1) }),
            )
            checksums.writeText(
                "${sha256(linux.readBytes())}  ${linux.fileName}\n" +
                    "${sha256(windows.readBytes())}  ${windows.fileName}\n",
            )
        }

        private fun entries(target: String, filename: String, native: ByteArray, vmCommit: String): List<Pair<String, ByteArray>> =
            listOf(
                "native/$filename" to native,
                "manifest.json" to manifest(target, filename, native, vmCommit).encodeToByteArray(),
                "LICENSE.txt" to "Apache-2.0\n".encodeToByteArray(),
                "NOTICE.txt" to "Compukter Runtime\n".encodeToByteArray(),
            )

        private fun manifest(target: String, filename: String, native: ByteArray, vmCommit: String): String =
            """
            {
              "schema": 1,
              "runtime_version": "5.0",
              "release_tag": "runtime-v5.0",
              "vm_commit": "$vmCommit",
              "ffi_abi": 5,
              "formats": {
                "artifact": 2,
                "compilation-request": 1,
                "executable-revision": 1,
                "filesystem-generation": 1
              },
              "rustc": "rustc 1.98.0",
              "target": "$target",
              "filename": "$filename",
              "size": ${native.size},
              "sha256": "${sha256(native)}",
              "profile": "release"
            }
            """.trimIndent() + "\n"

        private fun writeTar(path: Path, entries: List<Pair<String, ByteArray>>) {
            Files.newOutputStream(path).use { output ->
                GzipCompressorOutputStream(output).use { gzip ->
                    TarArchiveOutputStream(gzip).use { tar ->
                        entries.forEach { (name, bytes) ->
                            val entry = TarArchiveEntry(name).apply {
                                size = bytes.size.toLong()
                                mode = 0b110100100
                            }
                            tar.putArchiveEntry(entry)
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }

        private fun writeZip(path: Path, entries: List<Pair<String, ByteArray>>) {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        companion object {
            const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
            val LINUX_NATIVE = "linux-native".encodeToByteArray()
            val WINDOWS_NATIVE = "windows-native".encodeToByteArray()
            val CONTRACT =
                RuntimeBundleContract(
                    runtimeVersion = "5.0",
                    releaseTag = "runtime-v5.0",
                    ffiAbi = 5,
                    vmCommit = COMMIT,
                    formats =
                        sortedMapOf(
                            "artifact" to 2,
                            "compilation-request" to 1,
                            "executable-revision" to 1,
                            "filesystem-generation" to 1,
                        ),
                )
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
