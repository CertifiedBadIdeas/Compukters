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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class K16PrebuiltToolchainBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun neoforgeBuildInstallsPinnedPrebuiltToolchainArchive() {
        val buildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val config = root.resolve("config/k16-toolchain.json").readText()
        val docs = root.resolve("docs/toolchains/k16-prebuilt-toolchain.md").readText()

        assertTrue(config.contains("\"artifactBaseUrl\""))
        assertTrue(config.contains("\"sha256\""))
        assertTrue(config.contains(".zip"))
        assertFalse(config.contains(".tar.zst"))

        assertTrue(buildScript.contains("downloadK16ToolchainArchive"))
        assertTrue(buildScript.contains("installK16Toolchain"))
        assertTrue(buildScript.contains("zipTree"))
        assertTrue(buildScript.contains("URI("))
        assertTrue(buildScript.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(buildScript.contains("verifyK16ToolchainArchiveChecksum"))
        assertTrue(buildScript.contains("dependsOn(installK16Toolchain)"))
        assertTrue(buildScript.contains("k16ToolchainDir"))
        assertTrue(buildScript.contains("k16ToolchainCacheDir"))

        assertFalse(buildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(buildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(buildScript.contains("providers.environmentVariable(\"K16_LD\")"))

        assertTrue(docs.contains("gh release upload"))
        assertTrue(docs.contains("sha256sum"))
        assertTrue(docs.contains("config/k16-toolchain.json"))
        assertTrue(docs.contains("-Pk16ToolchainDir"))
    }
}
