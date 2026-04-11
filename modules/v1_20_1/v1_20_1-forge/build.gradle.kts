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
    idea
    alias(libs.plugins.v1201)
    alias(libs.plugins.forgeConvention)
    alias(libs.plugins.metadataConvention)
}

loom {
    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v1201Common.path))
            sourceSet("main", project(projects.core.path))
        }
    }
}

dependencies {
    implementation(project(path = projects.v1201Common.path, configuration = "namedElements"))
}
