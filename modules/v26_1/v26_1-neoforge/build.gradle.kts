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

@file:Suppress("PropertyName")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    idea
    alias(libs.plugins.v261)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.metadataConvention)
}

val gameTest by sourceSets.creating

tasks.named<ProcessResources>(gameTest.processResourcesTaskName) {
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-write.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-write-alternate.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-compilation-source.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-read.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-terminal-child.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-install-rom-executable.cpkt")) {
        into("fixtures")
    }
}

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

val verifyGameTestRunIsolation =
    tasks.register("verifyGameTestRunIsolation") {
        group = "verification"
        description = "Checks that GameTest classes are visible only to the GameTest run."
        doLast {
            val gameTestFiles = gameTest.output.files.map(File::getCanonicalFile).toSet()

            fun effectiveModFiles(runName: String): Set<File> {
                val run = loom.runs.named(runName).get()
                val mods = if (run.mods.isEmpty()) loom.mods else run.mods
                return mods.flatMap { it.modFiles.files }.map(File::getCanonicalFile).toSet()
            }

            listOf("client", "client2", "client3", "server").forEach { runName ->
                val leaked = effectiveModFiles(runName).intersect(gameTestFiles)
                check(leaked.isEmpty()) { "GameTest output leaked into $runName: $leaked" }
            }
            check(effectiveModFiles("gameTestServer").intersect(gameTestFiles).isNotEmpty()) {
                "GameTest output is missing from gameTestServer"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGameTestRunIsolation)
}

tasks.configureEach {
    if (name == "runGameTestServer") {
        dependsOn(gameTest.classesTaskName)
    }
}

loom {
    // Generic client / client2 / server runs are declared in the
    // `loom-runs-convention` precompiled script plugin (build-scripts).
    // Only neoforge-specific runs live here.
    runs {
        register("gameTestServer") {
            server()
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "compukters")
            ideConfigGenerated(true)
            vmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
            mods {
                maybeCreate("main").apply {
                    sourceSet("main")
                    sourceSet("main", projects.v261Common.path)
                    sourceSet(gameTest.name)
                }
            }
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v261Common.path))
        }
    }
}

dependencies {
    common(project(path = projects.v261Common.path)) { isTransitive = false }
    shadowBundle(project(path = projects.v261Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v261Common.path))

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v261Common.path))
}

data class TerminalFontBuildSpec(
    val id: String,
    val taskStem: String,
    val displayName: String,
    val sourceDescription: String,
    val bdfPath: String,
    val cellHeight: Int,
    val ascent: Int,
    val replacementCodePoint: Int,
    val selectedRanges: List<String>,
)

fun registerTerminalFont(spec: TerminalFontBuildSpec) {
    val bdf = rootProject.layout.projectDirectory.file(spec.bdfPath)
    val fontJson = layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/${spec.id}.json")
    val atlas = layout.projectDirectory.file("src/main/resources/assets/compukters/textures/font/terminal/${spec.id}.png")
    val manifest =
        layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/${spec.id}-codepoints.txt")
    val coverage =
        layout.projectDirectory.file(
            "src/main/kotlin/ru/lazyhat/compukters/impl/terminal/${spec.taskStem}FontCoverage.kt",
        )
    val coverageName = "${spec.id.uppercase()}_SUPPORTED_CODE_POINTS"
    val generate =
        tasks.register<GenerateTerminalBitmapFont>("generate${spec.taskStem}TerminalFont") {
            description = "Regenerates committed ${spec.displayName} terminal font resources from the pinned BDF."
            group = "build setup"
            bdfFile.set(bdf)
            displayName.set(spec.displayName)
            resourceName.set(spec.id)
            coveragePropertyName.set(coverageName)
            sourceDescription.set(spec.sourceDescription)
            cellWidth.set(6)
            cellHeight.set(spec.cellHeight)
            ascent.set(spec.ascent)
            descent.set(spec.cellHeight - spec.ascent)
            replacementCodePoint.set(spec.replacementCodePoint)
            selectedRanges.set(spec.selectedRanges)
            fontJsonFile.set(fontJson)
            atlasPngFile.set(atlas)
            manifestFile.set(manifest)
            coverageKotlinFile.set(coverage)
        }
    val verify =
        tasks.register<VerifyTerminalBitmapFont>("verify${spec.taskStem}TerminalFont") {
            description = "Rejects committed ${spec.displayName} terminal font resources that drifted from its BDF."
            group = "verification"
            regenerationTaskName.set(generate.name)
            bdfFile.set(bdf)
            displayName.set(spec.displayName)
            resourceName.set(spec.id)
            coveragePropertyName.set(coverageName)
            sourceDescription.set(spec.sourceDescription)
            cellWidth.set(6)
            cellHeight.set(spec.cellHeight)
            ascent.set(spec.ascent)
            descent.set(spec.cellHeight - spec.ascent)
            replacementCodePoint.set(spec.replacementCodePoint)
            selectedRanges.set(spec.selectedRanges)
            fontJsonFile.set(fontJson)
            atlasPngFile.set(atlas)
            manifestFile.set(manifest)
            coverageKotlinFile.set(coverage)
            mustRunAfter(generate)
        }
    tasks.named("check") { dependsOn(verify) }
}

