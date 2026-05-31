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
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
}

tasks.register<GenerateRuxFontTablesTask>("generateRuxFontTables") {
    description = "Generates Rust and Kotlin terminal font tables from the Rux bitmap font source."
    group = "rux"
    fontFile.set(layout.projectDirectory.file("assets/rux/fonts/rux-mono-5x7.font"))
    rustOutput.set(layout.projectDirectory.file("native/k16-vm/src/generated/font_mono5x7.rs"))
    kotlinOutput.set(
        layout.projectDirectory.file(
            "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/GeneratedTerminalFont.kt",
        ),
    )
}

tasks.register<GenerateRuxFontSpecimenTask>("generateRuxFontSpecimen") {
    description = "Generates a Markdown specimen report for the Rux bitmap font source."
    group = "rux"
    fontFile.set(layout.projectDirectory.file("assets/rux/fonts/rux-mono-5x7.font"))
    output.set(layout.buildDirectory.file("reports/rux-font/rux-mono-5x7-specimen.md"))
}
