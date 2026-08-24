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
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
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
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder
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
        userFunctions.forEach { validateFunction(it, pluginContext) }

        val intrinsicCollector =
            IntrinsicCollector { function ->
                resolveTrustedOperation(
                    function,
                    session,
                    pluginContext.irBuiltIns.unitType,
                    pluginContext.irBuiltIns.stringType,
                    pluginContext.irBuiltIns.intType,
                )
            }
        userFunctions.forEach { function -> function.accept(intrinsicCollector, null) }
        val capabilityIdentities = intrinsicCollector.capabilities.distinct().sorted()
        val capabilityIds =
            capabilityIdentities.withIndex().associate { (index, identity) -> identity to CapabilityId.of(index.toUInt()) }

        val metadataValues =
            (
                listOf("app") +
                    capabilityIdentities.flatMap { listOf(it.namespace, it.name) } +
                    userFunctions.map { it.name.asString() }
            )
                .distinct()
                .map(MetadataText::of)
                .sorted()
        val metadataIds = metadataValues.withIndex().associate { (index, value) -> value.toString() to StringId.of(index.toUInt()) }
        val literalCollector =
            LiteralCollector()
                .also { userFunctions.forEach { function -> function.accept(it, null) } }
        val literals =
            literalCollector.strings
                .distinct()
                .map {
                    Utf16Literal.fromString(it)
                }.sorted()
        val literalIds = literals.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        val constantPool = ConstantPoolBuilder()
        literalCollector.values
            .map { value -> value.toArtifactConstant(literalIds) }
            .forEach(constantPool::intern)
        val constants = constantPool.freeze().records
        val constantIds = constants.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }

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
                    intType = pluginContext.irBuiltIns.intType,
                    booleanType = pluginContext.irBuiltIns.booleanType,
                    charType = pluginContext.irBuiltIns.charType,
                    functionIds = functionIds,
                    constantIds = constantIds,
                    literalIds = literalIds,
                    session = session,
                    capabilityIds = capabilityIds,
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
            semanticFeatures =
                setOfNotNull(
                    SemanticFeature.COROUTINES,
                    SemanticFeature.CAPABILITIES.takeIf { capabilityIdentities.isNotEmpty() },
                    SemanticFeature.MODULE_IMPORTS,
                ),
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
                capabilityIdentities.map { identity ->
                    Capability(
                        namespace = requireNotNull(metadataIds[identity.namespace]),
                        name = requireNotNull(metadataIds[identity.name]),
                        abi = AbiVersion(identity.abiMajor, identity.abiMinor),
                        required = true,
                        operationCount = identity.operationCount,
                    )
                },
        )
    }

    private fun validateFunction(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
    ) {
        val supported =
            setOf(
                pluginContext.irBuiltIns.unitType,
                pluginContext.irBuiltIns.stringType,
                pluginContext.irBuiltIns.intType,
                pluginContext.irBuiltIns.booleanType,
                pluginContext.irBuiltIns.charType,
            )
        if (function.parameters.any { it.type !in supported } ||
            function.returnType !in supported
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
            pluginContext.irBuiltIns.intType -> ValueType.I32
            pluginContext.irBuiltIns.booleanType -> ValueType.Bool
            pluginContext.irBuiltIns.charType -> ValueType.Char
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
    private val intType: IrType,
    private val booleanType: IrType,
    private val charType: IrType,
    private val functionIds: Map<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, FunctionId>,
    private val constantIds: Map<Constant, ConstantId>,
    private val literalIds: Map<Utf16Literal, Utf16LiteralId>,
    private val session: CompilationSession,
    private val capabilityIds: Map<TrustedCapabilityIdentity, CapabilityId>,
) {
    private val localTypes = mutableListOf<ValueType>()
    private val values = mutableMapOf<IrValueSymbol, RegisterId>()
    private val blocks = mutableListOf(MutableBlock())
    private var currentBlock = 0

    fun compile(): CompiledFunction {
        function.parameters.forEachIndexed { index, parameter -> values[parameter.symbol] = RegisterId.of(index.toUInt()) }
        val body = function.body as? IrBlockBody ?: throw UnsupportedKotlinIr(function, "function body is not a block")
        body.statements.forEach(::compileStatement)
        if (blocks[currentBlock].instructions.lastOrNull()?.isTerminator() != true) emit(Instruction.Return(Destination.Unit))
        return CompiledFunction(
            localTypes.toList(),
            blocks.map { Block(functionId, it.loopHeaderSafepoint, it.instructions.toList()) },
        )
    }

    private fun compileStatement(statement: IrElement) {
        when (statement) {
            is IrVariable -> {
                val initializer = statement.initializer ?: throw UnsupportedKotlinIr(statement, "local without initializer")
                val source = compileExpression(initializer)
                val destination = allocate(valueType(statement.type, statement))
                emit(Instruction.Move(destination, source))
                values[statement.symbol] = destination
            }

            is IrSetValue -> {
                val destination = values[statement.symbol] ?: throw UnsupportedKotlinIr(statement, "unknown mutable local")
                emit(Instruction.Move(destination, compileExpression(statement.value)))
            }

            is IrCall -> {
                compileCall(statement)
            }

            is IrReturn -> {
                val destination =
                    if (function.returnType == unitType) Destination.Unit else Destination.Register(compileExpression(statement.value))
                emit(Instruction.Return(destination))
            }

            is IrWhen -> {
                compileWhenStatement(statement)
            }

            is IrWhileLoop -> {
                compileWhile(statement)
            }

            is IrBlock -> {
                statement.statements.forEach(::compileStatement)
            }

            is IrComposite -> {
                statement.statements.forEach(::compileStatement)
            }

            is IrTypeOperatorCall -> {
                compileStatement(statement.argument)
            }

            is IrExpression -> {
                compileExpression(statement)
            }

            else -> {
                throw UnsupportedKotlinIr(statement, "unsupported statement ${statement::class.simpleName}")
            }
        }
    }

    private fun compileExpression(expression: IrExpression): RegisterId =
        when (expression) {
            is IrConst -> {
                val constant = expression.toArtifactConstant(literalIds)
                val constantId = constantIds[constant] ?: throw UnsupportedKotlinIr(expression, "constant is absent from canonical pool")
                allocate(valueType(expression.type, expression)).also { emit(Instruction.Const(it, constantId)) }
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

            is IrWhen -> {
                compileWhenValue(expression)
            }

            is IrTypeOperatorCall -> {
                compileExpression(expression.argument)
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "unsupported expression ${expression::class.simpleName}")
            }
        }

    private fun compileConcat(expression: IrStringConcatenation): RegisterId {
        val arguments = expression.arguments
        if (arguments.isEmpty()) throw UnsupportedKotlinIr(expression, "empty string concatenation")
        var result = compileExpression(arguments.first())
        arguments.drop(1).forEach { argument ->
            val right = compileExpression(argument)
            prepareAllocationBlock()
            val destination = allocate(stringType)
            emit(Instruction.StringConcat(destination, result, right))
            result = destination
        }
        return result
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCall(call: IrCall): RegisterId? {
        val target = call.symbol.owner
        trustedOperation(target)?.let { intrinsic ->
            val arguments =
                target.parameters
                    .zip(call.arguments)
                    .filter { (parameter, _) -> parameter.kind == IrParameterKind.Regular }
                    .map { (_, argument) -> compileExpression(requireNotNull(argument)) }
            val capability = requireNotNull(capabilityIds[intrinsic.capability])
            val destination = destinationFor(call.type, call)
            if (intrinsic.asynchronous) {
                val resume = createBlock()
                emit(
                    Instruction.CapabilityCallAsync(
                        destination,
                        capability,
                        intrinsic.operation,
                        arguments,
                        blockId(resume),
                    ),
                )
                currentBlock = resume
            } else {
                emit(Instruction.CapabilityCallSync(destination, capability, intrinsic.operation, arguments))
            }
            return (destination as? Destination.Register)?.id
        }
        compileCompareToPredicate(call, target.name.asString())?.let { return it }
        val argumentExpressions = call.arguments.filterNotNull()
        val arguments = argumentExpressions.map(::compileExpression)
        val targetId = functionIds[target.symbol]
        if (targetId == null) {
            return compileBuiltinCall(call, target, argumentExpressions, arguments)
        }
        val destination = destinationFor(target.returnType, call)
        if (target.isSuspend) {
            val resume = createBlock()
            emit(Instruction.CallSuspend(destination, FunctionRef.Local(targetId), arguments, blockId(resume)))
            currentBlock = resume
        } else {
            emit(Instruction.Call(destination, FunctionRef.Local(targetId), arguments))
        }
        return (destination as? Destination.Register)?.id
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCompareToPredicate(
        call: IrCall,
        predicateName: String,
    ): RegisterId? {
        if (predicateName !in setOf("less", "lessOrEqual", "greater", "greaterOrEqual")) return null
        val outerArguments = call.arguments.filterNotNull()
        val compareCall = outerArguments.firstOrNull() as? IrCall ?: return null
        if (compareCall.symbol.owner.name
                .asString() != "compareTo"
        ) {
            return null
        }
        val zero = outerArguments.getOrNull(1) as? IrConst ?: return null
        if (zero.value != 0) return null
        val operands = compareCall.arguments.filterNotNull()
        if (operands.size != 2) return null
        val left = compileExpression(operands[0])
        val right = compileExpression(operands[1])
        val type = orderedType(operands[0].type, call)
        return allocate(ValueType.Bool).also { destination ->
            emit(
                when (predicateName) {
                    "less" -> Instruction.Less(type, destination, left, right)
                    "lessOrEqual" -> Instruction.LessOrEqual(type, destination, left, right)
                    "greater" -> Instruction.Greater(type, destination, left, right)
                    else -> Instruction.GreaterOrEqual(type, destination, left, right)
                },
            )
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileBuiltinCall(
        call: IrCall,
        target: IrSimpleFunction,
        argumentExpressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        val fqName = target.fqNameWhenAvailable?.asString().orEmpty()
        val name = target.name.asString()

        fun result(
            type: ValueType,
            instruction: (RegisterId) -> Instruction,
        ): RegisterId = allocate(type).also { emit(instruction(it)) }
        if (arguments.size == 2 && call.type == kotlinStringType && fqName == "kotlin.String.plus") {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringConcat(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 && argumentExpressions.all { it.type == intType }) {
            when (name) {
                "plus" -> return result(ValueType.I32) { Instruction.AddI32(it, arguments[0], arguments[1]) }
                "minus" -> return result(ValueType.I32) { Instruction.SubtractI32(it, arguments[0], arguments[1]) }
            }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == booleanType && name == "not") {
            val falseRegister = allocate(ValueType.Bool)
            emit(Instruction.Const(falseRegister, requireNotNull(constantIds[Constant.Bool(false)])))
            return result(ValueType.Bool) { Instruction.Equal(ScalarValueType.BOOL, it, arguments[0], falseRegister) }
        }
        comparison(call, name, argumentExpressions, arguments)?.let { return it }
        if (arguments.size == 1 && argumentExpressions[0].type == kotlinStringType && name == "<get-length>") {
            return result(ValueType.I32) { Instruction.StringLength(it, arguments[0]) }
        }
        if (arguments.size == 2 && argumentExpressions[0].type == kotlinStringType && name == "get") {
            return result(ValueType.Char) { Instruction.StringGet(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 3 && argumentExpressions[0].type == kotlinStringType && name == "substring") {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringSubstring(it, arguments[0], arguments[1], arguments[2]) }
        }
        throw UnsupportedKotlinIr(call, "call target ${fqName.ifEmpty { name }} is outside the project subset")
    }

    private fun comparison(
        call: IrCall,
        name: String,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        if (arguments.size != 2) return null
        val leftType = expressions[0].type
        val rightType = expressions[1].type
        if (name in setOf("EQEQ", "equals", "eqeq")) {
            return allocate(ValueType.Bool).also { destination ->
                if (leftType == kotlinStringType && rightType == kotlinStringType) {
                    emit(Instruction.StringEquals(destination, arguments[0], arguments[1]))
                } else {
                    emit(Instruction.Equal(scalarType(leftType, call), destination, arguments[0], arguments[1]))
                }
            }
        }
        val instructionFactory: (OrderedScalarValueType, RegisterId) -> Instruction =
            when (name) {
                "less" -> { type, destination -> Instruction.Less(type, destination, arguments[0], arguments[1]) }
                "lessOrEqual" -> { type, destination -> Instruction.LessOrEqual(type, destination, arguments[0], arguments[1]) }
                "greater" -> { type, destination -> Instruction.Greater(type, destination, arguments[0], arguments[1]) }
                "greaterOrEqual" -> { type, destination -> Instruction.GreaterOrEqual(type, destination, arguments[0], arguments[1]) }
                else -> return null
            }
        val orderedType = orderedType(leftType, call)
        return allocate(ValueType.Bool).also { destination ->
            val instruction = instructionFactory(orderedType, destination)
            emit(instruction)
        }
    }

    private fun compileWhile(loop: IrWhileLoop) {
        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = compileExpression(loop.condition)
        val body = createBlock()
        val exit = createBlock()
        emit(Instruction.Branch(condition, blockId(body), blockId(exit)))
        currentBlock = body
        loop.body?.let(::compileStatement)
        if (!isTerminated()) jumpTo(header)
        currentBlock = exit
    }

    private fun compileWhenStatement(expression: IrWhen) {
        val join = createBlock(loopHeader = true)
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                compileStatement(branch.result)
            } else {
                val condition = compileExpression(branch.condition)
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                compileStatement(branch.result)
                if (!isTerminated()) jumpTo(join)
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) jumpTo(join)
        currentBlock = join
    }

    private fun compileWhenValue(expression: IrWhen): RegisterId {
        val destination = allocate(valueType(expression.type, expression))
        val join = createBlock(loopHeader = true)
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                emit(Instruction.Move(destination, compileExpression(branch.result)))
            } else {
                val condition = compileExpression(branch.condition)
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                emit(Instruction.Move(destination, compileExpression(branch.result)))
                jumpTo(join)
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) jumpTo(join)
        currentBlock = join
        return destination
    }

    private fun trustedOperation(function: IrSimpleFunction): TrustedIntrinsic.CapabilityOperation? =
        resolveTrustedOperation(function, session, unitType, kotlinStringType, intType)

    private fun valueType(
        type: IrType,
        element: IrElement,
    ): ValueType =
        when (type) {
            unitType -> ValueType.Unit
            kotlinStringType -> stringType
            intType -> ValueType.I32
            booleanType -> ValueType.Bool
            charType -> ValueType.Char
            else -> throw UnsupportedKotlinIr(element, "unsupported value type")
        }

    private fun destinationFor(
        type: IrType,
        element: IrElement,
    ): Destination = if (type == unitType) Destination.Unit else Destination.Register(allocate(valueType(type, element)))

    private fun scalarType(
        type: IrType,
        element: IrElement,
    ): ScalarValueType =
        when (type) {
            intType -> ScalarValueType.I32
            booleanType -> ScalarValueType.BOOL
            charType -> ScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported equality operand")
        }

    private fun orderedType(
        type: IrType,
        element: IrElement,
    ): OrderedScalarValueType =
        when (type) {
            intType -> OrderedScalarValueType.I32
            charType -> OrderedScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported ordered-comparison operand")
        }

    private fun allocate(type: ValueType): RegisterId =
        RegisterId.of((function.parameters.size + localTypes.size).toUInt()).also { localTypes += type }

    private fun emit(instruction: Instruction) {
        blocks[currentBlock].instructions += instruction
    }

    private fun createBlock(loopHeader: Boolean = false): Int {
        blocks += MutableBlock(loopHeader)
        return blocks.lastIndex
    }

    private fun blockId(local: Int): BlockId = BlockId.of((blockBase + local).toUInt())

    private fun jumpTo(local: Int) = emit(Instruction.Jump(blockId(local)))

    private fun prepareAllocationBlock() {
        val instructions = blocks[currentBlock].instructions
        if (instructions.isNotEmpty()) {
            val allocationBlock = createBlock()
            jumpTo(allocationBlock)
            currentBlock = allocationBlock
        }
    }

    private fun isTerminated(): Boolean = blocks[currentBlock].instructions.lastOrNull()?.isTerminator() == true

    private fun Instruction.isTerminator(): Boolean =
        this is Instruction.Jump ||
            this is Instruction.Branch ||
            this is Instruction.Return ||
            this is Instruction.Throw ||
            this is Instruction.CallSuspend ||
            this is Instruction.CapabilityCallAsync

    private data class MutableBlock(
        var loopHeaderSafepoint: Boolean = false,
        val instructions: MutableList<Instruction> = mutableListOf(),
    )
}

private class LiteralCollector : IrVisitorVoid() {
    val values = mutableListOf<Any>()
    val strings: List<String>
        get() = values.filterIsInstance<String>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitConst(expression: IrConst) {
        expression.value?.takeIf { it is String || it is Int || it is Boolean || it is Char }?.let(values::add)
        super.visitConst(expression)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class IntrinsicCollector(
    private val resolve: (IrSimpleFunction) -> TrustedIntrinsic.CapabilityOperation?,
) : IrVisitorVoid() {
    val capabilities = mutableListOf<TrustedCapabilityIdentity>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitCall(expression: IrCall) {
        resolve(expression.symbol.owner)?.capability?.let(capabilities::add)
        super.visitCall(expression)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun resolveTrustedOperation(
    function: IrSimpleFunction,
    session: CompilationSession,
    unitType: IrType,
    stringType: IrType,
    intType: IrType,
): TrustedIntrinsic.CapabilityOperation? {
    var parent = function.parent
    while (parent !is IrFile) {
        parent = (parent as? IrDeclaration)?.parent ?: return null
    }
    val sourceName = parent.fileEntry.name
    fun IrType.trustedType(): TrustedValueType =
        when (this) {
            unitType -> TrustedValueType.UNIT
            stringType -> TrustedValueType.STRING
            intType -> TrustedValueType.INT
            else -> TrustedValueType.OTHER
        }
    val identity =
        TrustedCallableIdentity(
            bundleIdentity = session.trustedApiIdentity(sourceName),
            name = function.fqNameWhenAvailable?.asString() ?: return null,
            suspending = function.isSuspend,
            parameters =
                function.parameters
                    .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                    .map { parameter -> parameter.type.trustedType() },
            result = function.returnType.trustedType(),
        )
    return TrustedIntrinsicRegistry.resolve(identity) as? TrustedIntrinsic.CapabilityOperation
}

private fun Any.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (this) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(this)]))
        is Int -> Constant.I32(this)
        is Boolean -> Constant.Bool(this)
        is Char -> Constant.Char(code.toUShort())
        else -> error("unsupported literal $this")
    }

private fun IrConst.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (val literal = value) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(literal)]))
        is Int -> Constant.I32(literal)
        is Boolean -> Constant.Bool(literal)
        is Char -> Constant.Char(literal.code.toUShort())
        else -> throw UnsupportedKotlinIr(this, "unsupported constant")
    }

private fun IrExpression.isTrueConstant(): Boolean = this is IrConst && value == true
