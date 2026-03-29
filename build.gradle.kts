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
}

tasks.register("release") {
    group = "release"
    description = "Remove -SNAPSHOT, tag, bump minor version, push"
    doLast {
        fun git(vararg args: String) {
            val process = ProcessBuilder("git", *args)
                .directory(projectDir)
                .inheritIO()
                .start()
            require(process.waitFor() == 0) { "git ${args.toList()} failed" }
        }

        val propsFile = file("gradle.properties")
        val props = propsFile.readText()
        val currentVersion = version.toString()

        require(currentVersion.endsWith("-SNAPSHOT")) {
            "Version '$currentVersion' is not a SNAPSHOT — nothing to release."
        }

        val releaseVersion = currentVersion.removeSuffix("-SNAPSHOT")

        // 1. Write release version
        propsFile.writeText(props.replace("version = $currentVersion", "version = $releaseVersion"))
        git("add", "gradle.properties")
        git("commit", "-m", "release: v$releaseVersion")
        git("tag", "v$releaseVersion")

        // 2. Bump minor version
        val parts = releaseVersion.split(".")
        val nextVersion = "${parts[0]}.${parts[1].toInt() + 1}-SNAPSHOT"
        propsFile.writeText(propsFile.readText().replace("version = $releaseVersion", "version = $nextVersion"))
        git("add", "gradle.properties")
        git("commit", "-m", "chore: bump version to $nextVersion")

        println("Released v$releaseVersion, next development version: $nextVersion")
        println("Run 'git push && git push --tags' to publish.")
    }
}

tasks.register("currentVersion") {
    group = "release"
    description = "Print the current project version"
    doLast {
        println("Project version: $version")
    }
}
