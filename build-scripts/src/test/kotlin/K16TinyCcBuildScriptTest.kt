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
import kotlin.io.path.exists
import kotlin.io.path.readText

class K16TinyCcBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun tinyCcK16SourceIsPinnedAsTheDedicatedForkSubmodule() {
        val gitmodules = root.resolve(".gitmodules").readText()

        assertTrue(gitmodules.contains("[submodule \"toolchains/Compukter-Kraft-tinycc\"]"))
        assertTrue(gitmodules.contains("url = git@github.com:CertifiedBadIdeas/Compukter-Kraft-tinycc.git"))
        assertTrue(gitmodules.contains("branch = k16"))
    }

    @Test
    fun tinyCcK16BuildIsOutOfTreePinnedAndHostOnly() {
        val buildScript = root.resolve("build.gradle.kts").readText()

        assertTrue(buildScript.contains("tasks.register<Exec>(\"configureK16TinyCc\")"))
        assertTrue(buildScript.contains("tasks.register<Exec>(\"compileK16TinyCc\")"))
        assertTrue(buildScript.contains("tasks.register<Sync>(\"buildK16TinyCc\")"))
        assertTrue(buildScript.contains("tasks.register<Exec>(\"verifyK16TinyCcBackend\")"))
        assertTrue(buildScript.contains("tasks.register(\"verifyK16TinyCc\")"))
        assertTrue(buildScript.contains("toolchains/Compukter-Kraft-tinycc"))
        assertTrue(buildScript.contains(".toolchain/build/tinycc/k16"))
        assertTrue(buildScript.contains("bin/tcc-k16"))
        assertTrue(buildScript.contains("k16-tcc"))
        assertTrue(buildScript.contains("--cpu=k16"))
        assertFalse(buildScript.contains("curl"))
        assertFalse(buildScript.contains("wget"))
        assertFalse(buildScript.contains("https://repo.or.cz/tinycc.git"))
    }

    @Test
    fun tinyCcK16BackendVerificationUsesTheCheckedInCorpus() {
        val buildScript = root.resolve("build.gradle.kts").readText()
        val taskBody =
            buildScript
                .substringAfter("tasks.register<Exec>(\"verifyK16TinyCcBackend\")")
                .substringBefore("tasks.register(\"verifyK16TinyCc\")")

        assertTrue(taskBody.contains("dependsOn(buildK16TinyCc)"))
        assertTrue(taskBody.contains("dependsOn(buildK16Llvm)"))
        assertTrue(taskBody.contains("dependsOn(prepareK16Toolchain)"))
        assertTrue(taskBody.contains("tools/k16-tinycc-smoke.sh"))
        assertTrue(taskBody.contains("environment(\"K16_TINYCC\""))
        assertTrue(taskBody.contains("environment(\"K16_CLANG\""))
        assertTrue(taskBody.contains("environment(\"K16_LLVM_READOBJ\""))
        assertTrue(taskBody.contains("environment(\"K16_TOOL\""))

        assertTrue(root.resolve("tools/k16-tinycc-smoke.sh").exists())
        listOf(
            "arithmetic.c",
            "compiler-runtime.c",
            "narrow-memory.c",
            "pointers-locals.c",
            "control-flow.c",
            "calls.c",
            "relocations.c",
            "external-add.c",
            "asm-label.c",
            "reject-float.c",
            "reject-varargs.c",
            "reject-asm.c",
            "reject-wide.c",
            "reject-aggregate.c",
        ).forEach { fixture ->
            assertTrue(root.resolve("tools/fixtures/k16-tinycc/$fixture").exists(), fixture)
        }
    }
}
