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

plugins {
    id("kotlin-convention")
}

val modProperties = readModProperties()
val modVersion = computeModVersion()

modProperties["mod_version"] = modVersion
optionalSetting("ck.minecraftVersionRange")?.let { modProperties["minecraft_version_range"] = it }
optionalSetting("ck.loaderVersionRange")?.let { modProperties["loader_version_range"] = it }
optionalSetting("ck.neoforgeVersionRange")?.let { modProperties["neoforge_version_range"] = it }

base.archivesName = modProperties.getValue("mod_name").replace(" ", "")
version = modVersion

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val replaceProperties = modProperties.toMap()
        val from = file("src/main/resources")
        val intoDir = file("build/generated/resources")

        inputs.properties(replaceProperties)
        inputs.dir(from)

        outputs.dir(intoDir)

        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
    }

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.srcDir(generateModMetadata.get().outputs.files)
}
