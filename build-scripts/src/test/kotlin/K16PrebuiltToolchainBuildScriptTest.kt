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
        val rootBuildScript = root.resolve("build.gradle.kts").readText()
        val neoforgeBuildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val buildLogicSupport = root.resolve("build-scripts/src/main/kotlin/BuildLogicSupport.kt").readText()
        val k16ToolchainSupport = root.resolve("build-scripts/src/main/kotlin/K16ToolchainSupport.kt").readText()
        val gitignore = root.resolve(".gitignore").readText()
        val config = root.resolve("config/k16-toolchain.json").readText()
        val docs = root.resolve("docs/toolchains/k16-prebuilt-toolchain.md").readText()

        assertTrue(config.contains("\"artifactBaseUrl\""))
        assertTrue(config.contains("\"sha256\""))
        assertTrue(config.contains(".zip"))
        assertFalse(config.contains(".tar.zst"))

        assertTrue(rootBuildScript.contains("downloadK16ToolchainArchive"))
        assertTrue(rootBuildScript.contains("installK16Toolchain"))
        assertTrue(rootBuildScript.contains("zipTree"))
        assertTrue(rootBuildScript.contains("URI("))
        assertTrue(rootBuildScript.contains("verifyK16ToolchainArchiveChecksum"))
        assertTrue(rootBuildScript.contains("stageK16Toolchain"))
        assertTrue(rootBuildScript.contains("k16RustcPath"))
        assertTrue(rootBuildScript.contains("k16CargoPath"))
        assertTrue(rootBuildScript.contains("k16LdPath"))
        assertTrue(rootBuildScript.contains("k16RustcRuntimeLibDir"))
        assertTrue(rootBuildScript.contains("k16RustcHostRuntimeLibDir"))
        assertTrue(rootBuildScript.contains("lib/rustlib/\$hostTriple/lib"))
        assertTrue(rootBuildScript.contains("into(\"lib\")"))
        assertTrue(rootBuildScript.contains("include(\"librustc_driver*.so\")"))
        assertTrue(rootBuildScript.contains("include(\"rustlib/src/rust/library/**\")"))
        assertTrue(rootBuildScript.contains("into(\"lib/rustlib/"))
        assertTrue(rootBuildScript.contains("packageK16Toolchain"))
        assertTrue(rootBuildScript.contains("printK16ToolchainEnv"))
        assertTrue(rootBuildScript.contains("tasks.register<Zip>(\"packageK16Toolchain\")"))
        assertTrue(rootBuildScript.contains("dependsOn(installK16Toolchain)"))
        assertTrue(rootBuildScript.contains("k16ToolchainDir"))
        assertTrue(rootBuildScript.contains("k16ToolchainMode"))
        assertTrue(rootBuildScript.contains("prepareK16Toolchain"))
        assertTrue(k16ToolchainSupport.contains(".toolchain/k16"))
        assertTrue(k16ToolchainSupport.contains("data class K16Toolchain"))
        assertTrue(k16ToolchainSupport.contains("fun Project.resolveK16Toolchain()"))
        assertFalse(buildLogicSupport.contains("data class K16Toolchain"))
        assertFalse(buildLogicSupport.contains("fun Project.resolveK16Toolchain()"))
        assertTrue(gitignore.contains(".toolchain"))

        assertFalse(neoforgeBuildScript.contains("downloadK16ToolchainArchive"))
        assertFalse(neoforgeBuildScript.contains("installK16Toolchain"))
        assertFalse(neoforgeBuildScript.contains("stageK16Toolchain"))
        assertFalse(neoforgeBuildScript.contains("packageK16Toolchain"))
        assertFalse(neoforgeBuildScript.contains("printK16ToolchainEnv"))
        assertTrue(neoforgeBuildScript.contains("rootProject.tasks.named(\"prepareK16Toolchain\")"))
        assertTrue(neoforgeBuildScript.contains("resolveK16Toolchain()"))
        assertTrue(neoforgeBuildScript.contains("environment(\"RUSTC_BOOTSTRAP\", \"1\")"))

        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_LD\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_LD\")"))

        assertTrue(docs.contains("gh release upload"))
        assertTrue(docs.contains("stageK16Toolchain"))
        assertTrue(docs.contains("packageK16Toolchain"))
        assertTrue(docs.contains("config/k16-toolchain.json"))
        assertTrue(docs.contains("-Pk16ToolchainMode=prebuilt"))
        assertTrue(docs.contains("-Pk16ToolchainMode=local"))
        assertTrue(docs.contains("-Pk16ToolchainDir"))
        assertTrue(docs.contains("RUSTC_BOOTSTRAP=1"))
        assertTrue(docs.contains("lib/rustlib/<host>/lib/"))
    }

    @Test
    fun rootCleanRemovesWorkspaceBuildAndTargetOutputs() {
        val rootBuildScript = root.resolve("build.gradle.kts").readText()

        assertTrue(rootBuildScript.contains("tasks.register(\"cleanWorkspace\")"))
        assertTrue(rootBuildScript.contains("tasks.named(\"clean\")"))
        assertTrue(rootBuildScript.contains("dependsOn(cleanWorkspace)"))
        assertTrue(rootBuildScript.contains(".toolchain"))
        assertTrue(rootBuildScript.contains("walkTopDown()"))
        assertTrue(rootBuildScript.contains("file.name == \"build\" || file.name == \"target\""))
        assertTrue(rootBuildScript.contains("!file.resolve(\".git\").exists()"))
        assertTrue(rootBuildScript.contains(".git"))
        assertTrue(rootBuildScript.contains(".gradle-sandbox"))
    }
}
