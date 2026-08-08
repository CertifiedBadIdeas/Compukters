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
            "rodata.c",
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

    @Test
    fun tinyCcUnameProofIsIsolatedFromTheProductionClangBuild() {
        val producerConvention =
            root
                .resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts")
                .readText()
        val proofBody =
            producerConvention
                .substringAfter("val compileK16TinyCcUnameProof =")
                .substringBefore("val compileK16SystemInit =")
        val productionUnameBody =
            producerConvention
                .substringAfter("val compileK16SystemUname =")
                .substringBefore("val compileK16SystemLs =")

        assertTrue(proofBody.contains("tasks.register(\"compileK16TinyCcUnameProof\")"))
        assertTrue(producerConvention.contains("generated/k16-tinycc-proof"))
        assertTrue(proofBody.contains("k16TinyCcUnameProofArtifact"))
        assertTrue(producerConvention.contains(".toolchain/build/tinycc/k16/bin/tcc-k16"))
        assertTrue(proofBody.contains("k16TinyCcExecutable"))
        assertTrue(proofBody.contains("k16CLibcStartupSource"))
        assertTrue(proofBody.contains("k16CSystemUnameSource"))
        assertTrue(proofBody.contains("-Dmain=kraft_main"))
        assertTrue(proofBody.contains("k16-startup"))
        assertTrue(proofBody.contains("program-dynamic"))
        assertTrue(proofBody.contains("--dylib"))
        assertTrue(proofBody.contains("compileK16SharedKraft"))

        assertTrue(productionUnameBody.contains("compileK16GuestCProgram"))
        assertTrue(productionUnameBody.contains("k16ClangExecutable"))
        assertFalse(productionUnameBody.contains("TinyCc"))
        assertFalse(productionUnameBody.contains("tinycc"))
    }

    @Test
    fun tinyCcRuntimeProofHasADedicatedIntegrationTask() {
        val firmwareConvention =
            root
                .resolve("build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts")
                .readText()
        val rootBuild = root.resolve("build.gradle.kts").readText()
        val runtimeTaskBody =
            firmwareConvention
                .substringAfter("tasks.register<Test>(\"verifyK16TinyCcRuntime\")")
                .substringBefore("tasks.register<Test>(\"profileK16RuntimeWait\")")
        val rootTaskBody =
            rootBuild
                .substringAfter("tasks.register(\"verifyK16TinyCc\")")
                .substringBefore("tasks.register(\"printK16ToolchainEnv\")")

        assertTrue(runtimeTaskBody.contains("buildK16VmNativeLibrary"))
        assertTrue(runtimeTaskBody.contains("inputsK16RuntimeFirmwareResources"))
        assertTrue(runtimeTaskBody.contains("compileK16TinyCcUnameProof"))
        assertTrue(runtimeTaskBody.contains("K16TinyCcRuntimeSmokeTest"))
        assertTrue(runtimeTaskBody.contains("k16.vm.native.library"))
        assertTrue(runtimeTaskBody.contains("k16.tinycc.uname.path"))
        assertTrue(
            firmwareConvention.contains(
                "excludeTestsMatching(\"ru.lazyhat.compukterkraft.impl.K16TinyCcRuntimeSmokeTest\")",
            ),
        )
        assertTrue(rootTaskBody.contains("dependsOn(verifyK16TinyCcBackend)"))
        assertTrue(rootTaskBody.contains("dependsOn(\":v1_21_1-neoforge:verifyK16TinyCcRuntime\")"))
        assertTrue(
            root
                .resolve(
                    "modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/" +
                        "ru/lazyhat/compukterkraft/impl/K16TinyCcRuntimeSmokeTest.kt",
                ).exists(),
        )
    }

    @Test
    fun nativeTinyCcUsesOneCompilerOwnedCSdkSysroot() {
        val producerConvention =
            root
                .resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts")
                .readText()
        val rootBuildScript = root.resolve("build.gradle.kts").readText()
        val sdkInclude = root.resolve("guest/kraftos/sdk/c/include")

        assertTrue(producerConvention.contains("guest/kraftos/sdk/c/include"))
        assertTrue(producerConvention.contains("guest/kraftos/sdk/c/src"))
        assertTrue(producerConvention.contains("val compileK16CSdkLibc ="))
        assertTrue(producerConvention.contains("tasks.register(\"compileK16CSdkLibc\")"))
        assertTrue(producerConvention.contains("allocator_test.c"))
        assertTrue(producerConvention.contains("string_test.c"))
        assertTrue(producerConvention.contains("unistd_test.c"))
        assertTrue(producerConvention.contains("k16CSdkIncludeSource"))
        assertFalse(producerConvention.contains("from(\"guest/kraftos/libc/include\")"))
        assertTrue(rootBuildScript.contains(":v1_21_1-neoforge:verifyK16CSdkFoundation"))
        listOf(
            "assert.h",
            "ctype.h",
            "errno.h",
            "fcntl.h",
            "limits.h",
            "setjmp.h",
            "stdarg.h",
            "stdbool.h",
            "stddef.h",
            "stdint.h",
            "stdio.h",
            "stdlib.h",
            "string.h",
            "time.h",
            "unistd.h",
            "kraft/syscalls.h",
            "sys/stat.h",
            "sys/time.h",
            "sys/types.h",
        ).forEach { header ->
            assertTrue(sdkInclude.resolve(header).exists(), header)
        }
    }

    @Test
    fun tinyCcK16BackendDocumentationStatesTheProvenBoundary() {
        val documentationPath = root.resolve("docs/toolchains/k16-tinycc-backend.md")
        assertTrue(documentationPath.exists())
        val documentation = documentationPath.readText()

        assertTrue(documentation.contains("64552b3faa39ee7948a9ea21bfcc11045b90c70d"))
        assertTrue(documentation.contains("toolchains/Compukter-Kraft-tinycc"))
        assertTrue(documentation.contains("./gradlew-sandbox-dev-parallel buildK16TinyCc"))
        assertTrue(documentation.contains("./gradlew-sandbox-dev-parallel verifyK16TinyCc"))
        assertTrue(documentation.contains(".toolchain/build/tinycc/k16/bin/tcc-k16"))
        assertTrue(documentation.contains("ELF32"))
        assertTrue(documentation.contains("ET_REL"))
        assertTrue(documentation.contains("RELA"))
        assertTrue(documentation.contains("k16 link"))
        assertTrue(documentation.contains("scalar"))
        assertTrue(documentation.contains("narrow"))
        assertTrue(documentation.contains("pointer"))
        assertTrue(documentation.contains("control flow"))
        assertTrue(documentation.contains("stack arguments"))
        assertTrue(documentation.contains("global"))
        assertTrue(documentation.contains("floating-point"))
        assertTrue(documentation.contains("variadic"))
        assertTrue(documentation.contains("integrated assembly"))
        assertTrue(documentation.contains("wide integer"))
        assertTrue(documentation.contains("aggregate"))
        assertTrue(documentation.contains("JIT"))
        assertTrue(documentation.contains("production KraftOS remains Clang-built"))
        assertTrue(documentation.contains("guest TinyCC"))
        assertTrue(documentation.contains("C SDK module"))
        assertTrue(documentation.contains("libc packaging"))
        assertTrue(documentation.contains("-run"))
    }
}
