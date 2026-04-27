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
    alias(libs.plugins.v1211)
    alias(libs.plugins.commonConvention)
}

val generateLocalizationApi =
    tasks.register<GenerateLocalizationApiTask>("generateLocalizationApi") {
        description =
            "Generates a Kotlin API for accessing localization entries."
        langFile.set(
           layout
                .projectDirectory
                .file("src/main/resources/assets/compukterkraft/lang/en_us.json"),
        )
        packageName.set("ru.lazyhat.compukterkraft.common.localization")
        outputDirectory.set(layout.buildDirectory.dir("generated/sources/localizationApi/kotlin"))
    }

architectury {
    common("neoforge")
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateLocalizationApi)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateLocalizationApi)
}
