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
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val libVersion = "v1211"

val libs = the<VersionCatalogsExtension>().named("libs")
val minecraftVersion = libs.findVersion("minecraft-$libVersion").get().toString()
val minecraftLibrary = libs.findLibrary("minecraft-$libVersion").get()
val parchmentMappings = libs.findLibrary("parchment-$libVersion").get()

setBuildContext(
    versionKey = libVersion,
    minecraftVersion = minecraftVersion,
    javaVersion = 21,
)

// Common (Architectury) module: archive version = "<mc>-<modVersion>".
// Loader-specific conventions override this with "<mc>-<loader>-<modVersion>".
version = computeModVersion()

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

architectury {
    minecraft = minecraftVersion
}

dependencies {
    minecraft(minecraftLibrary)
    mappings(
        loom.layered {
            officialMojangMappings()
            parchment(parchmentMappings)
        },
    )

    testImplementation(kotlin("test"))
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
