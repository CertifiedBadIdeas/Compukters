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
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    maven("https://maven.parchmentmc.org/") {
        name = "Parchment MC"
    }
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/") {
        name = "IntelliJ Dependencies"
    }
    maven("https://www.jetbrains.com/intellij-repository/releases") {
        name = "IntelliJ Releases"
    }
}

kotlin {
    jvmToolchain(17)
}
