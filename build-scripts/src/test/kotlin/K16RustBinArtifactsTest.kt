/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class K16RustBinArtifactsTest {
    @Test
    fun findsSingleLinkedCargoBinArtifactByK16TargetProfileAndUnderscorePrefix() {
        val workspace = createTempDirectory("k16-rust-bin-artifact-test-")
        val deps = workspace.resolve("k16-unknown-kraftos/release/deps").createDirectories()
        val artifact = deps.resolve("k16_kernel-1234567890abcdef")
        artifact.writeBytes(byteArrayOf(1, 2, 3))
        deps.resolve("k16_kernel-1234567890abcdef.d").writeBytes(byteArrayOf(4, 5, 6))

        assertEquals(artifact.toFile(), K16RustBinArtifacts.find(workspace.toFile(), "k16-kernel", "release"))
    }

    @Test
    fun rejectsMissingOrAmbiguousLinkedCargoBinArtifacts() {
        val workspace = createTempDirectory("k16-rust-bin-artifact-test-")
        val deps = workspace.resolve("k16-unknown-kraftos/release/deps").createDirectories()

        assertThrows<IllegalStateException> {
            K16RustBinArtifacts.find(workspace.toFile(), "kernel", "release")
        }

        deps.resolve("kernel-1111").writeBytes(byteArrayOf(1))
        deps.resolve("kernel-2222").writeBytes(byteArrayOf(2))

        val error =
            assertThrows<IllegalStateException> {
                K16RustBinArtifacts.find(workspace.toFile(), "kernel", "release")
            }
        assertTrue(error.message.orEmpty().contains("found 2"))
    }

    @Test
    fun copiesLinkedArtifactAndDeletesStaleCargoOutputsForOneBin() {
        val workspace = createTempDirectory("k16-rust-bin-artifact-test-")
        val profile = workspace.resolve("k16-unknown-kraftos/debug").createDirectories()
        val deps = profile.resolve("deps").createDirectories()
        val fingerprint = profile.resolve(".fingerprint").createDirectories()
        val incremental = profile.resolve("incremental").createDirectories()
        val artifact = deps.resolve("kernel_loader-abcd")
        val unrelated = deps.resolve("other-abcd")
        artifact.writeBytes(byteArrayOf(7, 8, 9))
        unrelated.writeBytes(byteArrayOf(10))

        val output = workspace.resolve("out/kernel-loader.kb")
        K16RustBinArtifacts.copy(workspace.toFile(), "kernel-loader", output.toFile(), "debug")
        assertEquals(listOf<Byte>(7, 8, 9), output.readBytes().toList())

        profile.resolve("kernel-loader").writeBytes(byteArrayOf(1))
        profile.resolve("kernel-loader.d").writeBytes(byteArrayOf(1))
        deps.resolve("kernel_loader-efgh.d").writeBytes(byteArrayOf(1))
        fingerprint.resolve("kernel_loader-efgh").createDirectories()
        incremental.resolve("kernel_loader-efgh").createDirectories()

        K16RustBinArtifacts.deleteOutputs(workspace.toFile(), "kernel-loader", "debug")

        assertFalse(profile.resolve("kernel-loader").toFile().exists())
        assertFalse(profile.resolve("kernel-loader.d").toFile().exists())
        assertFalse(artifact.toFile().exists())
        assertFalse(deps.resolve("kernel_loader-efgh.d").toFile().exists())
        assertFalse(fingerprint.resolve("kernel_loader-efgh").toFile().exists())
        assertFalse(incremental.resolve("kernel_loader-efgh").toFile().exists())
        assertTrue(unrelated.toFile().exists())
    }
}
