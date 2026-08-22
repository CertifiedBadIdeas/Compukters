/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteLimits
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic

internal object MinimalScriptLowering {
    fun lower(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
        session: CompilationSession,
    ) {
        val functions = SourceFunctionCollector().also { module.accept(it, null) }.functions
        val namedMain = functions.filter { it.name.asString() == "main" }
        if (namedMain.isNotEmpty()) {
            val validMain =
                namedMain.filter { function ->
                    function.parameters.isEmpty() &&
                        function.returnType == pluginContext.irBuiltIns.unitType
                }
            if (validMain.size != 1 || namedMain.size != 1) {
                session.diagnosticSink(invalidEntry(session, namedMain.firstOrNull()))
                return
            }
            val entry = validMain.single()
            val body = entry.body as? IrBlockBody
            if (body == null || body.statements.isNotEmpty()) {
                session.diagnosticSink(unsupported(session, entry))
                return
            }
            writeArtifact(session, mainArtifact(entry.isSuspend))
            return
        }

        val fields = SourceFieldCollector().also { module.accept(it, null) }.fields
        val field = fields.singleOrNull()
        val constant = field?.initializer?.expression as? IrConst
        val value = constant?.value as? Int
        if (field == null || field.type != pluginContext.irBuiltIns.intType || constant == null || value == null) {
            session.diagnosticSink(unsupported(session, field))
            return
        }

        writeArtifact(session, artifact(value))
    }

    private fun writeArtifact(
        session: CompilationSession,
        artifact: Artifact,
    ) {
        when (val result = ArtifactWriter.write(artifact, writeLimits(session))) {
            is ArtifactWriteResult.Success -> {
                session.artifactSink(BinaryValue.of(result.bytes))
            }

            is ArtifactWriteResult.Failure -> {
                result.errors.forEach { error ->
                    session.diagnosticSink(
                        WorkerDiagnostic(
                            DiagnosticSeverity.ERROR,
                            DiagnosticCategory.INTERNAL,
                            "ARTIFACT_WRITE_${error.code}",
                            "artifact writer rejected lowered IR: ${error.detail}",
                            null,
                            null,
                            null,
                        ),
                    )
                }
            }
        }
    }

    private fun writeLimits(session: CompilationSession) =
        ArtifactWriteLimits(
            artifactBytes = session.limits.artifactBytes,
            diagnostics = session.limits.diagnostics,
        )

    private fun invalidEntry(
        session: CompilationSession,
        function: IrSimpleFunction?,
    ) = WorkerDiagnostic(
        DiagnosticSeverity.ERROR,
        DiagnosticCategory.TARGET,
        "INVALID_ENTRY_POINT",
        "project must declare exactly one zero-argument fun main() or suspend fun main() returning Unit",
        session.virtualSourcePath(function?.file?.fileEntry?.name),
        function?.startOffset?.takeIf { it >= 0 }?.toUInt(),
        function?.endOffset?.takeIf { it >= 0 }?.toUInt(),
    )

    private fun unsupported(
        session: CompilationSession,
        element: IrElement?,
    ) = WorkerDiagnostic(
        DiagnosticSeverity.ERROR,
        DiagnosticCategory.TARGET,
        "UNSUPPORTED_IR",
        "source IR is outside the minimal script subset",
        session.virtualSourcePath((element as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.file?.fileEntry?.name),
        element?.startOffset?.takeIf { it >= 0 }?.toUInt(),
        element?.endOffset?.takeIf { it >= 0 }?.toUInt(),
    )

    private fun mainArtifact(suspending: Boolean): Artifact =
        Artifact(
            semanticFeatures = if (suspending) setOf(SemanticFeature.COROUTINES) else emptySet(),
            manifest = Manifest.minimal(maximumBlockCost = 1u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = listOf(MetadataText.of("app"), MetadataText.of("main")),
                        types =
                            listOf(
                                NominalType.Function(
                                    name = StringId.of(1u),
                                    suspending = suspending,
                                    result = ValueType.Unit,
                                    parameters = emptyList(),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    owner = null,
                                    name = StringId.of(1u),
                                    signature = TypeRef.Local(TypeId.of(0u)),
                                    flags = setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { suspending }),
                                    registers = emptyList(),
                                    parameterCount = 0u,
                                    firstBlock = BlockId.of(0u),
                                    blockCount = 1u,
                                    firstException = 0u,
                                    exceptionCount = 0u,
                                ),
                            ),
                        blocks = listOf(Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit)))),
                    ),
                ),
        )

    private fun artifact(value: Int): Artifact =
        Artifact(
            manifest = Manifest.minimal(maximumBlockCost = BLOCK_COST),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = listOf(MetadataText.of("app"), MetadataText.of("entry")),
                        types =
                            listOf(
                                NominalType.Function(
                                    name = StringId.of(1u),
                                    suspending = false,
                                    result = ValueType.Unit,
                                    parameters = emptyList(),
                                ),
                            ),
                        constants = listOf(Constant.I32(value)),
                        functions =
                            listOf(
                                Function(
                                    owner = null,
                                    name = StringId.of(1u),
                                    signature = TypeRef.Local(TypeId.of(0u)),
                                    flags = setOf(FunctionFlag.STATIC),
                                    registers = listOf(ValueType.I32),
                                    parameterCount = 0u,
                                    firstBlock = BlockId.of(0u),
                                    blockCount = 1u,
                                    firstException = 0u,
                                    exceptionCount = 0u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(
                                    owner = FunctionId.of(0u),
                                    loopHeaderSafepoint = false,
                                    instructions =
                                        listOf(
                                            Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                                            Instruction.Return(Destination.Unit),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    private const val BLOCK_COST = 2u
}

private class SourceFunctionCollector : IrVisitorVoid() {
    val functions = mutableListOf<IrSimpleFunction>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        if (declaration.startOffset >= 0 && declaration.parent is org.jetbrains.kotlin.ir.declarations.IrFile) {
            functions += declaration
        }
        super.visitSimpleFunction(declaration)
    }
}

private class SourceFieldCollector : IrVisitorVoid() {
    val fields = mutableListOf<IrField>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitField(declaration: IrField) {
        val initializerStart = declaration.initializer?.expression?.startOffset
        if (declaration.startOffset >= 0 && initializerStart != null && initializerStart >= 0) {
            fields += declaration
        }
        super.visitField(declaration)
    }
}