registerTerminalFont(
    TerminalFontBuildSpec(
        id = "cozette",
        taskStem = "Cozette",
        displayName = "Cozette",
        sourceDescription = "pinned Cozette v.1.30.0",
        bdfPath = "tools/fonts/cozette/v.1.30.0/cozette.bdf",
        cellHeight = 13,
        ascent = 10,
        replacementCodePoint = 0xFFFD,
        selectedRanges =
            listOf("32..126", "160..255", "1024..1279", "8592..8703", "9472..9599", "9600..9631", "65533..65533"),
    ),
)
registerTerminalFont(
    TerminalFontBuildSpec(
        id = "dina",
        taskStem = "Dina",
        displayName = "Dina",
        sourceDescription = "pinned Dina v2.92 Regular 6pt",
        bdfPath = "tools/fonts/dina/v2.92/Dina_r400-6.bdf",
        cellHeight = 10,
        ascent = 8,
        replacementCodePoint = '?'.code,
        selectedRanges = listOf("32..126", "160..255"),
    ),
)
registerTerminalFont(
    TerminalFontBuildSpec(
        id = "proggy_tiny",
        taskStem = "ProggyTiny",
        displayName = "ProggyTiny",
        sourceDescription = "pinned ProggyTiny commit 139ec08a",
        bdfPath = "tools/fonts/proggy/139ec08a/ProggyTiny.bdf",
        cellHeight = 10,
        ascent = 8,
        replacementCodePoint = '?'.code,
        selectedRanges = listOf("32..126", "160..255"),
    ),
)

val nativeOs =
    when {
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        else -> error("unsupported native build operating system: ${System.getProperty("os.name")}")
    }
val nativeArch =
    when (System.getProperty("os.arch").trim().lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("unsupported native build architecture: ${System.getProperty("os.arch")}")
    }
