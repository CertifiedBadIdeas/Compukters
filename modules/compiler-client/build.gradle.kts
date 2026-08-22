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

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val allowedRuntimeModules =
    setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )

val assertNoK2CompilerRuntime = tasks.register("assertNoK2CompilerRuntime") {
    group = "verification"
    description = "Fails when dependencies outside compiler-client's production runtime allowlist are resolved."

    doLast {
        val disallowed =
            configurations
                .getByName("runtimeClasspath")
                .resolvedConfiguration
                .resolvedArtifacts
                .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
                .filterNot(allowedRuntimeModules::contains)
        check(disallowed.isEmpty()) { "compiler-client runtimeClasspath contains disallowed dependencies: $disallowed" }
    }
}

tasks.check {
    dependsOn(assertNoK2CompilerRuntime)
}
