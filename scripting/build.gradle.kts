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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlinConvention)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    implementation(projects.langApi)
    implementation(projects.langFrontend)
    implementation(projects.langRuntime)
    implementation(projects.scriptingApi)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.dependencies)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvmHost)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveFileName.set("CompukterKraftCompiler.jar")
    mergeServiceFiles()
    exclude("ru/lazyhat/compukterkraft/machine/**")
    exclude("ru/lazyhat/compukterkraft/scripting/api/**")

    dependencies {
        exclude {
            it.name == "scripting-api"
        }
    }
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
