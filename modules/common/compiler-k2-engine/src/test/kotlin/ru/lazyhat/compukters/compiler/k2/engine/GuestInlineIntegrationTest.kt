/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.ir.PreSerializationSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.link.LibraryModuleLinker
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Test-only feasibility adapter; unsupported backend hooks fail explicitly rather than inventing JVM storage. */
@OptIn(UnsafeDuringIrConstructionAPI::class, CompilerConfiguration.Internals::class)
class GuestInlineIntegrationTest {
    @Test
    fun `common inliner expands direct and generic callbacks using Guest builtins`() {
        probe(
            """
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            inline fun <T, R> transform(value: T, block: (T) -> R): R = block(value)
            fun main() {
                val first = apply(3) { it + 2 }
                val second = transform(first) { it * 2 }
                val third = transform(5000000000L) { it + 2L }
                val fourth = transform(true) { !it }
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertTrue(facts.blocks.isNotEmpty())
            assertTrue(!facts.erasedGenericValue, "specialized inline inputs must retain concrete types")
            assertEquals(
                0,
                assertNotNull(artifact)
                    .modules
                    .flatMap { it.blocks }
                    .flatMap { it.instructions }
                    .count { it is Instruction.NewObject },
                "scalar generic callbacks must not allocate boxes or closures",
            )
            assertTrue(facts.returns.any { it.returnTargetSymbol.owner is IrReturnableBlock })
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `common inliner preserves local and non-local return targets and mutable captures`() {
        probe(
            """
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            fun early(): Int {
                return apply(3) { return 7 }
            }
            fun main() {
                var bias = 1
                val first = apply(3) { value -> bias = bias + value; bias }
                val second = apply(first) label@{ value -> if (value > 0) return@label 9; 0 }
                val third = early()
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertTrue(facts.returns.any { it.returnTargetSymbol.owner is IrReturnableBlock })
            assertTrue(facts.biasWrites > 0, "inlining must preserve captured local writes")
            assertTrue(facts.nonLocalReturn, "return 7 must still target early(), not an inline block")
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `common inliner resolves source-library bodies and preserves array loop origins`() {
        probe(
            source =
                """
                import probe.foldAll
                fun main() {
                    val result = foldAll(intArrayOf(1, 2), 0) { sum, value -> sum + value }
                }
                """.trimIndent(),
            librarySource =
                """
                package probe
                inline fun <T> identity(value: T, block: (T) -> T): T = block(value)
                inline fun <T> foldAll(values: IntArray, initial: T, operation: (T, Int) -> T): T {
                    var result = identity(initial) { it }
                    for (value in values) result = operation(result, value)
                    return result
                }
                """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertEquals(1, facts.forLoops)
            assertTrue(facts.blocks.isNotEmpty())
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `inline blocks emit executable nested local non-local Unit and wide results`() {
        probe(
            """
            class Box(var value: Int)
            fun next(box: Box): Int { box.value += 1; return box.value }
            fun verify(value: Boolean) {
                if (!value) { val zero = 0; val failure = 1 / zero }
            }
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            inline fun <T, R> transform(value: T, block: (T) -> R): R = block(value)
            inline fun <T, R> forward(value: T, block: (T) -> R): R = transform(value, block)
            inline fun effect(block: () -> Unit) { block() }
            inline fun terminal(block: () -> Nothing): Nothing = block()
            inline fun wide(value: Long, block: (Long) -> Long): Long = block(value)
            inline fun reference(value: Box, block: (Box) -> Box): Box = block(value)
            inline fun nullable(block: () -> Int?): Int? = block()
            inline fun fold(values: IntArray, initial: Int, block: (Int, Int) -> Int): Int {
                var result = initial
                for (value in values) result = block(result, value)
                return result
            }
            fun early(): Int {
                val value = forward(2) { if (it > 0) return 9; 3 }
                return value + 100
            }
            fun both(flag: Boolean): Int = apply(2) { if (flag) return 11 else return 12 }
            fun fromNothing(): Int { terminal { return 17 } }
            fun genericNothing(): Int { forward(2) { return 23 } }
            fun nestedBoth(flag: Boolean): Int =
                if (flag) apply(2) { return 21 } else apply(3) { return 22 }
            fun nested(): Int = apply(1) outer@{ first ->
                val second = apply(2) { if (it > 0) return@outer 7; 5 }
                first + second
            }
            fun main() {
                val counter = Box(0)
                verify(apply(next(counter)) { it + it } == 2)
                verify(counter.value == 1)
                verify(transform(3) { it + 2 } == 5)
                verify(forward(5000000000L) { it + 2L } == 5000000002L)
                verify(forward(true) { !it } == false)
                verify(forward<Int?, Int?>(null) { it } == null)
                verify(forward<Int?, Int?>(4) { it } == 4)
                verify(forward(counter) { it } === counter)
                verify(forward(3) label@{ if (it > 0) return@label 8; 0 } == 8)
                var bias = 1
                verify(apply(3) { bias += it; bias } == 4)
                verify(bias == 4)
                verify(apply(3) label@{ if (it == 3) return@label 7; 9 } == 7)
                verify(early() == 9)
                verify(both(true) == 11)
                verify(both(false) == 12)
                verify(nested() == 7)
                verify(nestedBoth(true) == 21)
                verify(nestedBoth(false) == 22)
                verify(fromNothing() == 17)
                verify(genericNothing() == 23)
                effect { bias += 1; return@effect }
                verify(bias == 5)
                effect { bias += 1 }
                effect { if (bias > 0) return@effect; bias = 99 }
                verify(bias == 6)
                forward(2) { bias += it }
                verify(bias == 8)
                verify(wide(5000000000L) { it + 2L } == 5000000002L)
                val box = Box(1)
                val returned = reference(box) { it.value = 8; it }
                verify(returned === box && box.value == 8)
                verify(nullable { null } == null)
                verify(nullable { 4 } == 4)
                verify(fold(intArrayOf(1, 2, 3), 0) { sum, value -> sum + value } == 6)
                verify(fold(intArrayOf(), 17) { sum, value -> sum + value } == 17)
                // Rust expects this distinct trap only after every preceding assertion has executed.
                val completion = IntArray(-1)
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertEquals(0, facts.richReferences)
            val linked = LibraryModuleLinker.link(assertNotNull(artifact), emptyMap<String, Module>())
            val encoded = ArtifactWriter.write(linked)
            assertTrue(encoded is ArtifactWriteResult.Success, encoded.toString())
            System.getProperty("compukter.vm.inlineBlocksArtifact")?.let { output ->
                val path = Path.of(output)
                Files.createDirectories(path.parent)
                Files.write(path, encoded.bytes)
            }
        }
    }

    @Test
    fun `unknown inline return targets produce located diagnostics`() {
        probe(
            source =
                """
                inline fun apply(block: () -> Int): Int = block()
                fun main() { val result = apply { 7 } }
                """.trimIndent(),
            invalidReturnTarget = true,
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertTrue(diagnostics.single().message.contains("return target is outside"), diagnostics.toString())
        }
    }

    @Test
    fun `generic specialization reuses a concrete variant within the limit`() {
        probe(
            """
            inline fun <T> identity(value: T): T { var result = value; result = value; return result }
            fun main() { val a = identity(1); val b = identity(2) }
            """.trimIndent(),
            maximumVariants = 1,
        ) { _, facts, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertTrue(!facts.erasedGenericValue)
            assertEquals(0, facts.inlineCalls)
            assertNotNull(artifact)
        }
    }

    @Test
    fun `generic specialization rejects variant and nesting limits explicitly`() {
        val variants =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> identity(value: T): T = value
                    fun main() { val a = identity(1); val b = identity(2L) }
                    """.trimIndent(),
                    maximumVariants = 1,
                ) { _, _, _, _ -> error("limit must fail before emission") }
            }
        assertTrue(variants.message.orEmpty().contains("variant limit"))
        val depth =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> identity(value: T): T = value
                    inline fun <T> forward(value: T): T = identity(value)
                    fun main() { val a = forward(1) }
                    """.trimIndent(),
                    maximumDepth = 1,
                ) { _, _, _, _ -> error("limit must fail before emission") }
            }
        assertTrue(depth.message.orEmpty().contains("depth limit"))
    }

    @Test
    fun `generic specialization rejects recursive expansion explicitly`() {
        val recursion =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> recursive(value: T): T = recursive(value)
                    fun main() { val a = recursive(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("recursion must fail before emission") }
            }
        assertTrue(recursion.message.orEmpty().contains("recursive generic inline"))
    }

    @Test
    fun `generic specialization rejects reified and member templates explicitly`() {
        val reified =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <reified T> identity(value: T): T = value
                    fun main() { val a = identity(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("unsupported shape must fail before emission") }
            }
        assertTrue(reified.message.orEmpty().contains("reified inline"))
        val member =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    class Owner { inline fun <T> identity(value: T): T = value }
                    fun main() { val a = Owner().identity(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("unsupported shape must fail before emission") }
            }
        assertTrue(member.message.orEmpty().contains("top-level declaration"))
    }

    private fun probe(
        source: String,
        librarySource: String? = null,
        invalidReturnTarget: Boolean = false,
        maximumVariants: Int = 256,
        maximumDepth: Int = 64,
        check: (IrSimpleFunction, InlineFacts, List<WorkerDiagnostic>, Artifact?) -> Unit,
    ) {
        val builtinsRoot = Path.of(checkNotNull(System.getProperty("compukters.guest.builtins")))
        val builtinsSources =
            Files.walk(builtinsRoot).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .sorted()
                    .map { path ->
                        PlatformSource(builtinsRoot.relativize(path).toString(), ImmutableBytes.of(Files.readAllBytes(path)))
                    }.toList()
            }
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins = environment.compile(PlatformModuleId("kotlin", "builtins"), builtinsSources, emptyList())
            val library =
                librarySource?.let {
                    environment.compile(
                        PlatformModuleId("probe", "library"),
                        listOf(PlatformSource("Library.kt", ImmutableBytes.of(it.encodeToByteArray()))),
                        listOf(builtins),
                    )
                }
            val dependencies = listOfNotNull(builtins, library)
            val project =
                environment.compile(
                    PlatformModuleId("probe", "inline"),
                    listOf(PlatformSource("Main.kt", ImmutableBytes.of(source.encodeToByteArray()))),
                    dependencies,
                )
            val converted = CompuktersFir2IrPipeline.convert(dependencies + project)
            val context =
                object : LoweringContext {
                    override val configuration = CompilerConfiguration()
                    override var inVerbosePhase = false
                    override val irBuiltIns = converted.pluginContext.irBuiltIns
                    override val irFactory = converted.pluginContext.irFactory
                    override val messageCollector = MessageCollector.NONE
                    override val symbols: PreSerializationSymbols get() = error("probe reached backend-specific symbols")
                    override val sharedVariablesManager: SharedVariablesManager get() =
                        error(
                            "probe reached backend shared-variable lowering",
                        )
                }
            val resolver =
                object : InlineFunctionResolver() {
                    override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? =
                        symbol.owner.takeIf { (it as? IrSimpleFunction)?.isInline == true && it.body != null }
                }
            val inliner = object : FunctionInlining(context, resolver) {}
            val file = converted.irModuleFragment.files.single { it.fileEntry.name.endsWith("Main.kt") }
            val before = InlineFacts(context.irBuiltIns.anyNType).also { file.accept(it, null) }
            val specialization = GuestInlineSpecialization(maximumVariants, maximumDepth)
            val templates =
                converted.irModuleFragment.files
                    .flatMap { it.declarations }
                    .filterIsInstance<IrSimpleFunction>()
                    .filter { it.typeParameters.isNotEmpty() }
                    .associateWith { it.dump() }
            converted.irModuleFragment.files
                .toList()
                .forEach { specialization.lower(it) }
            templates.forEach { (template, original) -> assertEquals(original, template.dump(), "generic template mutated") }
            converted.irModuleFragment.files.forEach { sourceFile ->
                UpgradeCallableReferences(
                    context,
                    upgradeFunctionReferencesAndLambdas = true,
                    upgradePropertyReferences = false,
                    upgradeLocalDelegatedPropertyReferences = false,
                    upgradeSamConversions = false,
                    upgradeExtractedAdaptedBlocks = false,
                    castDispatchReceiver = false,
                    generateFakeAccessorsForReflectionProperty = false,
                ).lower(sourceFile)
            }
            file.declarations.filterIsInstance<IrFunction>().forEach { declaration ->
                declaration.body?.let { inliner.lower(it, declaration) }
            }
            converted.irModuleFragment.files.retainAll(listOf(file))
            val main = file.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "main" }
            val facts =
                InlineFacts(context.irBuiltIns.anyNType).also { facts ->
                    file.declarations
                        .filterIsInstance<IrSimpleFunction>()
                        .filterNot { it.isInline }
                        .forEach { it.accept(facts, null) }
                }
            if (invalidReturnTarget) {
                val foreign = file.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "apply" }
                facts.returns.first { it.returnTargetSymbol.owner is IrReturnableBlock }.returnTargetSymbol = foreign.symbol
            }
            val diagnostics = mutableListOf<WorkerDiagnostic>()
            val artifact =
                MinimalScriptLowering.lower(
                    converted.irModuleFragment,
                    converted.pluginContext,
                    CompilationSession(
                        irSink = { _, _ -> },
                        diagnosticSink = { diagnostics += it },
                        sourcePaths = mapOf(file.fileEntry.name to VirtualSourcePath.of("project/Main.kt")),
                    ),
                )
            assertTrue(diagnostics.all { it.code == "UNSUPPORTED_IR" && it.path != null })
            check(main, facts, diagnostics, artifact)
            println(
                "inline probe: original nodes=${before.nodes}, executable expanded nodes=${facts.nodes}, " +
                    "blocks=${facts.blocks.size}, richReferences=${facts.richReferences}, diagnostics=$diagnostics",
            )
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class InlineFacts(
    private val anyNullableType: IrType,
) : IrVisitorVoid() {
    val blocks = mutableListOf<IrReturnableBlock>()
    val returns = mutableListOf<IrReturn>()
    var nodes = 0
    var richReferences = 0
    var lambdas = 0
    var inlineCalls = 0
    var nonLocalReturn = false
    var erasedGenericValue = false
    var biasWrites = 0
    var forLoops = 0

    override fun visitElement(element: IrElement) {
        nodes += 1
        element.acceptChildren(this, null)
    }

    override fun visitVariable(declaration: IrVariable) {
        if (declaration.type == anyNullableType) erasedGenericValue = true
        super.visitVariable(declaration)
    }

    override fun visitSetValue(expression: IrSetValue) {
        if (expression.symbol.owner.name
                .asString() == "bias"
        ) {
            biasWrites += 1
        }
        super.visitSetValue(expression)
    }

    override fun visitBlock(expression: IrBlock) {
        if (expression.origin?.toString() == "FOR_LOOP") forLoops += 1
        super.visitBlock(expression)
    }

    override fun visitReturnableBlock(expression: IrReturnableBlock) {
        blocks += expression
        super.visitReturnableBlock(expression)
    }

    override fun visitReturn(expression: IrReturn) {
        returns += expression
        if ((expression.returnTargetSymbol.owner as? IrSimpleFunction)?.name?.asString() == "early" &&
            (expression.value as? IrConst)?.value == 7
        ) {
            nonLocalReturn = true
        }
        super.visitReturn(expression)
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        richReferences += 1
        super.visitRichFunctionReference(expression)
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression) {
        lambdas += 1
        super.visitFunctionExpression(expression)
    }

    override fun visitCall(expression: IrCall) {
        if (expression.symbol.owner.isInline) inlineCalls += 1
        super.visitCall(expression)
    }
}
