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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class K16IncrementalBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun k16GuestRustKernelDeclaresCargoLockAsIncrementalInput() {
        val buildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val kernelTask =
            buildScript.substringAfter("val compileK16SystemKernel =")
                .substringBefore("val compileK16SystemInit =")

        assertTrue(buildScript.contains("val k16GuestLock"))
        assertTrue(kernelTask.contains("inputs.file(k16GuestLock)"))
    }

    @Test
    fun k16FirmwareCompileTasksDeclareFullHostToolDependencyInputs() {
        val buildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val helper =
            buildScript.substringAfter("fun org.gradle.api.Task.inputsK16HostTools()")
                .substringBefore("fun Project.compileK16GuestRustBin")

        assertTrue(helper.contains("inputs.file(k16HostToolsManifest)"))
        assertTrue(helper.contains("inputs.file(k16HostToolsLock)"))
        assertTrue(helper.contains("inputs.dir(k16HostToolsSource)"))
        assertTrue(helper.contains("inputs.file(k16HostVmManifest)"))
        assertTrue(helper.contains("inputs.file(k16HostVmLock)"))
        assertTrue(helper.contains("inputs.dir(k16HostVmSource)"))
        assertTrue(helper.contains("inputs.file(k16AbiManifest)"))
        assertTrue(helper.contains("inputs.dir(k16AbiSource)"))

        val kernelTask =
            buildScript.substringAfter("val compileK16SystemKernel =")
                .substringBefore("val compileK16SystemInit =")
        val lsTask =
            buildScript.substringAfter("val compileK16SystemLs =")
                .substringBefore("val compileK16SystemCat =")
        val sharedKraftTask =
            buildScript.substringAfter("val compileK16SharedKraft =")
                .substringBefore("val createK16SystemStorage0 =")

        assertTrue(kernelTask.contains("inputsK16HostTools()"))
        assertTrue(lsTask.contains("inputsK16HostTools()"))
        assertTrue(sharedKraftTask.contains("inputsK16HostTools()"))
    }

    @Test
    fun rustBootstrapProbeDeclaresOutputMarkerForIncrementalBuilds() {
        val buildScript = root.resolve("build.gradle.kts").readText()
        val probeTask =
            buildScript.substringAfter("val probeK16RustBootstrap =")
                .substringBefore("val buildK16Rustc =")

        assertTrue(buildScript.contains("val k16RustBootstrapProbeMarker"))
        assertTrue(probeTask.contains("outputs.file(k16RustBootstrapProbeMarker)"))
        assertTrue(probeTask.contains("k16RustBootstrapProbeMarker.writeText"))
    }
}
