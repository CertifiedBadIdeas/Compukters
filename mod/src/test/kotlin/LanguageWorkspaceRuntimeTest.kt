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

import ck.lang.frontend.FrontendSeverity
import ck.lang.runtime.BytecodeVirtualMachine
import ck.lang.runtime.VmSignal
import ck.lang.runtime.VmValue
import ck.mod.block.ComputerFamily
import ck.mod.computer.vm.ComputerProfileRegistry
import ck.mod.computer.vm.FileComputerWorkspace
import ck.mod.language.LanguageServices
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageWorkspaceRuntimeTest {
    @Test
    fun seededBiosCompilesAndDelegatesToShell() {
        val profile = ComputerProfileRegistry.forFamily(ComputerFamily.ADVANCED)
        val root = createTempDirectory("compukterkraft-language-workspace")

        try {
            val workspace =
                FileComputerWorkspace(
                    rootPath = root,
                    bundledScriptLoader = LanguageServices::bundledScript,
                )
            workspace.ensureInitialized(1)

            val bootScript = workspace.readDocument(1, profile.bootScriptName)
            assertNotNull(bootScript)

            val artifact = LanguageServices.frontend.compile(bootScript.path, bootScript.text)
            assertTrue(
                artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
                artifact.analysis.diagnostics.joinToString { it.message },
            )

            val vm = BytecodeVirtualMachine(requireNotNull(artifact.module))
            val firstSignal = vm.runUntilSignal()
            assertEquals(
                VmSignal.HostCall("process", "run", listOf(VmValue.StringValue("shell.ck"))),
                firstSignal,
            )

            vm.resumeWith(VmValue.IntValue(0))
            val secondSignal = vm.runUntilSignal()
            assertEquals(VmSignal.Halt, secondSignal)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
