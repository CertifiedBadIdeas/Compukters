/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.tooling.shrink

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class ToolingShrinkConfiguration(
    val inputJars: List<Path>,
    val libraryJmods: List<Path>,
    val outputJar: Path,
    val outputRoot: Path,
    val usageReport: Path,
    val seedsReport: Path,
    val reportRoot: Path,
    val mainClass: String,
    val whyKeptClasses: List<String>,
) {
    init {
        require(inputJars.isNotEmpty()) { "tooling shrink input is empty" }
        require(libraryJmods.isNotEmpty()) { "tooling shrink JDK library set is empty" }
        require(inputJars.distinctBy(::canonical).size == inputJars.size) { "tooling shrink input contains duplicates" }
        require(libraryJmods.distinctBy(::canonical).size == libraryJmods.size) { "tooling shrink JDK libraries contain duplicates" }
        inputJars.forEach { path -> requireRegular(path, ".jar", "tooling shrink input") }
        libraryJmods.forEach { path -> requireRegular(path, ".jmod", "tooling shrink JDK library") }
        requireBinaryName(mainClass, "tooling shrink main class")
        whyKeptClasses.forEach { name -> requireBinaryName(name, "tooling shrink why-kept class") }
        requireInside(outputJar, outputRoot, "tooling shrink output")
        requireInside(usageReport, reportRoot, "tooling shrink usage report")
        requireInside(seedsReport, reportRoot, "tooling shrink seeds report")
    }

    fun canonicalText(): String =
        buildString {
            inputJars.sortedBy(::canonical).forEach { path -> appendLine("-injars ${quoted(path)}") }
            appendLine("-outjars ${quoted(outputJar)}")
            libraryJmods.sortedBy(::canonical).forEach { path ->
                appendLine("-libraryjars ${quoted(path)}(!**.jar;!module-info.class)")
            }
            appendLine()
            appendLine("-dontoptimize")
            appendLine("-dontobfuscate")
            appendLine("-dontpreverify")
            appendLine("-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*,MethodParameters,SourceFile,LineNumberTable")
            appendLine("-keep class kotlin.Metadata")
            appendLine(
                "-keep public class $mainClass { public static void main(java.lang.String[]); }",
            )
            appendLine("-printusage ${quoted(usageReport)}")
            appendLine("-printseeds ${quoted(seedsReport)}")
            whyKeptClasses.distinct().sorted().forEach { name -> appendLine("-whyareyoukeeping class $name") }
        }

    companion object {
        fun create(
            inputJars: List<Path>,
            libraryJmods: List<Path>,
            outputRoot: Path,
            reportRoot: Path,
            mainClass: String,
            whyKeptClasses: List<String> = emptyList(),
        ): ToolingShrinkConfiguration {
            val canonicalOutputRoot = outputRoot.toAbsolutePath().normalize()
            val canonicalReportRoot = reportRoot.toAbsolutePath().normalize()
            return ToolingShrinkConfiguration(
                inputJars = inputJars.map { it.toAbsolutePath().normalize() },
                libraryJmods = libraryJmods.map { it.toAbsolutePath().normalize() },
                outputJar = canonicalOutputRoot.resolve("profile.jar"),
                outputRoot = canonicalOutputRoot,
                usageReport = canonicalReportRoot.resolve("usage.txt"),
                seedsReport = canonicalReportRoot.resolve("seeds.txt"),
                reportRoot = canonicalReportRoot,
                mainClass = mainClass,
                whyKeptClasses = whyKeptClasses,
            )
        }
    }
}

private fun requireRegular(
    path: Path,
    suffix: String,
    label: String,
) {
    require(path.fileName.toString().endsWith(suffix)) { "$label must be a $suffix file" }
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "$label must be a regular non-symbolic file"
    }
}

private fun requireInside(
    path: Path,
    root: Path,
    label: String,
) {
    val canonicalRoot = root.toAbsolutePath().normalize()
    val canonicalPath = path.toAbsolutePath().normalize()
    require(canonicalPath != canonicalRoot && canonicalPath.startsWith(canonicalRoot)) { "$label escapes its root" }
}

private fun requireBinaryName(
    value: String,
    label: String,
) {
    require(BINARY_NAME.matches(value)) { "$label is not a canonical JVM binary name" }
}

private fun canonical(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

private fun quoted(path: Path): String {
    val value = canonical(path)
    require(value.none { it == '\u0000' || it == '\r' || it == '\n' }) { "tooling shrink path contains control text" }
    return "\"${value.replace("\"", "\\\"")}\""
}

private val BINARY_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