val nativeFilename =
    when (nativeOs) {
        "linux" -> "libcompukter_ffi.so"
        "windows" -> "compukter_ffi.dll"
        "macos" -> "libcompukter_ffi.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val productionJar = tasks.named<ShadowJar>("shadowJar")
val verifyPackagedCompukterFfi =
    tasks.register("verifyPackagedCompukterFfi") {
        description = "Checks the contents of the production NeoForge jar."
        group = "verification"
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        inputs.property("nativeResourcePath", nativeResourcePath)
        doLast {
            val archive = productionJar.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .toList()
                }
            val nativeEntries = entries.filter { it.startsWith("META-INF/natives/") }
            check(nativeEntries == listOf(nativeResourcePath)) {
                "expected only $nativeResourcePath in ${archive.name}, found $nativeEntries"
            }
            check(entries.count { it == "META-INF/neoforge.mods.toml" } == 1) {
                "expected exactly one META-INF/neoforge.mods.toml in ${archive.name}"
            }
            listOf(
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "expected exactly one $required in ${archive.name}"
                }
            }
            val nestedWorkerEntries =
                ZipFile(archive).use { zip ->
                    val worker = checkNotNull(zip.getEntry("compiler/worker/compiler-k2-worker.zip")) {
                        "compiler worker is missing from ${archive.name}"
                    }
                    ZipInputStream(zip.getInputStream(worker)).use { nested ->
                        buildList {
                            while (true) {
                                val entry = nested.nextEntry ?: break
                                if (!entry.isDirectory) add(entry.name)
                                nested.closeEntry()
                            }
                        }
                    }
                }
            listOf(
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(nestedWorkerEntries.count { it == required } == 1) {
                    "expected exactly one $required in packaged compiler worker"
                }
            }
            val inventory = rootProject.file("licenses/distribution-components.tsv")
            check(inventory.isFile) { "distribution component inventory is missing: $inventory" }
            val expectedNestedLibraries =
                inventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-outer" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .sorted()
            val actualNestedLibraries =
                entries
                    .filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }
                    .map { it.removePrefix("META-INF/jars/") }
                    .sorted()
            check(actualNestedLibraries == expectedNestedLibraries) {
                "nested JVM library inventory mismatch: expected $expectedNestedLibraries, found $actualNestedLibraries"
            }
            check(entries.none { it.startsWith("dev/architectury/") }) {
                "Architectury runtime classes leaked into ${archive.name}"
            }
            check(entries.none { it.contains("kotlin/compiler") }) {
                "Kotlin compiler implementation leaked into ${archive.name}"
            }
            check(entries.none { it.startsWith("ru/lazyhat/compukters/ide/") }) {
                "ide-core classes leaked into ${archive.name} before the IDE is packaged"
            }
            val forbiddenIdeLibraries = listOf("tomlj-", "antlr4-runtime-", "checker-qual-")
            check(
                entries.none { entry ->
                    entry.startsWith("META-INF/jars/") &&
                        forbiddenIdeLibraries.any(entry.substringAfterLast('/')::startsWith)
                },
            ) {
                "ide-core TOML dependencies leaked into ${archive.name} before the IDE is packaged"
            }
            check(entries.none { it.contains("ComputerBlockGameTest") }) {
                "GameTest classes leaked into ${archive.name}"
            }
            check(entries.none { it.startsWith("fixtures/") }) {
                "GameTest artifacts leaked into ${archive.name}"
            }
            check("ru/lazyhat/compukters/minecraft/computer/ComputerBlock.class" in entries) {
                "common computer classes are missing from ${archive.name}"
            }
            check("ru/lazyhat/compukters/impl/computer/NeoForgeComputerBlockEntity.class" in entries) {
                "NeoForge computer classes are missing from ${archive.name}"
            }
            check(
                listOf("boot", "shell", "kotlinc", "edit")
                    .all { program -> "system/programs/$program" in entries },
            ) {
                "packaged extensionless system programs are missing from ${archive.name}"
            }
            check(entries.none { it.startsWith("system/programs/") && it.endsWith(".cpkt") }) {
                "extension-bearing system program leaked into ${archive.name}"
            }
            check("assets/compukters/items/compukter.json" in entries) {
                "26.1 item model is missing from ${archive.name}"
            }
            listOf(
                "assets/compukters/font/terminal/cozette.json",
                "assets/compukters/font/terminal/cozette-codepoints.txt",
                "assets/compukters/textures/font/terminal/cozette.png",
                "META-INF/licenses/Cozette-MIT.txt",
                "META-INF/licenses/Cozette-PROVENANCE.txt",
                "assets/compukters/font/terminal/dina.json",
                "assets/compukters/font/terminal/dina-codepoints.txt",
                "assets/compukters/textures/font/terminal/dina.png",
                "META-INF/licenses/Dina-LICENSE.txt",
                "META-INF/licenses/Dina-PROVENANCE.txt",
                "assets/compukters/font/terminal/proggy_tiny.json",
                "assets/compukters/font/terminal/proggy_tiny-codepoints.txt",
                "assets/compukters/textures/font/terminal/proggy_tiny.png",
                "META-INF/licenses/Proggy-MIT.txt",
                "META-INF/licenses/Proggy-PROVENANCE.txt",
            ).forEach { required ->
                check(required in entries) { "$required is missing from ${archive.name}" }
            }
            check("assets/compukters/textures/gui/term_font.png" !in entries) {
                "legacy terminal font atlas leaked into ${archive.name}"
            }
            check(entries.none { it.contains("Spleen", ignoreCase = true) }) {
                "legacy Spleen font attribution leaked into ${archive.name}"
            }
            check("assets/compukters/models/item/compukter.json" !in entries) {
                "legacy item model leaked into ${archive.name}"
            }
            check("pack.mcmeta" !in entries) {
                "legacy pack.mcmeta leaked into ${archive.name}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPackagedCompukterFfi)
}

tasks.named("buildProductionUniversalJar") {
    dependsOn(verifyPackagedCompukterFfi)
}

val verifyNeoForgeRuntimeDependencies =
    tasks.register("verifyNeoForgeRuntimeDependencies") {
        description = "Rejects Architectury mod runtime and embedded Kotlin compiler dependencies."
        group = "verification"
        val runtimeClasspath = configurations.named("runtimeClasspath")
        inputs.files(runtimeClasspath)
        doLast {
            val forbidden =
                runtimeClasspath
                    .get()
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .filter { component ->
                        (component.group.startsWith("dev.architectury") &&
                            component.module != "architectury-transformer") ||
                            component.module.contains("kotlin-compiler")
                    }.map(ModuleComponentIdentifier::getDisplayName)
                    .sorted()
            check(forbidden.isEmpty()) {
                "forbidden NeoForge runtime dependencies: ${forbidden.joinToString()}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyNeoForgeRuntimeDependencies)
}
