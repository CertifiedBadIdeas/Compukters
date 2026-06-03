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
        assertTrue(config.contains("\"linux-x86_64\""))
        assertTrue(config.contains("\"54d859125ceaa0093ccea98b6069c3c37d3aa6270fc1242c2a20ba8c6589dc99\""))

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
        assertTrue(rootBuildScript.contains("printK16ToolchainEnv"))
        assertTrue(rootBuildScript.contains("packageBuiltK16Toolchain"))
        assertFalse(rootBuildScript.contains("tasks.register<Zip>(\"packageK16Toolchain\")"))
        assertTrue(rootBuildScript.contains("permissions { unix(\"rwxr-xr-x\") }"))
        assertTrue(rootBuildScript.contains("k16ToolchainPin.requiredExecutables.contains(relativePath.pathString)"))
        assertTrue(rootBuildScript.contains("dependsOn(installK16Toolchain)"))
        assertTrue(rootBuildScript.contains("k16ToolchainDir"))
        assertTrue(rootBuildScript.contains("k16ToolchainMode"))
        assertTrue(rootBuildScript.contains("prepareK16Toolchain"))
        assertTrue(k16ToolchainSupport.contains(".toolchain/k16"))
        assertTrue(k16ToolchainSupport.contains("data class K16Toolchain"))
        assertTrue(k16ToolchainSupport.contains("fun Project.resolveK16Toolchain()"))
        assertTrue(k16ToolchainSupport.contains("file.canExecute()"))
        assertTrue(k16ToolchainSupport.contains("must be executable"))
        assertTrue(k16ToolchainSupport.contains("file.length() > 0"))
        assertTrue(k16ToolchainSupport.contains("must not be empty"))
        assertTrue(k16ToolchainSupport.contains("must point outside the staged K16 toolchain root"))
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
        assertTrue(neoforgeBuildScript.contains("processBuilder.environment()[\"RUSTC_BOOTSTRAP\"] = \"1\""))

        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_LD\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(neoforgeBuildScript.contains("providers.environmentVariable(\"K16_LD\")"))

        assertTrue(docs.contains("gh release upload"))
        assertTrue(docs.contains("stageK16Toolchain"))
        assertFalse(docs.contains("packageK16Toolchain"))
        assertTrue(docs.contains("config/k16-toolchain.json"))
        assertTrue(docs.contains("-Pk16ToolchainMode=prebuilt"))
        assertTrue(docs.contains("-Pk16ToolchainMode=local"))
        assertTrue(docs.contains("-Pk16ToolchainDir"))
        assertTrue(docs.contains("RUSTC_BOOTSTRAP=1"))
        assertTrue(docs.contains("lib/rustlib/<host>/lib/"))
    }

    @Test
    fun rootCleanRemovesRepoBuildAndTargetOutputsButKeepsToolchainWorkspace() {
        val rootBuildScript = root.resolve("build.gradle.kts").readText()
        val docs = root.resolve("docs/toolchains/k16-prebuilt-toolchain.md").readText()

        assertTrue(rootBuildScript.contains("tasks.register(\"cleanWorkspace\")"))
        assertTrue(rootBuildScript.contains("tasks.named(\"clean\")"))
        assertTrue(rootBuildScript.contains("dependsOn(cleanWorkspace)"))
        assertFalse(rootBuildScript.contains("sequenceOf(repositoryRoot.resolve(\".toolchain\"))"))
        assertTrue(rootBuildScript.contains(".toolchain"))
        assertTrue(rootBuildScript.contains("walkTopDown()"))
        assertTrue(rootBuildScript.contains("file.name == \"build\" || file.name == \"target\""))
        assertTrue(rootBuildScript.contains("!file.resolve(\".git\").exists()"))
        assertTrue(rootBuildScript.contains(".git"))
        assertTrue(rootBuildScript.contains(".gradle-sandbox"))
        assertTrue(docs.contains("preserves `.toolchain`"))
    }

    @Test
    fun rootBuildCanBuildLocalToolchainFromSources() {
        val rootBuildScript = root.resolve("build.gradle.kts").readText()
        val bootstrapProbe = root.resolve("tools/k16-rustc-bootstrap-probe.sh").readText()
        val docs = root.resolve("docs/toolchains/k16-prebuilt-toolchain.md").readText()

        assertTrue(rootBuildScript.contains("buildK16ToolchainFromSource"))
        assertTrue(rootBuildScript.contains("buildK16Llvm"))
        assertTrue(rootBuildScript.contains("writeK16RustBootstrapConfig"))
        assertTrue(rootBuildScript.contains("probeK16RustBootstrap"))
        assertTrue(rootBuildScript.contains("buildK16Rustc"))
        assertTrue(rootBuildScript.contains("buildK16HostTools"))
        assertTrue(rootBuildScript.contains("stageBuiltK16Toolchain"))
        assertTrue(rootBuildScript.contains("prepareBuiltK16Toolchain"))
        assertTrue(rootBuildScript.contains("packageBuiltK16Toolchain"))
        assertTrue(rootBuildScript.contains(".toolchain/build/llvm/k16-min"))
        assertTrue(rootBuildScript.contains(".toolchain/build/rust/k16"))
        assertTrue(rootBuildScript.contains(".toolchain/build/cargo/k16-tools"))
        assertTrue(rootBuildScript.contains("toolchains/Compukter-Kraft-llvm"))
        assertTrue(rootBuildScript.contains("toolchains/Compukter-Kraft-rust"))
        assertTrue(rootBuildScript.contains("tools/k16-rustc-bootstrap-probe.sh"))
        assertTrue(rootBuildScript.contains("val k16LlvmHostTarget"))
        assertTrue(rootBuildScript.contains("val k16LlvmBuildJobs"))
        assertTrue(rootBuildScript.contains("LLVMX86TargetMCA"))
        assertTrue(rootBuildScript.contains("LLVMMCA"))
        assertTrue(rootBuildScript.contains("LLVM_TARGETS_TO_BUILD=\$k16LlvmHostTarget"))
        assertTrue(rootBuildScript.contains("LLVM_EXPERIMENTAL_TARGETS_TO_BUILD=K16"))
        assertTrue(rootBuildScript.contains("\"--parallel\""))
        assertTrue(rootBuildScript.contains("k16LlvmBuildJobs"))
        assertTrue(rootBuildScript.contains("\"FileCheck\""))
        assertTrue(rootBuildScript.contains("\"LLVMLTO\""))
        assertTrue(rootBuildScript.contains("K16_LLVM_HOST_TARGET"))
        assertTrue(bootstrapProbe.contains("FileCheck"))
        assertTrue(bootstrapProbe.contains("HOST_LLVM_TARGET"))
        assertTrue(bootstrapProbe.contains("HOST_LLVM_COMPONENT"))
        assertTrue(bootstrapProbe.contains("--link-static"))
        assertTrue(bootstrapProbe.contains("lto"))
        assertTrue(rootBuildScript.contains("cmake"))
        assertTrue(rootBuildScript.contains("x.py"))
        assertTrue(rootBuildScript.contains("library/std"))
        assertTrue(rootBuildScript.contains("libstd-*.rlib"))
        assertTrue(rootBuildScript.contains("cargo"))
        assertTrue(rootBuildScript.contains("stage0/bin/cargo"))
        assertTrue(rootBuildScript.contains("stage1/bin/rustc"))
        assertTrue(rootBuildScript.contains("release/k16-ld"))
        assertTrue(rootBuildScript.contains("source-built-gradle-stage"))
        assertTrue(rootBuildScript.contains("dependsOn(stageBuiltK16Toolchain)"))
        assertTrue(rootBuildScript.contains("export K16_CARGO="))
        assertTrue(rootBuildScript.contains("export K16_RUSTC="))
        assertTrue(rootBuildScript.contains("export K16_LD="))

        assertTrue(docs.contains("buildK16ToolchainFromSource"))
        assertTrue(docs.contains("prepareBuiltK16Toolchain"))
        assertTrue(docs.contains("packageBuiltK16Toolchain"))
        assertTrue(docs.contains(".toolchain/build/llvm/k16-min"))
        assertTrue(docs.contains(".toolchain/build/rust/k16"))
        assertTrue(docs.contains(".toolchain/build/cargo/k16-tools"))
        assertTrue(docs.contains("the native host LLVM backend plus K16"))
    }
}
