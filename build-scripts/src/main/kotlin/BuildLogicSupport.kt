/*
 * The Compukters Developers
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

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import java.io.File

data class BuildContext(
    val versionKey: String,
    val minecraftVersion: String,
)

enum class LoaderKind(
    val lowercase: String,
) {
    FABRIC("fabric"),
    NEOFORGE("neoforge"),
}

private const val BUILD_CONTEXT_KEY = "ck.buildContext"
private const val LOADER_KIND_KEY = "ck.loaderKind"
private const val EFFECTIVE_BUILD_VERSION_KEY = "ck.effectiveBuildVersion"

fun ExtensionAware.libsCatalog(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun ExtensionAware.setBuildContext(
    versionKey: String,
    minecraftVersion: String,
) {
    extraProperties()[BUILD_CONTEXT_KEY] =
        BuildContext(
            versionKey = versionKey,
            minecraftVersion = minecraftVersion,
        )
}

fun ExtensionAware.buildContextOrNull(): BuildContext? = (extraProperties()[BUILD_CONTEXT_KEY] as? BuildContext)

fun Project.buildContext(): BuildContext =
    buildContextOrNull()
        ?: error("Build context is not configured for $path. Apply a version convention plugin first.")

fun ExtensionAware.setLoaderKind(loaderKind: LoaderKind) {
    extraProperties()[LOADER_KIND_KEY] = loaderKind
}

fun ExtensionAware.loaderKindOrNull(): LoaderKind? = extraProperties()[LOADER_KIND_KEY] as? LoaderKind

fun Project.loaderKind(): LoaderKind =
    loaderKindOrNull()
        ?: error("Loader kind is not configured for $path. Apply a loader convention plugin first.")

fun Project.versionLibrary(aliasPrefix: String): Provider<MinimalExternalModuleDependency> =
    libsCatalog().findLibrary("$aliasPrefix-${buildContext().versionKey}").get()

fun Project.readAllModProperties(): Map<String, String> =
    file("$rootDir/config/mod.properties")
        .readLines()
        .mapNotNull { line ->
            line.indexOf('=').takeIf { it != -1 }?.let { index -> line.substring(0, index) to line.substring(index + 1) }
        }.toMap()

fun Project.readVersionedModProperties(): Map<String, String> =
    readAllModProperties()
        .let { map ->
            val minecraftVersion = buildContext().minecraftVersion

            map
                .filterKeys { it.startsWith(minecraftVersion) }
                .mapKeys { (key, _) -> key.substringAfter("${minecraftVersion}_") } +
                map
                    .filterKeys { it.startsWith("common") }
                    .mapKeys { it.key.substringAfter("common_") }
        }

fun Project.computeModArchiveVersion(): String =
    "${buildContext().minecraftVersion}-${loaderKind().lowercase}-${rootProject.effectiveBuildVersion()}"

fun Project.computeModVersion(): String = "${buildContext().minecraftVersion}-${rootProject.effectiveBuildVersion()}"

fun computeEffectiveBuildVersion(
    baseVersion: String,
    headTags: Iterable<String>,
    shortHash: String,
): String {
    val releaseTags = setOf(baseVersion, "v$baseVersion")
    return if (headTags.any { it in releaseTags }) {
        baseVersion
    } else {
        "$baseVersion-S-$shortHash"
    }
}

fun Project.effectiveBuildVersion(): String {
    val extra = rootProject.extraProperties()
    extra.getProperties()[EFFECTIVE_BUILD_VERSION_KEY]?.let { return it.toString() }
    val baseVersion = rootProject.version.toString()
    val headTags =
        gitCaptureOrNull(rootProject.projectDir, "tag", "--points-at", "HEAD")
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            ?: emptyList()
    val shortHash =
        gitCaptureOrNull(rootProject.projectDir, "rev-parse", "--short", "HEAD")
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
    val effective = computeEffectiveBuildVersion(baseVersion, headTags, shortHash)
    extra[EFFECTIVE_BUILD_VERSION_KEY] = effective
    return effective
}

private fun gitCaptureOrNull(
    projectDir: File,
    vararg args: String,
): String? =
    runCatching {
        val process =
            ProcessBuilder("git", *args)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

private fun ExtensionAware.extraProperties(): ExtraPropertiesExtension = extensions.getByType<ExtraPropertiesExtension>()
