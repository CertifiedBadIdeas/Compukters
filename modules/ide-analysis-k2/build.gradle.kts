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

import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinConvention)
}

repositories {
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        name = "JetBrainsIntellijDependencies"
        content {
            includeGroup("org.jetbrains.kotlin")
            includeGroup("com.intellij.platform")
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.caffeine.kotlin)
    implementation(libs.intellij.coroutines.core)
    implementation(libs.kotlin.analysis.api) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.impl.base) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.k2) { isTransitive = false }
    implementation(libs.kotlin.analysis.platform) { isTransitive = false }
    implementation(libs.kotlin.analysis.low.level.fir) { isTransitive = false }
    implementation(libs.kotlin.symbol.light.classes) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.standalone) {
        // JetBrains publishes each `for-ide` JAR as a shaded aggregate while
        // its generated POM still names unpublished source modules.
        isTransitive = false
    }
    testImplementation(kotlin("test"))
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val analysisWorkerPayloadDirectory = layout.buildDirectory.dir("worker-payload/content")

val prepareAnalysisWorkerPayload = tasks.register<Sync>("prepareAnalysisWorkerPayload") {
    dependsOn(tasks.jar)
    into(analysisWorkerPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
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
    from(rootProject.layout.projectDirectory.file("licenses/jvm/checker-qual-3.19.0-MIT.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }
}

val analysisWorkerPayload = tasks.register<Zip>("analysisWorkerPayload") {
    dependsOn(prepareAnalysisWorkerPayload)
    from(analysisWorkerPayloadDirectory)
    archiveFileName = "ide-analysis-k2-worker.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyAnalysisWorkerLicenses = tasks.register("verifyAnalysisWorkerLicenses") {
    description = "Checks licenses and the exact library inventory in the packaged K2 analysis worker."
    group = "verification"
    dependsOn(analysisWorkerPayload)
    inputs.file(analysisWorkerPayload.flatMap { it.archiveFile })
    inputs.file(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv"))
    doLast {
        val archive = analysisWorkerPayload.get().archiveFile.get().asFile
        val entries =
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            }
        listOf(
            "META-INF/licenses/Compukters-Apache-2.0.txt",
            "META-INF/licenses/jvm/checker-qual-3.19.0-MIT.txt",
            "META-INF/NOTICE.txt",
            "META-INF/THIRD-PARTY-NOTICES.md",
        ).forEach { required ->
            check(entries.count { it == required } == 1) {
                "expected exactly one $required in ${archive.name}"
            }
        }

        val expectedExternal =
            rootProject
                .file("licenses/distribution-components.tsv")
                .readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { it.split('\t') }
                .filter { it[0] == "jvm-analysis-worker" }
                .map { (_, component, version, _) -> "$component-$version.jar" }
                .sorted()
        val actualExternal =
            entries
                .filter { it.startsWith("lib/") && it.endsWith(".jar") }
                .map { it.removePrefix("lib/") }
                .filterNot { it.startsWith("ide-analysis-k2-") }
                .sorted()
        check(actualExternal == expectedExternal) {
            "analysis worker library inventory mismatch: expected $expectedExternal, found $actualExternal"
        }
    }
}

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.*")
}

val forkedWorkerTest = tasks.register<Test>("forkedWorkerTest") {
    description = "Runs forked K2 analysis worker integration tests."
    group = "verification"
    dependsOn(analysisWorkerPayload)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.*")
        isFailOnNoMatchingTests = false
    }
    doFirst {
        systemProperty("compukters.analysis.java", javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().executablePath.asFile.absolutePath)
        systemProperty(
            "compukters.analysis.payload",
            analysisWorkerPayloadDirectory.get().asFile.absolutePath,
        )
    }
}

tasks.named("check") {
    dependsOn(verifyAnalysisWorkerLicenses)
    dependsOn(forkedWorkerTest)
}
