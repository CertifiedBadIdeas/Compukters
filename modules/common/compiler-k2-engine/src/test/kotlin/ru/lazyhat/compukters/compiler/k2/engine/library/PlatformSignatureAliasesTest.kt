/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.library

import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformSignatureAliasesTest {
    @Test
    fun `source alias signatures expand across receivers parameters returns and dependent modules`() {
        val aliases = aliases("kotlin.math.Position2i" to "Vector2i", "kotlin.math.Size2i" to "Position2i")
        assertEquals(
            "fun(Vector2i.Vector2i):Vector2i",
            aliases.expand("fun(Position2i.Size2i):Position2i", "compukter.display.place"),
        )
        assertEquals("fun(Vector2i):Vector2i?", aliases.expand("fun(kotlin.math.Size2i):Position2i?", "sample.use"))
        assertEquals("fun(Int.Int):Int", aliases.expand("fun(Int.Int):Int", "sample.use"))
    }

    @Test
    fun `ambiguous short aliases use local scope and reject unmatched scope`() {
        val aliases = aliases("first.Position" to "Vector2i", "second.Position" to "Vector3i")
        assertEquals("fun():Vector2i", aliases.expand("fun():Position", "first.create"))
        assertEquals("fun():Vector3i", aliases.expand("fun():second.Position", "first.create"))
        assertFailsWith<IllegalStateException> { aliases.expand("fun():Position", "third.create") }
    }

    @Test
    fun `cyclic alias signatures fail without unbounded expansion`() {
        val aliases = aliases("sample.First" to "Second", "sample.Second" to "First")
        assertFailsWith<IllegalArgumentException> { aliases.expand("fun():First", "sample.create") }
    }

    private fun aliases(vararg declarations: Pair<String, String>): PlatformSignatureAliases {
        val id = PlatformModuleId("stdlib", "core")
        val module =
            PlatformModule(
                id,
                "test",
                emptyList(),
                ImmutableBytes.of(byteArrayOf()),
                null,
                emptyList(),
                declarations.map { (symbol, target) -> PlatformDeclaration(symbol, "typealias:$target", id, "aliases.kt", 0, 1, false) },
                emptyList(),
            )
        return PlatformSignatureAliases(listOf(module))
    }
}
