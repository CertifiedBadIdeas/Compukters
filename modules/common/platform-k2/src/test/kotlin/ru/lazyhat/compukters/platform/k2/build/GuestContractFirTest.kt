/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.platform.k2.build

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.contracts.GuestContractFirExtensionRegistrar
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestContractFirTest {
    @Test
    fun `device remains a native interface with discovery commands and executable stdlib methods`() =
        analyze(
            """
            package fixture
            import ru.lazyhat.compukters.api.contracts.*
            import compukter.peripheral.Peripheral
            import compukter.commands.Command
            import kotlin.math.Vector3d

            value class Velocity(val x: Double, val y: Double, val z: Double) {
                fun direction(): Vector3d = Vector3d(x, y, z).normalized()
            }
            @GuestDevice("fixture:engine")
            interface Engine {
                @GuestCall fun velocity(): Velocity
                @GuestCall fun setThrottle(value: Double): Unit
                fun direction(): Vector3d = velocity().direction()
            }
            fun use(): Vector3d {
                val engine: Engine = Engine.first()
                val peripheral: Peripheral = engine
                val command: Command<Unit> = engine.setThrottleCommand(0.4)
                val read: Command<Velocity> = engine.velocityCommand()
                return engine.direction()
            }
            """.trimIndent(),
        ) { output ->
            val symbol =
                output.frontendOutput.session.symbolProvider.getClassLikeSymbolByClassId(
                    ClassId.topLevel(FqName("fixture.Engine")),
                )
            assertEquals(ClassKind.INTERFACE, (symbol as FirRegularClassSymbol).classKind)
        }

    @Test
    fun `service companion exposes operations without replacing the interface`() =
        analyze(
            """
            package fixture
            import ru.lazyhat.compukters.api.contracts.*
            import kotlin.math.Vector3d
            @GuestService interface Space {
                @GuestCall fun position(): Vector3d
                fun distance(): Double = position().length
            }
            fun use(): Double = Space.distance() + Space.position().x
            """.trimIndent(),
        ) {}

    @Test
    fun `declared companions retain constants and ordinary methods alongside generated providers`() =
        analyze(
            """
            package fixture
            import ru.lazyhat.compukters.api.contracts.*
            import kotlin.math.Vector3d
            @GuestDevice("fixture:engine") interface Engine {
                companion object { const val DEFAULT: Double = 0.5 }
                @GuestCall fun setThrottle(value: Double): Unit
            }
            @GuestService interface Space {
                companion object {
                    const val SECONDS: Double = 0.05
                    fun twice(): Double = SECONDS * 2.0
                }
                @GuestCall fun time(): Double
            }
            fun use(): Double {
                Engine.first().setThrottleCommand(Engine.DEFAULT)
                return Space.time() * Space.twice()
            }
            """.trimIndent(),
        ) {}

    @Test
    fun `real Guest type errors remain diagnostics in ordinary addon bodies`() =
        analyze(
            """
            package fixture
            import ru.lazyhat.compukters.api.contracts.*
            import kotlin.math.Vector3d
            @GuestDevice("fixture:engine") interface Engine {
                @GuestCall fun aim(value: Vector3d): Unit
            }
            fun use() { Engine.first().aimCommand("invalid") }
            """.trimIndent(),
            errors = true,
        ) { output ->
            assertTrue(output.diagnostics.diagnostics.any { it.factoryName == "ARGUMENT_TYPE_MISMATCH" })
        }

    private fun analyze(
        text: String,
        errors: Boolean = false,
        block: (CompuktersFirModuleOutput) -> Unit,
    ) {
        val root = Path.of(requireNotNull(System.getProperty("compukters.guest.platform.sources")))

        fun sources(relative: String): List<PlatformSource> =
            Files.walk(root.resolve(relative)).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .sorted()
                    .map {
                        PlatformSource(root.relativize(it).invariantSeparatorsPathString, ImmutableBytes.of(Files.readAllBytes(it)))
                    }.toList()
            }
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins = environment.compile(PlatformModuleId("kotlin", "builtins"), sources("builtins"), emptyList())
            val stdlib = environment.compile(PlatformModuleId("stdlib", "core"), sources("libraries/stdlib-core"), listOf(builtins))
            val core =
                environment.compile(
                    PlatformModuleId("compukter", "core"),
                    sources("libraries/compukter-core"),
                    listOf(builtins, stdlib),
                )
            assertTrue(
                builtins.diagnostics.diagnostics.all {
                    it.factoryName == "WRONG_MODIFIER_TARGET" &&
                        it.renderMessage() == "Modifier 'external' is not applicable to 'constructor'."
                },
            )
            listOf(stdlib, core).forEach { output ->
                assertFalse(
                    output.diagnostics.hasErrors,
                    output.diagnostics.diagnostics.joinToString { "${it.factoryName}: ${it.renderMessage()}" },
                )
            }
            val output =
                environment.compile(
                    PlatformModuleId("fixture", "api"),
                    listOf(PlatformSource("fixture/Fixture.kt", ImmutableBytes.of(text.encodeToByteArray()))),
                    listOf(builtins, stdlib, core),
                    listOf(GuestContractFirExtensionRegistrar()),
                )
            assertEquals(
                errors,
                output.diagnostics.hasErrors,
                output.diagnostics.diagnostics.joinToString {
                    "${it.factoryName}: ${it.renderMessage()}"
                },
            )
            block(output)
        }
    }
}
