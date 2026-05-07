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

package ru.lazyhat.compukterkraft.impl.computer.vm

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeVmProfilingReportAggregationTest {
    @Test
    fun generatesRuntimeVmComparisonReportFromIsolatedProfiles() {
        val jvmProfilePath = requiredPath(JVM_PROFILE_PATH_PROPERTY)
        val rustProfilePath = requiredPath(RUST_PROFILE_PATH_PROPERTY)
        val reportPath = requiredPath(REPORT_PATH_PROPERTY)

        val jvmProfile = RuntimeVmProfileCodec.read(jvmProfilePath)
        val rustProfile = RuntimeVmProfileCodec.read(rustProfilePath)
        val markdown = RuntimeVmProfilingReportFormatter.markdown(jvmProfile, rustProfile)

        Files.createDirectories(reportPath.parent)
        reportPath.writeText(markdown)
        println("Runtime VM profiling comparison report: ${reportPath.absolutePathString()}")

        assertTrue(Files.exists(reportPath), "Expected report at ${reportPath.absolutePathString()}")
        assertTrue(markdown.contains("JVM"), markdown)
        assertTrue(markdown.contains("Rust"), markdown)
    }

    private fun requiredPath(propertyName: String): Path {
        val value = System.getProperty(propertyName)
        assumeTrue(!value.isNullOrBlank(), "$propertyName is only provided by profiling Gradle tasks")
        return Path.of(value)
    }

    private companion object {
        const val JVM_PROFILE_PATH_PROPERTY = "ckl.profiling.jvm.profile.path"
        const val RUST_PROFILE_PATH_PROPERTY = "ckl.profiling.rust.profile.path"
        const val REPORT_PATH_PROPERTY = "ckl.profiling.report.path"
    }
}
