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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.guestApiCore)
    implementation(projects.compilerClient)
    implementation(projects.compilerArtifact)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler)
    testImplementation(projects.ideCore)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val workerRuntimeClasspath = configurations.create("workerRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
}

val workerPayloadDirectory = layout.buildDirectory.dir("worker-payload/content")
val pinnedKotlinVersion = libs.versions.kotlin.asProvider().get()

val prepareCompilerWorkerPayload = tasks.register<Sync>("prepareCompilerWorkerPayload") {
    dependsOn(tasks.jar)
    into(workerPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar) {
        into("lib")
    }
    from(workerRuntimeClasspath) {
        into("lib")
    }
    from(rootProject.layout.projectDirectory.file("licenses/project/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "Compukters-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.dir("licenses/kotlin/v2.4.10")) {
        into("META-INF/licenses/kotlin/v2.4.10")
    }
    from(rootProject.layout.projectDirectory.file("licenses/rust/generic-array-0.14.7-LICENSE.txt")) {
        into("META-INF/licenses/rust")
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }

    doLast {
        val root = workerPayloadDirectory.get().asFile.toPath()
        val libraryDirectory = root.resolve("lib")
        val files =
            Files.list(libraryDirectory).use { paths ->
                paths.sorted().map { path ->
                    val bytes = Files.readAllBytes(path)
                    Triple("lib/${path.fileName}", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
                }.toList()
            }
        val payloadDigest = MessageDigest.getInstance("SHA-256")
        fun digestField(value: String) {
            val bytes = value.toByteArray()
            payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            payloadDigest.update(bytes)
        }
        val standardLibraryAbi = ByteArray(32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val identityProperties =
            sortedMapOf(
                "artifactWriter" to "1",
                "codegenAbi" to "1",
                "compiler" to pinnedKotlinVersion,
                "language" to "2.4",
                "standardLibraryAbi" to standardLibraryAbi,
            )
        payloadDigest.update("Compukters worker payload v1\u0000".toByteArray())
        payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        digestField("compiler")
        identityProperties.forEach { (name, value) ->
            digestField(name)
            digestField(value)
        }
        digestField(application.mainClass.get())
        files.forEach { (path, size, hash) ->
            digestField(path)
            payloadDigest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
            payloadDigest.update(hash)
        }
        val payloadHash = payloadDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest =
            buildString {
                appendLine("format=1")
                appendLine("kind=compiler")
                identityProperties.forEach { (name, value) -> appendLine("identity.$name=$value") }
                appendLine("mainClass=${application.mainClass.get()}")
                appendLine("payloadSha256=$payloadHash")
                files.forEach { (path, size, hash) ->
                    append("file=").append(path).append('\t').append(size).append('\t')
                    appendLine(hash.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
            }
        Files.writeString(root.resolve("worker.payload"), manifest)
    }
}

val compilerWorkerPayload = tasks.register<Zip>("compilerWorkerPayload") {
    dependsOn(prepareCompilerWorkerPayload)
    from(workerPayloadDirectory)
    archiveFileName = "compiler-k2-worker.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyCompilerWorkerLicenses =
    tasks.register("verifyCompilerWorkerLicenses") {
        description = "Checks licenses and the library inventory in the packaged compiler worker."
        group = "verification"
        dependsOn(compilerWorkerPayload)
        inputs.file(compilerWorkerPayload.flatMap { it.archiveFile })
        inputs.file(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv"))
        doLast {
            val archive = compilerWorkerPayload.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
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

            val inventory = rootProject.file("licenses/distribution-components.tsv")
            check(inventory.isFile) { "distribution component inventory is missing: $inventory" }
            val expectedExternal =
                inventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-worker" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .sorted()
            val projectPrefixes =
                listOf("compiler-artifact-", "compiler-client-", "compiler-k2-", "guest-api-core-", "worker-client-")
            val actualExternal =
                entries
                    .filter { it.startsWith("lib/") && it.endsWith(".jar") }
                    .map { it.removePrefix("lib/") }
                    .filterNot { name -> projectPrefixes.any(name::startsWith) }
                    .sorted()
            check(actualExternal == expectedExternal) {
                "compiler worker library inventory mismatch: expected $expectedExternal, found $actualExternal"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyCompilerWorkerLicenses)
}

val workerJar = tasks.jar.flatMap { it.archiveFile }

tasks.test {
    dependsOn(tasks.jar)
    filter.excludeTestsMatching("ru.lazyhat.compukters.compiler.worker.integration.*")
    inputs.file(workerJar)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
    }
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    source(
        rootProject.file("system/programs/edit.kt"),
        rootProject.file("system/programs/kotlinc.kt"),
        rootProject.file("system/programs/shell/Lexer.kt"),
        rootProject.file("system/programs/shell.kt"),
    )
}

val kotlinSubsetConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt")
val blockingCallConformanceArtifact = layout.buildDirectory.file("generated/conformance/blocking-call.cpkt")
val suspendCallConformanceArtifact = layout.buildDirectory.file("generated/conformance/suspend-call.cpkt")
val whenConformanceArtifact = layout.buildDirectory.file("generated/conformance/when.cpkt")
val argvConformanceArtifact = layout.buildDirectory.file("generated/conformance/argv.cpkt")
val bootArtifact = layout.buildDirectory.file("generated/system/boot.cpkt")
val shellArtifact = layout.buildDirectory.file("generated/system/shell.cpkt")
val kotlincArtifact = layout.buildDirectory.file("generated/system/kotlinc.cpkt")
val editArtifact = layout.buildDirectory.file("generated/system/edit.cpkt")

val generateKotlinSubsetConformanceArtifact = tasks.register<Test>("generateKotlinSubsetConformanceArtifact") {
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*primitive char array lowers deterministically for exact utf16 materialization*")
    inputs.file(workerJar)
    outputs.file(kotlinSubsetConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.kotlinSubsetArtifact", kotlinSubsetConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateSuspendCallConformanceArtifact = tasks.register<Test>("generateSuspendCallConformanceArtifact") {
    description = "Compiles a real K2 suspend-call program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*suspend project call lowers deterministically for vm execution*")
    inputs.file(workerJar)
    outputs.file(suspendCallConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.suspendCallArtifact", suspendCallConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateBlockingCallConformanceArtifact = tasks.register<Test>("generateBlockingCallConformanceArtifact") {
    description = "Compiles an ordinary Kotlin main with a VM-blocking call for native runtime conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*ordinary main lowers trusted terminal wait as vm blocking*")
    inputs.file(workerJar)
    outputs.file(blockingCallConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.blockingCallArtifact", blockingCallConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateWhenConformanceArtifact = tasks.register<Test>("generateWhenConformanceArtifact") {
    description = "Compiles a bounded Kotlin when program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*bounded when lowers deterministically for vm execution*")
    inputs.file(workerJar)
    outputs.file(whenConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.whenArtifact", whenConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateArgvConformanceArtifact = tasks.register<Test>("generateArgvConformanceArtifact") {
    description = "Compiles a Kotlin Array<String> entry program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*string array entry lowers deterministically for vm argv conformance*")
    inputs.file(workerJar)
    outputs.file(argvConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.argvArtifact", argvConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateShellArtifact = tasks.register<Test>("generateShellArtifact") {
    description = "Compiles the checked-in no-std Kotlin shell into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in shell compiles deterministically*")
    inputs.file(rootProject.file("system/programs/shell.kt"))
    inputs.file(rootProject.file("system/programs/shell/Lexer.kt"))
    inputs.file(workerJar)
    outputs.file(shellArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
    }
}

val generateBootArtifact = tasks.register<Test>("generateBootArtifact") {
    description = "Compiles the checked-in no-std Kotlin boot program into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in boot compiles deterministically with process intrinsic*")
    inputs.file(rootProject.file("system/programs/boot.kt"))
    inputs.file(workerJar)
    outputs.file(bootArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.boot.artifact", bootArtifact.get().asFile.absolutePath)
    }
}

val generateKotlincArtifact = tasks.register<Test>("generateKotlincArtifact") {
    description = "Compiles the checked-in no-std Kotlin compiler CLI into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in kotlinc compiles deterministically*")
    inputs.file(rootProject.file("system/programs/kotlinc.kt"))
    inputs.file(workerJar)
    outputs.file(kotlincArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.kotlinc.artifact", kotlincArtifact.get().asFile.absolutePath)
    }
}

val generateEditArtifact = tasks.register<Test>("generateEditArtifact") {
    description = "Compiles the checked-in no-std Kotlin editor into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in editor compiles deterministically*")
    inputs.file(rootProject.file("system/programs/edit.kt"))
    inputs.file(workerJar)
    outputs.file(editArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.edit.artifact", editArtifact.get().asFile.absolutePath)
    }
}

val workerMeasurementReport = layout.buildDirectory.file("reports/worker/measurements.json")

val forkedWorkerTest = tasks.register<Test>("forkedWorkerTest") {
    dependsOn(prepareCompilerWorkerPayload)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("ru.lazyhat.compukters.compiler.worker.integration.*")
    shouldRunAfter(tasks.test)
    inputs.dir(workerPayloadDirectory)
    outputs.file(workerMeasurementReport)
    doFirst {
        systemProperty("compukters.worker.payload", workerPayloadDirectory.get().asFile.absolutePath)
        systemProperty("compukters.worker.java", javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }.get().executablePath)
        systemProperty("compukters.worker.test-classpath", sourceSets.test.get().runtimeClasspath.asPath)
        systemProperty("compukters.worker.measurement-report", workerMeasurementReport.get().asFile.absolutePath)
    }
}

tasks.check {
    dependsOn(forkedWorkerTest)
}

val compilerModuleNames =
    setOf(
        "kotlin-compiler",
        "kotlin-compiler-embeddable",
        "kotlin-scripting-compiler-embeddable",
        "kotlin-scripting-compiler-impl-embeddable",
    )
val nonWorkerIsolationChecks =
    rootProject.allprojects
        .filter { it != project && it.path !in setOf(":compiler-client", ":ide-analysis-k2") }
        .map { candidate ->
            candidate.tasks.register("assertNoK2CompilerRuntime") {
                doLast {
                    candidate.configurations.findByName("runtimeClasspath")?.takeIf { it.isCanBeResolved }?.let { runtime ->
                        val leaked = runtime.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.name in compilerModuleNames }
                        check(leaked.isEmpty()) { "${candidate.path} production runtime leaks compiler artifacts: $leaked" }
                    }
                }
            }
        }

val assertCompilerWorkerIsolation = tasks.register("assertCompilerWorkerIsolation") {
    dependsOn(prepareCompilerWorkerPayload, nonWorkerIsolationChecks, ":compiler-client:assertNoK2CompilerRuntime")
    doLast {
        val workerArtifacts = workerRuntimeClasspath.resolvedConfiguration.resolvedArtifacts
        val compilerVersions =
            workerArtifacts
                .filter { it.moduleVersion.id.name == "kotlin-compiler" }
                .map { it.moduleVersion.id.version }
                .toSet()
        check(compilerVersions == setOf(pinnedKotlinVersion)) {
            "kotlin-compiler must resolve exactly to $pinnedKotlinVersion, got $compilerVersions"
        }
        val forbiddenCompilerModules =
            workerArtifacts.filter {
                it.moduleVersion.id.name in
                    setOf(
                        "kotlin-compiler-embeddable",
                        "kotlin-scripting-compiler-embeddable",
                        "kotlin-scripting-compiler-impl-embeddable",
                    )
            }
        check(forbiddenCompilerModules.isEmpty()) {
            "compiler worker contains forbidden embeddable or scripting compiler artifacts: $forbiddenCompilerModules"
        }

        val registrarPath = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val registrars = zipTree(workerJar.get().asFile).matching { include(registrarPath) }.files
        check(registrars.size == 1) { "worker jar must contain exactly one compiler registrar service" }
        check(registrars.single().readLines().filter(String::isNotBlank) == listOf("ru.lazyhat.compukters.compiler.worker.k2.CompukterCompilerPluginRegistrar")) {
            "worker jar contains an unexpected compiler registrar"
        }

        val payloadLibraries = workerPayloadDirectory.get().asFile.toPath().resolve("lib")
        val actualNames = Files.list(payloadLibraries).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
        val expectedNames = (workerRuntimeClasspath.files + workerJar.get().asFile).map { it.name }.toSet()
        check(actualNames == expectedNames) { "worker payload differs from its fixed resolved runtime classpath" }
        val forbiddenGroups = listOf("minecraft", "neoforge", "fabric", "architectury")
        val forbidden = workerArtifacts.filter { artifact -> forbiddenGroups.any { it in artifact.moduleVersion.id.group.lowercase() } }
        check(forbidden.isEmpty()) { "worker payload contains game or mod-loader artifacts: $forbidden" }
    }
}

tasks.check {
    dependsOn(assertCompilerWorkerIsolation)
}
