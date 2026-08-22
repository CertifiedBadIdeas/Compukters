/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Import
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
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
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter

internal class UnsupportedKotlinIr(
    val element: IrElement,
    message: String,
) : IllegalArgumentException(message)

internal object KotlinProjectLowering {
    fun lower(
        functions: List<IrSimpleFunction>,
        entry: IrSimpleFunction,
        pluginContext: IrPluginContext,
        session: CompilationSession,
    ): Artifact {
        val userFunctions =
            functions
                .filterNot { session.trustedApiIdentity(it.file.fileEntry.name) != null }
                .sortedWith(
                    compareBy<IrSimpleFunction>(
                        { if (it === entry) 0 else 1 },
                        { session.virtualSourcePath(it.file.fileEntry.name)?.value.orEmpty() },
                        { it.startOffset },
                        { it.name.asString() },
                    ),
                )
        require(userFunctions.firstOrNull() === entry)
        userFunctions.forEach { validateFunction(it, pluginContext, entry) }

        val metadataValues =
            (listOf("app", "compukter", "terminal") + userFunctions.map { it.name.asString() })
                .distinct()
                .map(MetadataText::of)
                .sorted()
        val metadataIds = metadataValues.withIndex().associate { (index, value) -> value.toString() to StringId.of(index.toUInt()) }
        val literals =
            StringLiteralCollector()
                .also { userFunctions.forEach { function -> function.accept(it, null) } }
                .values
                .distinct()
                .map {
                    Utf16Literal.fromString(it)
                }.sorted()
        val literalIds = literals.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        val constants = literals.map { Constant.StringLiteral(requireNotNull(literalIds[it])) }
        val constantIds = literals.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }

        val library = stringLibrary()
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val stringType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(0u)))
        val functionIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to FunctionId.of(index.toUInt()) }
        val typeIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to TypeId.of(index.toUInt()) }
        val blocks = mutableListOf<Block>()
        val loweredFunctions = mutableListOf<Function>()

        userFunctions.forEach { function ->
            val functionId = requireNotNull(functionIds[function.symbol])
            val firstBlock = blocks.size
            val compiler =
                FunctionCompiler(
                    function = function,
                    functionId = functionId,
                    blockBase = firstBlock,
                    stringType = stringType,
                    unitType = pluginContext.irBuiltIns.unitType,
                    kotlinStringType = pluginContext.irBuiltIns.stringType,
                    functionIds = functionIds,
                    constantIds = constantIds,
                    session = session,
                )
            val compiled = compiler.compile()
            blocks += compiled.blocks
            val resultType = valueType(function.returnType, pluginContext, stringType, function)
            val parameterTypes = function.parameters.map { valueType(it.type, pluginContext, stringType, it) }
            val flags = setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { function.isSuspend })
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds[function.name.asString()]),
                    signature = TypeRef.Local(requireNotNull(typeIds[function.symbol])),
                    flags = flags,
                    registers = parameterTypes + compiled.localTypes,
                    parameterCount = parameterTypes.size.toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        val functionTypes =
            userFunctions.map { function ->
                NominalType.Function(
                    name = requireNotNull(metadataIds[function.name.asString()]),
                    suspending = function.isSuspend,
                    result = valueType(function.returnType, pluginContext, stringType, function),
                    parameters = function.parameters.map { valueType(it.type, pluginContext, stringType, it) },
                )
            }
        val app =
            Module(
                name = requireNotNull(metadataIds["app"]),
                kind = ModuleKind.APPLICATION,
                strings = metadataValues,
                utf16Literals = literals,
                types = functionTypes,
                constants = constants,
                imports =
                    listOf(
                        Import(
                            kind = SymbolKind.TYPE,
                            targetModule = ModuleId.of(1u),
                            targetName = StringId.of(0u),
                            expectedSignature = TypeRef.Imported(ImportId.of(0u)),
                            targetModuleHash = libraryHash,
                        ),
                    ),
                functions = loweredFunctions,
                blocks = blocks,
            )
        return Artifact(
            semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS),
            manifest =
                Manifest(
                    requiredHeapBytes = 64u * 1024u,
                    requiredStackBytes = 64u * 1024u,
                    maximumCoroutines = 1u,
                    maximumCallDepth = 16u,
                    maximumHostRequests = 64u,
                    maximumEvents = 0u,
                    maximumBlockCost = 64u,
                    minimumSliceCost = 64u,
                    compilerAbi = ByteArray(32),
                    standardLibraryAbi = ByteArray(32),
                ),
            entry = EntryPoint(ModuleId.of(0u), requireNotNull(functionIds[entry.symbol])),
            modules = listOf(app, library),
            capabilities =
                listOf(
                    Capability(
                        namespace = requireNotNull(metadataIds["compukter"]),
                        name = requireNotNull(metadataIds["terminal"]),
                        abi = AbiVersion(1u, 0u),
                        required = true,
                        operationCount = 3u,
                    ),
                ),
        )
    }

    private fun validateFunction(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
        entry: IrSimpleFunction,
    ) {
        if (function.parameters.any { it.type != pluginContext.irBuiltIns.stringType } ||
            (function.returnType != pluginContext.irBuiltIns.unitType && function.returnType != pluginContext.irBuiltIns.stringType) ||
            (function !== entry && function.isSuspend)
        ) {
            throw UnsupportedKotlinIr(function, "unsupported function signature")
        }
    }

    private fun valueType(
        type: IrType,
        pluginContext: IrPluginContext,
        stringType: ValueType,
        element: IrElement,
    ): ValueType =
        when (type) {
            pluginContext.irBuiltIns.unitType -> ValueType.Unit
            pluginContext.irBuiltIns.stringType -> stringType
            else -> throw UnsupportedKotlinIr(element, "unsupported value type")
        }

    private fun stringLibrary(): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings = listOf(MetadataText.of("kotlin.String")),
            types = listOf(NominalType.Class(name = StringId.of(0u), final = true)),
            exports =
                listOf(
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(0u),
                        localSymbol = 0u,
                        signature = TypeRef.Local(TypeId.of(0u)),
                    ),
                ),
        )
}

