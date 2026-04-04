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

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

data class BuildContext(
    val versionKey: String,
    val minecraftVersion: String,
    val javaVersion: Int,
)

enum class LoaderKind {
    FABRIC,
    FORGE,
    NEOFORGE,
}

private const val BUILD_CONTEXT_KEY = "ck.buildContext"
private const val LOADER_KIND_KEY = "ck.loaderKind"

fun Project.libsCatalog(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun Project.setBuildContext(versionKey: String, minecraftVersion: String, javaVersion: Int) {
    extraProperties()[BUILD_CONTEXT_KEY] = BuildContext(
        versionKey = versionKey,
        minecraftVersion = minecraftVersion,
        javaVersion = javaVersion,
    )
}

fun Project.buildContext(): BuildContext =
    (extraProperties()[BUILD_CONTEXT_KEY] as? BuildContext)
        ?: error("Build context is not configured for $path. Apply a version convention plugin first.")

fun Project.setLoaderKind(loaderKind: LoaderKind) {
    extraProperties()[LOADER_KIND_KEY] = loaderKind
}

fun Project.loaderKind(): LoaderKind =
    (extraProperties()[LOADER_KIND_KEY] as? LoaderKind)
        ?: error("Loader kind is not configured for $path. Apply a loader convention plugin first.")

fun Project.versionLibrary(aliasPrefix: String): Provider<MinimalExternalModuleDependency> =
    libsCatalog().findLibrary("$aliasPrefix-${buildContext().versionKey}").get()

fun Project.readModProperties(): MutableMap<String, String> =
    file("$rootDir/config/mod.properties")
        .readLines()
        .mapNotNull { line -> line.indexOf('=').takeIf { it != -1 }?.let { index -> line.substring(0, index) to line.substring(index + 1) } }
        .toMap()
        .toMutableMap()

fun Project.computeModVersion(): String = "${buildContext().minecraftVersion}-${rootProject.version}"

fun Project.optionalSetting(name: String): String? {
    val extra = extraProperties()
    return when {
        extra.has(name) -> extra[name].toString()
        else -> providers.gradleProperty(name).orNull
    }
}

private fun Project.extraProperties(): ExtraPropertiesExtension = extensions.getByType<ExtraPropertiesExtension>()