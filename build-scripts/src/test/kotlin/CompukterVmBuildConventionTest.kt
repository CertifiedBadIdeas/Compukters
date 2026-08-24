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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class CompukterVmBuildConventionTest {
    @Test
    fun neutralVmHasHostRustVerificationWithoutNativePackaging() {
        val rootBuildScript = rootBuildSource().readText()

        assertTrue(rootBuildScript.contains("testCompukterVmRust"))
        assertTrue(rootBuildScript.contains("host/compukter-vm"))
        assertTrue(rootBuildScript.contains("Compukter-VM submodule"))
        assertTrue(rootBuildScript.contains("inputs.dir(vmRoot.resolve(\"src\"))"))
        assertTrue(rootBuildScript.contains("inputs.dir(vmRoot.resolve(\"tests\"))"))
        assertTrue(rootBuildScript.contains("dependsOn(testCompukterVmRust)"))
        assertFalse(rootBuildScript.contains("libcompukter_vm"))
        assertFalse(rootBuildScript.contains("buildCompukterVmNativeLibrary"))
    }

    private fun rootBuildSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "..", "build.gradle.kts").normalize(),
                Path.of(System.getProperty("user.dir"), "build.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate root build.gradle.kts from ${System.getProperty("user.dir")}")
    }
}
