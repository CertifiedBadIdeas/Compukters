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

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class AssertNoK2CompilerRuntime : DefaultTask() {
    @get:Input
    abstract val allowedRuntimeModules: SetProperty<String>

    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val disallowed = runtimeModules.get().filterNot(allowedRuntimeModules.get()::contains)
        check(disallowed.isEmpty()) { "compiler-client runtimeClasspath contains disallowed dependencies: $disallowed" }
    }
}

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val allowedClientRuntimeModules =
    setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )
val resolvedRuntimeModules =
    configurations.named("runtimeClasspath").map { runtimeClasspath ->
        runtimeClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }
            }
            .sorted()
    }

val assertNoK2CompilerRuntime = tasks.register<AssertNoK2CompilerRuntime>("assertNoK2CompilerRuntime") {
    group = "verification"
    description = "Fails when dependencies outside compiler-client's production runtime allowlist are resolved."
    allowedRuntimeModules.set(allowedClientRuntimeModules)
    runtimeModules.set(resolvedRuntimeModules)
}

tasks.check {
    dependsOn(assertNoK2CompilerRuntime)
}
