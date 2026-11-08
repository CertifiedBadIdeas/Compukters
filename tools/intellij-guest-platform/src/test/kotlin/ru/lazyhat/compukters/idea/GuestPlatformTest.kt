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

package ru.lazyhat.compukters.idea

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms
import java.nio.file.Files
import java.nio.file.Path

/** Runs inside the real installed IntelliJ/Kotlin plugin, not standalone Analysis API. */
@OptIn(org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class)
class GuestPlatformTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("compukters-guest.properties", "platform=${System.getProperty("compukters.prototype.platform")}\n")
    }

    fun testGuestMfvcAndRealStdlibResolution() {
        val fixture = Path.of(System.getProperty("compukters.prototype.fixture"))
        val file =
            myFixture.addFileToProject(
                "prototype/flight/Velocity.kt",
                Files.readString(fixture.resolve("library/prototype/flight/Velocity.kt")),
            ) as KtFile
        allowAnalysisOnEdt {
            runReadActionBlocking {
                val module = KaModuleProvider.getModule(project, file, null)
                assertEquals(CompuktersPlatforms.default, module.targetPlatform)
                analyze(file) {
                    val errors =
                        file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter {
                            it.severity.name ==
                                "ERROR"
                        }
                    assertTrue(errors.joinToString { "${it.factoryName}: ${it.defaultMessage}" }, errors.isEmpty())
                }
            }
        }
    }

    fun testStdlibMemberCompletion() {
        myFixture.configureByText("Completion.kt", "import kotlin.math.Vector3d\nfun direction(v: Vector3d) { v.nor<caret> }")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(
            "Expected normalized completion; received $items; text=${myFixture.file.text}",
            "normalized" in items || "v.normalized" in myFixture.file.text,
        )
    }

    fun testNativeContractAndExecutableMfvc() {
        val file =
            myFixture.configureByText(
                "NativeContract.kt",
                """
                package fixture
                import ru.lazyhat.compukters.api.contracts.*
                import kotlin.math.Vector3d
                import compukter.commands.Command
                value class Velocity(val x: Double, val y: Double, val z: Double) {
                    fun direction(): Vector3d = Vector3d(x, y, z).normalized()
                }
                @GuestDevice("fixture:engine") interface Engine {
                    @GuestRead fun velocity(): Velocity
                    @GuestCommand fun aim(direction: Vector3d): Unit
                    fun direction(): Vector3d = velocity().direction()
                }
                fun use(): Vector3d {
                    val engine: Engine = Engine.first()
                    val command: Command<Unit> = engine.aimCommand(engine.direction())
                    return engine.direction()
                }
                """.trimIndent(),
            ) as KtFile
        allowAnalysisOnEdt {
            runReadActionBlocking {
                analyze(file) {
                    val errors =
                        file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter {
                            it.severity.name ==
                                "ERROR"
                        }
                    assertTrue(errors.joinToString { "${it.factoryName}: ${it.defaultMessage}" }, errors.isEmpty())
                }
            }
        }
    }

    fun testNativeCommandFactoryCompletion() {
        myFixture.configureByText(
            "CommandCompletion.kt",
            """
            import ru.lazyhat.compukters.api.contracts.*
            @GuestDevice("fixture:engine") interface Engine {
                @GuestCommand fun setThrottle(value: Double): Unit
            }
            fun use(engine: Engine) { engine.setTh<caret> }
            """.trimIndent(),
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("Expected generated command factory; received $items", "setThrottleCommand" in items)
        assertTrue("Expected ordinary operation; received $items", "setThrottle" in items)
    }

    fun testNativeProviderCompletion() {
        myFixture.configureByText(
            "ProviderCompletion.kt",
            """
            import ru.lazyhat.compukters.api.contracts.*
            @GuestDevice("fixture:engine") interface Engine {
                @GuestRead fun speed(): Double
            }
            fun use() { Engine.fi<caret> }
            """.trimIndent(),
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("Expected typed provider completion; received $items", "first" in items)
    }

    fun testServerOperationBoundariesHaveNativeDiagnostics() {
        val file =
            myFixture.configureByText(
                "InvalidContract.kt",
                """
                import ru.lazyhat.compukters.api.contracts.*
                abstract class Accidental { @GuestRead abstract fun value(): Int }
                @GuestService interface Control {
                    @GuestCommand fun invalidResult(): Int
                    @GuestRead fun invalidBody(): Int = 42
                    fun ordinaryBody(): Int = 42
                }
                """.trimIndent(),
            ) as KtFile
        allowAnalysisOnEdt {
            runReadActionBlocking {
                analyze(file) {
                    val errors =
                        file
                            .collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                            .filter { it.severity.name == "ERROR" }
                    assertTrue(errors.joinToString { it.defaultMessage }, errors.any { "must have no body" in it.defaultMessage })
                    assertTrue(errors.joinToString { it.defaultMessage }, errors.any { "must return Unit" in it.defaultMessage })
                    assertTrue(errors.joinToString { it.defaultMessage }, errors.any { "annotated interface" in it.defaultMessage })
                }
            }
        }
    }

    fun testCompiledAddonLibraryConsumer() {
        val fixture = Path.of(System.getProperty("compukters.prototype.fixture"))
        val file = myFixture.addFileToProject("main.kt", Files.readString(fixture.resolve("consumer/main.kt"))) as KtFile
        allowAnalysisOnEdt {
            runReadActionBlocking {
                analyze(file) {
                    val errors =
                        file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter {
                            it.severity.name ==
                                "ERROR"
                        }
                    assertTrue(errors.joinToString { "${it.factoryName}: ${it.defaultMessage}" }, errors.isEmpty())
                }
            }
        }
    }

    fun testCompiledAddonMemberCompletion() {
        myFixture.configureByText(
            "AddonCompletion.kt",
            "import prototype.flight.Velocity\nfun v(velocity: Velocity) { velocity.dir<caret> }",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(
            "Expected direction completion; received $items; text=${myFixture.file.text}",
            "direction" in items || "velocity.direction" in myFixture.file.text,
        )
    }

    fun testTypeErrorsUseGuestSignatures() {
        val file =
            myFixture.configureByText(
                "Invalid.kt",
                "import kotlin.math.Vector3d\nfun bad() = Vector3d(\"wrong\", 0.0, 0.0)",
            ) as KtFile
        allowAnalysisOnEdt {
            runReadActionBlocking {
                analyze(file) {
                    val errors =
                        file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter {
                            it.severity.name ==
                                "ERROR"
                        }
                    assertTrue(errors.joinToString { it.factoryName }, errors.any { it.factoryName == "ARGUMENT_TYPE_MISMATCH" })
                }
            }
        }
    }

    fun testNavigationOpensActualStdlibSource() {
        val file = myFixture.configureByText("Navigation.kt", "import kotlin.math.Vector3d\nfun v() = Vec<caret>tor3d(1.0, 2.0, 3.0)")
        allowAnalysisOnEdt {
            runReadActionBlocking {
                val targets =
                    GuestSourceNavigation().getGotoDeclarationTargets(
                        file.findElementAt(myFixture.caretOffset),
                        myFixture.caretOffset,
                        myFixture.editor,
                    )
                assertNotNull(targets)
                val source = targets!!.single().containingFile
                assertTrue(source.text.contains("value class Vector3d"))
                assertTrue(source.text.contains("fun normalized"))
            }
        }
    }
}