private data class CompiledFunction(
    val localTypes: List<ValueType>,
    val blocks: List<Block>,
)

private class FunctionCompiler(
    private val function: IrSimpleFunction,
    private val functionId: FunctionId,
    private val blockBase: Int,
    private val stringType: ValueType,
    private val unitType: IrType,
    private val kotlinStringType: IrType,
    private val functionIds: Map<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, FunctionId>,
    private val constantIds: Map<Utf16Literal, ConstantId>,
    private val session: CompilationSession,
) {
    private val localTypes = mutableListOf<ValueType>()
    private val values = mutableMapOf<IrValueSymbol, RegisterId>()
    private val blocks = mutableListOf(mutableListOf<Instruction>())
    private var currentBlock = 0

    fun compile(): CompiledFunction {
        function.parameters.forEachIndexed { index, parameter -> values[parameter.symbol] = RegisterId.of(index.toUInt()) }
        val body = function.body as? IrBlockBody ?: throw UnsupportedKotlinIr(function, "function body is not a block")
        body.statements.forEach { statement ->
            when (statement) {
                is IrVariable -> {
                    if (statement.isVar) throw UnsupportedKotlinIr(statement, "mutable local")
                    val initializer = statement.initializer ?: throw UnsupportedKotlinIr(statement, "local without initializer")
                    values[statement.symbol] = compileString(initializer)
                }

                is IrCall -> {
                    compileCall(statement)
                }

                is IrReturn -> {
                    if (function.returnType == unitType) {
                        emit(Instruction.Return(Destination.Unit))
                    } else {
                        emit(Instruction.Return(Destination.Register(compileString(statement.value))))
                    }
                }

                else -> {
                    throw UnsupportedKotlinIr(statement, "unsupported statement ${statement::class.simpleName}")
                }
            }
        }
        if (blocks[currentBlock].lastOrNull()?.isTerminator() != true) emit(Instruction.Return(Destination.Unit))
        return CompiledFunction(localTypes.toList(), blocks.map { Block(functionId, false, it.toList()) })
    }

    private fun compileString(expression: IrExpression): RegisterId =
        when (expression) {
            is IrConst -> {
                val value = expression.value as? String ?: throw UnsupportedKotlinIr(expression, "non-string constant")
                val literal = Utf16Literal.fromString(value)
                val constantId =
                    constantIds[literal]
                        ?: throw UnsupportedKotlinIr(expression, "literal is absent from canonical pool")
                allocate(stringType).also { emit(Instruction.Const(it, constantId)) }
            }

            is IrGetValue -> {
                values[expression.symbol] ?: throw UnsupportedKotlinIr(expression, "unknown local value")
            }

            is IrStringConcatenation -> {
                compileConcat(expression)
            }

            is IrCall -> {
                compileCall(expression)
                    ?: throw UnsupportedKotlinIr(expression, "Unit call used as a value")
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "unsupported expression ${expression::class.simpleName}")
            }
        }

    private fun compileConcat(expression: IrStringConcatenation): RegisterId {
        val arguments = expression.arguments
        if (arguments.isEmpty()) throw UnsupportedKotlinIr(expression, "empty string concatenation")
        var result = compileString(arguments.first())
        arguments.drop(1).forEach { argument ->
            val right = compileString(argument)
            startBlock()
            val destination = allocate(stringType)
            emit(Instruction.StringConcat(destination, result, right))
            result = destination
        }
        return result
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCall(call: IrCall): RegisterId? {
        val target = call.symbol.owner
        val arguments = call.arguments.filterNotNull().map(::compileString)
        terminalOperation(target)?.let { intrinsic ->
            val destination = if (intrinsic.operation == 2u) Destination.Register(allocate(stringType)) else Destination.Unit
            val resume = nextBlockId()
            emit(
                Instruction.CapabilityCallAsync(
                    destination,
                    CapabilityId.of(intrinsic.capability),
                    intrinsic.operation,
                    arguments,
                    resume,
                ),
            )
            startBlock(withJump = false)
            return (destination as? Destination.Register)?.id
        }
        val targetId = functionIds[target.symbol]
        if (targetId == null) {
            if (target.fqNameWhenAvailable?.asString() == "kotlin.String.plus" &&
                target.returnType == kotlinStringType &&
                call.arguments.filterNotNull().size == 2 &&
                call.arguments.filterNotNull().all { argument -> argument.type == kotlinStringType }
            ) {
                startBlock()
                return allocate(stringType).also { destination ->
                    emit(Instruction.StringConcat(destination, arguments[0], arguments[1]))
                }
            }
            throw UnsupportedKotlinIr(
                call,
                "call target ${target.fqNameWhenAvailable?.asString() ?: target.name.asString()} is outside the project subset",
            )
        }
        val returnsString = target.returnType == kotlinStringType
        val destination = if (returnsString) Destination.Register(allocate(stringType)) else Destination.Unit
        if (target.isSuspend) {
            val resume = nextBlockId()
            emit(Instruction.CallSuspend(destination, FunctionRef.Local(targetId), arguments, resume))
            startBlock(withJump = false)
        } else {
            emit(Instruction.Call(destination, FunctionRef.Local(targetId), arguments))
        }
        return (destination as? Destination.Register)?.id
    }

    private fun terminalOperation(function: IrSimpleFunction): TrustedIntrinsic.CapabilityOperation? {
        val sourceName = (function.parent as? IrFile)?.fileEntry?.name ?: return null
        val identity =
            TrustedCallableIdentity(
                bundleIdentity = session.trustedApiIdentity(sourceName),
                name = function.name.asString(),
                suspending = function.isSuspend,
                parameters = function.parameters.map { parameter -> parameter.type.toTrustedValueType() },
                result = function.returnType.toTrustedValueType(),
            )
        return TrustedIntrinsicRegistry.resolve(identity) as? TrustedIntrinsic.CapabilityOperation
    }

    private fun IrType.toTrustedValueType(): TrustedValueType =
        when (this) {
            unitType -> TrustedValueType.UNIT
            kotlinStringType -> TrustedValueType.STRING
            else -> TrustedValueType.OTHER
        }

    private fun allocate(type: ValueType): RegisterId =
        RegisterId.of((function.parameters.size + localTypes.size).toUInt()).also { localTypes += type }

    private fun emit(instruction: Instruction) {
        blocks[currentBlock] += instruction
    }

    private fun startBlock(withJump: Boolean = true) {
        val target = BlockId.of((blockBase + blocks.size).toUInt())
        if (withJump) emit(Instruction.Jump(target))
        blocks.add(mutableListOf())
        currentBlock++
    }

    private fun nextBlockId(): BlockId = BlockId.of((blockBase + blocks.size).toUInt())

    private fun Instruction.isTerminator(): Boolean =
        this is Instruction.Jump ||
            this is Instruction.Branch ||
            this is Instruction.Return ||
            this is Instruction.Throw ||
            this is Instruction.CallSuspend ||
            this is Instruction.CapabilityCallAsync
}

private class StringLiteralCollector : IrVisitorVoid() {
    val values = mutableListOf<String>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitConst(expression: IrConst) {
        (expression.value as? String)?.let(values::add)
        super.visitConst(expression)
    }
}
