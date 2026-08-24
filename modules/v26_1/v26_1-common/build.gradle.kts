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
    alias(libs.plugins.v261)
    alias(libs.plugins.commonConvention)
}

architectury {
    common("neoforge")
}

val bootArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/boot.cpkt")
val shellArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")
val kotlincArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/kotlinc.cpkt")

tasks.processResources {
    dependsOn(":compiler-k2:generateBootArtifact", ":compiler-k2:generateShellArtifact", ":compiler-k2:generateKotlincArtifact")
    from(bootArtifact) {
        into("system/programs")
        rename { "boot" }
    }
    from(shellArtifact) {
        into("system/programs")
        rename { "shell" }
    }
    from(kotlincArtifact) {
        into("system/programs")
        rename { "kotlinc" }
    }
}
