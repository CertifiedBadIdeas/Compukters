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

tasks.test {
    val rustFixtures = rootProject.layout.projectDirectory.dir("host/compukter-vm/tests/fixtures")
    inputs.dir(rustFixtures)
    systemProperty("compukter.vm.fixtures", rustFixtures.asFile.absolutePath)
}

val assertCompilerArtifactIsolation = tasks.register("assertCompilerArtifactIsolation") {
    group = "verification"
    description = "Fails when compiler implementation artifacts enter compiler-artifact production classpaths."

    doLast {
        val forbidden =
            listOf("compileClasspath", "runtimeClasspath")
                .flatMap { name -> configurations.getByName(name).resolvedConfiguration.resolvedArtifacts }
                .map { it.moduleVersion.id.group to it.moduleVersion.id.name }
                .filter { (group, name) ->
                    group == "org.jetbrains.kotlin" &&
                        (name.startsWith("kotlin-compiler") || name.startsWith("kotlin-scripting-compiler"))
                }
        check(forbidden.isEmpty()) { "compiler-artifact contains compiler implementation dependencies: $forbidden" }
    }
}

tasks.check {
    dependsOn(assertCompilerArtifactIsolation)
}
