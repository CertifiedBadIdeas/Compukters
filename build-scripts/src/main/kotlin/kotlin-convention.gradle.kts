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

import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    id("org.jetbrains.kotlin.jvm")
    id("org.jmailen.kotlinter")
}

repositories {
    mavenCentral {
        name = "Central"
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
    }
    maven("https://maven.neoforged.net/releases/") {
        name = "NeoForged"
    }
}

group = readAllModProperties().getValue("common_mod_group_id")
version = rootProject.version

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ConfigurableKtLintTask>().configureEach {
    exclude { it.file.path.contains("build/generated") }
}
