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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceCleanSupportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `selects project outputs without selecting source packages named build or target`() {
        temporary.resolve("build.gradle.kts").writeText("")
        val rootBuild = temporary.resolve("build").createDirectories()

        val module = temporary.resolve("modules/example").createDirectories()
        module.resolve("build.gradle.kts").writeText("")
        val moduleBuild = module.resolve("build").createDirectories()
        module.resolve("src/main/kotlin/example/build").createDirectories()
        module.resolve("src/main/kotlin/example/build/Source.kt").writeText("package example.build")

        val crate = temporary.resolve("host/example").createDirectories()
        crate.resolve("Cargo.toml").writeText("")
        val cargoTarget = crate.resolve("target").createDirectories()
        crate.resolve("src/target").createDirectories()
        crate.resolve("src/target/mod.rs").writeText("")

        assertEquals(
            setOf(rootBuild, moduleBuild, cargoTarget).map(Path::toRealPath).toSet(),
            workspaceCleanTargets(temporary).map(Path::toRealPath).toSet(),
        )
        assertEquals(true, Files.exists(module.resolve("src/main/kotlin/example/build/Source.kt")))
        assertEquals(true, Files.exists(crate.resolve("src/target/mod.rs")))
    }
}
