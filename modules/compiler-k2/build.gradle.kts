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
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compilerArtifact)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.scripting.compiler.embeddable)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
}

val workerRuntimeClasspath = configurations.create("workerRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
}
