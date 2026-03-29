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

data class SemVer(val major: Int, val minor: Int, val patch: Int, val label: String? = null, val labelNum: Int? = null) {
    val base get() = "$major.$minor.$patch"
    override fun toString(): String = if (label != null) "$base-$label.$labelNum" else base
}

val versionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""")

fun parseVersion(v: String): SemVer {
    val match = versionRegex.matchEntire(v)
        ?: error("Invalid version format: '$v'. Expected 'X.Y.Z' or 'X.Y.Z-beta.N' or 'X.Y.Z-rc.N'.")
    return SemVer(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        label = match.groupValues[4].ifEmpty { null },
        labelNum = match.groupValues[5].ifEmpty { null }?.toInt(),
    )
}

fun git(projectDir: File, vararg args: String) {
    val process = ProcessBuilder("git", *args)
        .directory(projectDir)
        .inheritIO()
        .start()
    require(process.waitFor() == 0) { "git ${args.toList()} failed" }
}

fun doVersionBump(project: Project, nextVersion: String) {
    val currentVersion = project.version.toString()
    val propsFile = project.file("gradle.properties")
    propsFile.writeText(propsFile.readText().replace("version = $currentVersion", "version = $nextVersion"))
    git(project.projectDir, "add", "gradle.properties")
    git(project.projectDir, "commit", "-m", "chore: bump version to $nextVersion")
    println("Version bumped: $currentVersion -> $nextVersion")
}

val v = parseVersion(project.version.toString())

tasks.register("currentVersion") {
    group = "release"
    description = "Print the current project version"
    doLast { println("Project version: ${project.version}") }
}

tasks.register("release") {
    group = "release"
    description = "Tag current version (${project.version} -> v${project.version})"
    doLast {
        val currentVersion = project.version.toString()
        parseVersion(currentVersion)
        git(projectDir, "tag", "v$currentVersion")
        println("Tagged v$currentVersion")
        println("Run 'git push --tags' to publish.")
    }
}

tasks.register("bumpPatch") {
    group = "release"
    description = "Bump patch, clear label (${project.version} -> ${v.major}.${v.minor}.${v.patch + 1})"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major}.${v.minor}.${v.patch + 1}")
    }
}

tasks.register("bumpMinor") {
    group = "release"
    description = "Bump minor, reset patch and label (${project.version} -> ${v.major}.${v.minor + 1}.0)"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major}.${v.minor + 1}.0")
    }
}

tasks.register("bumpMajor") {
    group = "release"
    description = "Bump major, reset all (${project.version} -> ${v.major + 1}.0.0)"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major + 1}.0.0")
    }
}

tasks.register("bumpBeta") {
    val next = when (v.label) {
        null -> "${v.base}-beta.1"
        "beta" -> "${v.base}-beta.${v.labelNum!! + 1}"
        else -> null
    }
    group = "release"
    description = if (next != null) "Add/increment beta (${project.version} -> $next)" else "Add/increment beta (unavailable: current label is '${v.label}')"
    doLast {
        val v = parseVersion(project.version.toString())
        val next = when (v.label) {
            null -> "${v.base}-beta.1"
            "beta" -> "${v.base}-beta.${v.labelNum!! + 1}"
            else -> error("Cannot bump beta: current label is '${v.label}'. Use promote first.")
        }
        doVersionBump(project, next)
    }
}

tasks.register("bumpRC") {
    val next = when (v.label) {
        null, "beta" -> "${v.base}-rc.1"
        "rc" -> "${v.base}-rc.${v.labelNum!! + 1}"
        else -> null
    }
    group = "release"
    description = if (next != null) "Add/increment rc (${project.version} -> $next)" else "Add/increment rc (unavailable: unexpected label '${v.label}')"
    doLast {
        val v = parseVersion(project.version.toString())
        val next = when (v.label) {
            null, "beta" -> "${v.base}-rc.1"
            "rc" -> "${v.base}-rc.${v.labelNum!! + 1}"
            else -> error("Cannot bump rc: unexpected label '${v.label}'.")
        }
        doVersionBump(project, next)
    }
}

tasks.register("promote") {
    group = "release"
    description = if (v.label != null) "Remove label (${project.version} -> ${v.base})" else "Remove label (no label to remove)"
    doLast {
        val v = parseVersion(project.version.toString())
        require(v.label != null) { "Version '${project.version}' has no label to remove." }
        doVersionBump(project, v.base)
    }
}
