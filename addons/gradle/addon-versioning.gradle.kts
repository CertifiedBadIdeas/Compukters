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
import java.io.StringReader
import java.util.Properties
import java.util.zip.ZipFile

// First-party addon API versions are x.y; the target Compukters line is a separate archive/dependency identity.
val addonVersion = providers.gradleProperty("addonVersion").get()
require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(addonVersion)) {
    "addonVersion must be x.y: x is the addon API compatibility line, y is a compatible update"
}
version = addonVersion

val workspaceProperties = Properties().apply {
    load(StringReader(providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.get()))
}
val compuktersVersion = checkNotNull(workspaceProperties.getProperty("version")) { "workspace version is missing" }.trim()
val compuktersParts = checkNotNull(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.+-]+)?").matchEntire(compuktersVersion)) {
    "unsupported Compukters workspace version: $compuktersVersion"
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
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("addon_version" to addonVersion, "compukters_version_range" to compuktersRange)
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
    doLast {
        val archive = productionArchive.get().archiveFile.get().asFile
        check(archive.name == "$archiveBase-$addonVersion.jar") { "unexpected addon archive name: ${archive.name}" }
        ZipFile(archive).use { zip ->
            val metadata = zip.getInputStream(checkNotNull(zip.getEntry("META-INF/neoforge.mods.toml"))).bufferedReader().use { it.readText() }
            fun value(section: String, key: String): String? =
                Regex("(?m)^${Regex.escape(key)}\\s*=\\s*\"([^\"]+)\"\\s*$").find(section)?.groupValues?.get(1)
            val sections = metadata.split(Regex("(?m)^\\[\\["))
            val mod = sections.single { it.startsWith("mods]]") }
            check(value(mod, "version") == addonVersion) { "packaged addon version does not match $addonVersion" }
            val dependency = sections.single { it.startsWith("dependencies.") && value(it, "modId") == "compukters" }
            check(value(dependency, "versionRange") == compuktersRange) { "packaged Compukters dependency does not match $compuktersRange" }
        }
    }
}
tasks.named("check") { dependsOn(verifyAddonVersioning) }
