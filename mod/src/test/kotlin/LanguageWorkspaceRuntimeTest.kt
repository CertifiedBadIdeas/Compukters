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

import ru.lazyhat.ck.lang.frontend.FrontendSeverity
import ru.lazyhat.ck.lang.runtime.BytecodeVirtualMachine
import ru.lazyhat.ck.lang.runtime.VmSignal
import ru.lazyhat.ck.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.block.ComputerFamily
import ru.lazyhat.compukterkraft.computer.vm.ComputerProfileRegistry
import ru.lazyhat.compukterkraft.computer.vm.FileComputerWorkspace
import ru.lazyhat.compukterkraft.language.LanguageServices
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageWorkspaceRuntimeTest {
    @Test
    fun seededBiosCompilesAndStartsEventLoop() {
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
                VmSignal.HostCall("terminal", "printLine", listOf(VmValue.StringValue("Compukter Kraft ready"))),
                firstSignal,
            )

            vm.resumeWith(VmValue.UnitValue)
            val secondSignal = vm.runUntilSignal()
            assertEquals(VmSignal.WaitEvent(null), secondSignal)

            vm.resumeWith(
                VmValue.RecordValue(
                    typeName = "Event",
                    fields = mapOf("name" to VmValue.StringValue("boot")),
                ),
            )
            val thirdSignal = vm.runUntilSignal()
            assertEquals(
                VmSignal.HostCall("terminal", "printLine", listOf(VmValue.StringValue("boot"))),
                thirdSignal,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
