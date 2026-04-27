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

@file:Suppress("PropertyName")

plugins {
    alias(libs.plugins.v1211)
    alias(libs.plugins.neoforgeConvention)
}

repositories {
    maven("https://maven.createmod.net") // Create, Ponder, Flywheel
    maven("https://maven.ithundxr.dev/snapshots") // Registrate
}

dependencies {
    // Create + ecosystem are compile-time only: the addon must NOT bundle them in the final mod jar,
    // and they must NOT be hard runtime dependencies. Activation happens through the guarded
    // CreateCompatBootstrap, so all com.simibubi.create.* imports stay class-loaded behind that gate.
    modCompileOnly("com.simibubi.create:create-${property("minecraft_version")}:${property("create_version")}:slim") { isTransitive = false }
    modCompileOnly("net.createmod.ponder:ponder-neoforge:${property("ponder_version")}+mc${property("minecraft_version")}")
    modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${property("minecraft_version")}:${property("flywheel_version")}")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-${property("minecraft_version")}:${property("flywheel_version")}")
    modCompileOnly("com.tterrag.registrate:Registrate:${property("registrate_version")}")

    implementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    implementation(projects.core)
    implementation(projects.compiler)

    testImplementation(kotlin("test"))
}
