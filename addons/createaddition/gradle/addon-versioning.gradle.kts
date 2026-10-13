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

import org.gradle.api.plugins.BasePluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.Properties
import java.util.zip.ZipFile

// First-party addon API versions are x.y; the target Compukters line is a separate archive/dependency identity.
val addonVersion = providers.gradleProperty("addonVersion").get()
require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(addonVersion)) {
    "addonVersion must be x.y: x is the addon API compatibility line, y is a compatible update"
}
version = addonVersion

val sdkDirectory = File(gradle.extra["compuktersSdkDirectory"].toString())
val sdk = Properties().apply { sdkDirectory.resolve("sdk.properties").inputStream().use(::load) }
val compuktersVersion = checkNotNull(sdk.getProperty("modBuildVersion")) { "SDK build version is missing" }
val compuktersParts = checkNotNull(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.+-]+)?").matchEntire(compuktersVersion)) {
    "unsupported Compukters SDK version: $compuktersVersion"
}.groupValues
val compuktersMajor = compuktersParts[1]
val compuktersMinor = compuktersParts[2].toBigInteger()
val compuktersLine = "$compuktersMajor.$compuktersMinor"
val compuktersRange = "[$compuktersVersion,$compuktersMajor.${compuktersMinor + java.math.BigInteger.ONE}.0)"
val archives = extensions.getByType<BasePluginExtension>()
val archiveBase = "${archives.archivesName.get()}-$compuktersLine"
archives.archivesName.set(archiveBase)

tasks.named<ProcessResources>("processResources") {
    inputs.property("addonVersion", addonVersion)
    inputs.property("compuktersVersionRange", compuktersRange)
    val metadataValues = mapOf("addon_version" to addonVersion, "compukters_version_range" to compuktersRange)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(metadataValues)
    }
}

val productionArchive = tasks.named<Jar>("remapJar")
val verifyAddonVersioning = tasks.register("verifyAddonVersioning") {
    group = "verification"
    description = "Verifies the packaged addon API version, target Compukters line and bounded dependency range."
    dependsOn(productionArchive)
    inputs.file(productionArchive.flatMap { it.archiveFile })
    inputs.property("addonVersion", addonVersion)
    inputs.property("archiveBase", archiveBase)
    inputs.property("compuktersVersionRange", compuktersRange)
    val checkedArchive = productionArchive.flatMap { it.archiveFile }
    val expectedAddonVersion = addonVersion
    val expectedArchiveBase = archiveBase
    val expectedCompuktersRange = compuktersRange
    doLast {
        val archive = checkedArchive.get().asFile
        check(archive.name == "$expectedArchiveBase-$expectedAddonVersion.jar") { "unexpected addon archive name: ${archive.name}" }
        ZipFile(archive).use { zip ->
            val metadata = zip.getInputStream(checkNotNull(zip.getEntry("META-INF/neoforge.mods.toml"))).bufferedReader().use { it.readText() }
            fun value(section: String, key: String): String? =
                Regex("(?m)^${Regex.escape(key)}\\s*=\\s*\"([^\"]+)\"\\s*$").find(section)?.groupValues?.get(1)
            val sections = metadata.split(Regex("(?m)^\\[\\["))
            val mod = sections.single { it.startsWith("mods]]") }
            check(value(mod, "version") == expectedAddonVersion) { "packaged addon version does not match $expectedAddonVersion" }
            val dependency = sections.single { it.startsWith("dependencies.") && value(it, "modId") == "compukters" }
            check(value(dependency, "versionRange") == expectedCompuktersRange) { "packaged Compukters dependency does not match $expectedCompuktersRange" }
        }
    }
}
tasks.named("check") { dependsOn(verifyAddonVersioning) }

// A stable output directory is the artifact contract between independent composite builds.
tasks.register<Sync>("stageDistributionJar") {
    group = "build"
    description = "Stages the verified production addon JAR for workspace distribution."
    outputs.upToDateWhen { false }
    dependsOn(verifyAddonVersioning)
    dependsOn(tasks.matching { it.name == "verifyProductionJar" })
    from(productionArchive.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("distribution"))
}
