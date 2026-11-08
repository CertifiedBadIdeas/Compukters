/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeGuestContractBuildTest {
    @Test
    fun `expanded native interfaces compile executable helpers and preserve logical ABI identities`() =
        build(
            """
            import kotlin.math.Vector3d
            import kotlin.math.Rotation3d
            value class Velocity(val vector: Vector3d) {
                fun direction(): Vector3d = vector.normalized()
            }
            data class State(val velocity: Velocity, val orientation: Rotation3d) {
                fun direction(): Vector3d = velocity.direction()
            }
            @GuestResource interface Token {
                @GuestRead fun value(): Double
                fun twice(): Double = value() * 2.0
            }
            @GuestDevice("fixture:engine", bindings = "EngineBindings") interface Engine {
                @GuestRead fun state(): State
                @GuestRead fun bounds(): kotlin.math.Rect2i
                @GuestCommand(binding = "aim") fun aim(direction: Vector3d): Unit
                @GuestCommand fun target(state: State): Unit
                @GuestCall fun open(): Token
                @GuestCommand fun consume(token: Token): Unit
                fun direction(): Vector3d = state().direction()
            }
            @GuestService(bindings = "Space") interface Space {
                companion object { const val SCALE: Double = 2.0 }
                @GuestRead(binding = "time") fun time(): Double
                fun twice(): Double = time() * SCALE
            }
            fun use(): Double {
                val engine = Engine.first()
                engine.aimCommand(engine.direction())
                val token = engine.open()
                engine.consumeCommand(token)
                return Space.twice() + token.twice()
            }
            """.trimIndent(),
            AddonAbiLock.parse(
                """
                lock 1
                operation fixture fixture 1 0 active fixture.Space.time fun():Double
                operation fixture fixture 1 1 tombstone fixture.Old.obsolete fun():Unit
                operation fixture fixture 1 2 active fixture.EngineBindings.aim fun(Int,Double,Double,Double):Unit
                """.trimIndent().lines(),
            ),
        ) { resolved, bundle, declaration ->
            assertEquals(
                0,
                resolved.expectedLock.entries
                    .single { it.symbol == "fixture.Space.time" }
                    .operation,
            )
            assertEquals(
                2,
                resolved.expectedLock.entries
                    .single { it.symbol == "fixture.EngineBindings.aim" }
                    .operation,
            )
            assertEquals(
                AddonAbiEntryState.TOMBSTONE,
                resolved.expectedLock.entries
                    .single { it.operation == 1 }
                    .state,
            )
            assertTrue(bundle.moduleDescriptor.declarations.any { it.symbol == "fixture.Engine" && it.signature == "interface()" })
            assertTrue(bundle.moduleDescriptor.declarations.any { it.symbol == "fixture.Velocity.direction" })
            assertTrue(bundle.moduleDescriptor.declarations.any { it.symbol == "fixture.Engine.direction" })
            assertTrue(bundle.bindings.any { it.symbol == "fixture.__CompuktersSpace.time" && it.operation == 0 })
            val host = renderAddonHostContract(AddonAuthoringContract("fixture", "1.0.0"), resolved.contract, declaration)
            assertTrue("argument1: Vector3d" in host)
            assertTrue("ProgramCommandBinding(\"fixture.__CompuktersEngineBindings.aim\"" in host)
            assertEquals(
                generateGuestContractSources(declaration),
                bundle.moduleDescriptor.sources.associate { it.path to it.content.toByteArray().decodeToString() },
            )
        }

    @Test
    fun `pure executable Guest library compiles without server selectors`() =
        build(
            """
            import kotlin.math.Vector3d
            value class Velocity(val x: Double, val y: Double, val z: Double) {
                fun direction(): Vector3d = Vector3d(x, y, z).normalized()
            }
            fun direction(value: Velocity): Vector3d = value.direction()
            """.trimIndent(),
        ) { resolved, bundle, _ ->
            assertTrue(resolved.expectedLock.entries.isEmpty())
            assertTrue(bundle.capabilitySchemas.isEmpty())
            assertTrue(bundle.bindings.isEmpty())
            assertTrue(bundle.moduleDescriptor.declarations.any { it.symbol == "fixture.Velocity.direction" })
        }

    private fun build(
        text: String,
        lock: AddonAbiLock = AddonAbiLock.empty(),
        block: (ResolvedAddonContract, ru.lazyhat.compukters.addon.api.AddonGuestApiBundle, DeclarativeGuestContract) -> Unit,
    ) {
        val root = Files.createTempDirectory("compukters-native-build-")
        try {
            val input = root.resolve("input")
            input.resolve("fixture/Contract.kt").apply {
                parent.createDirectories()
                writeText("package fixture\nimport ru.lazyhat.compukters.api.contracts.*\n$text")
            }
            val platform =
                PlatformBundleCodec.decode(
                    Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.native.contract.platform")))),
                )
            val declaration = analyzeNativeGuestContract(platform, input, "fixture")
            val generated = root.resolve("generated")
            writeGeneratedGuestSources(declaration, generated)
            val resolved =
                resolveAddonContract(
                    generated,
                    AddonAuthoringContract("fixture", "1.0.0"),
                    lock,
                    platform.modules.map { it.id },
                    bindingAliases = declaration.bindingAliases,
                    resolvedRecords = nativeResultSchemas(declaration),
                    allowExecutableOnly = true,
                )
            val bundle = AddonGuestApiBundleCodec.decode(buildAddonGuestApiBundle(platform, generated, resolved.contract))
            block(resolved, bundle, declaration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
