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
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
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
import ru.lazyhat.compukters.compiler.artifact.model.EntryArguments
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
        val guestTypes = GuestTypeRegistry(pluginContext)
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
        userFunctions.forEach { validateFunction(it, pluginContext, guestTypes) }

        val intrinsicCollector =
            IntrinsicCollector { function ->
                resolveTrustedOperation(
                    function,
                    session,
                    pluginContext.irBuiltIns.unitType,
                    pluginContext.irBuiltIns.stringType,
                    pluginContext.irBuiltIns.intType,
                    pluginContext.irBuiltIns.booleanType,
                    pluginContext.irBuiltIns.charType,
                )
            }
        userFunctions.forEach { function -> function.accept(intrinsicCollector, null) }
        val capabilityIdentities = intrinsicCollector.capabilities.distinct().sorted()
        val capabilityIds =
            capabilityIdentities.withIndex().associate { (index, identity) -> identity to CapabilityId.of(index.toUInt()) }

        val stringArrayUsage = StringArrayUsageCollector(guestTypes)
        userFunctions.forEach { function -> function.accept(stringArrayUsage, null) }
        val usesStringArray =
            stringArrayUsage.used ||
                userFunctions.any { function ->
                    guestTypes.isStringArray(function.returnType) ||
                        function.parameters.any { parameter -> guestTypes.isStringArray(parameter.type) }
                }
        val metadataValues =
            (
                listOf("app") +
                    listOfNotNull("kotlin.Array".takeIf { usesStringArray }) +
                    capabilityIdentities.flatMap { listOf(it.namespace, it.name) } +
                    userFunctions.map { it.name.asString() }
            ).distinct()
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

        val library = kotlinLibrary()
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val charArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(0u)))
        val stringType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(1u)))
        val functionIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to FunctionId.of(index.toUInt()) }
        val typeIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to TypeId.of(index.toUInt()) }
        val stringArrayType =
            ValueType.Ref(
                nullable = false,
                type = TypeRef.Local(TypeId.of(userFunctions.size.toUInt())),
            )
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
                    charArrayType = charArrayType,
                    stringArrayType = stringArrayType,
                    guestTypes = guestTypes,
                    unitType = pluginContext.irBuiltIns.unitType,
                    kotlinStringType = pluginContext.irBuiltIns.stringType,
                    kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
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
            val resultType = valueType(function.returnType, pluginContext, guestTypes, stringType, charArrayType, stringArrayType, function)
            val parameterTypes =
                function.parameters.map {
                    valueType(it.type, pluginContext, guestTypes, stringType, charArrayType, stringArrayType, it)
                }
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
                    result =
                        valueType(
                            function.returnType,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            function,
                        ),
                    parameters =
                        function.parameters.map {
                            valueType(it.type, pluginContext, guestTypes, stringType, charArrayType, stringArrayType, it)
                        },
                )
            }
        val app =
            Module(
                name = requireNotNull(metadataIds["app"]),
                kind = ModuleKind.APPLICATION,
                strings = metadataValues,
                utf16Literals = literals,
                types =
                    functionTypes +
                        if (usesStringArray) {
                            listOf(
                                NominalType.Array(
                                    name = requireNotNull(metadataIds["kotlin.Array"]),
                                    element = stringType,
                                ),
                            )
                        } else {
                            emptyList()
                        },
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
                        Import(
                            kind = SymbolKind.TYPE,
                            targetModule = ModuleId.of(1u),
                            targetName = StringId.of(1u),
                            expectedSignature = TypeRef.Imported(ImportId.of(1u)),
                            targetModuleHash = libraryHash,
                        ),
                    ),
                functions = loweredFunctions,
                blocks = blocks,
            )
        val maximumCallDepth = 16u
        val maximumFrameBytes =
            loweredFunctions.maxOfOrNull { function -> function.registers.size.toULong() * 16uL + 32uL } ?: 32uL
        val requiredStackBytes = maximumFrameBytes * maximumCallDepth.toULong()
        require(requiredStackBytes <= UInt.MAX_VALUE.toULong()) { "required frame storage exceeds u32" }
        return Artifact(
            semanticFeatures =
                setOfNotNull(
                    SemanticFeature.COROUTINES.takeIf { userFunctions.any { it.isSuspend } },
                    SemanticFeature.CAPABILITIES.takeIf { capabilityIdentities.isNotEmpty() },
                    SemanticFeature.MODULE_IMPORTS,
                ),
            manifest =
                Manifest(
                    requiredHeapBytes = 64u * 1024u,
                    requiredStackBytes = maxOf(64u * 1024u, requiredStackBytes.toUInt()),
                    maximumCoroutines = 1u,
                    maximumCallDepth = maximumCallDepth,
                    maximumHostRequests = 64u,
                    maximumEvents = 0u,
                    maximumBlockCost = 64u,
                    minimumSliceCost = 64u,
                    compilerAbi = ByteArray(32),
                    standardLibraryAbi = ByteArray(32),
                ),
            entry =
                EntryPoint(
                    ModuleId.of(0u),
                    requireNotNull(functionIds[entry.symbol]),
                    if (entry.parameters.isEmpty()) EntryArguments.NONE else EntryArguments.STRING_ARRAY,
                ),
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
        guestTypes: GuestTypeRegistry,
    ) {
        val supported =
            setOf(
                pluginContext.irBuiltIns.unitType,
                pluginContext.irBuiltIns.stringType,
                pluginContext.irBuiltIns.intType,
                pluginContext.irBuiltIns.booleanType,
                pluginContext.irBuiltIns.charType,
            )

        fun isSupported(type: IrType): Boolean =
            type in supported || type.isExactClass(pluginContext.irBuiltIns.charArray) || guestTypes.isStringArray(type)
        if (function.parameters.any { !isSupported(it.type) } ||
            !isSupported(function.returnType)
        ) {
            throw UnsupportedKotlinIr(function, "unsupported function signature")
        }
    }

    private fun valueType(
        type: IrType,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        stringType: ValueType,
        charArrayType: ValueType,
        stringArrayType: ValueType,
        element: IrElement,
    ): ValueType =
        when (type) {
            pluginContext.irBuiltIns.unitType -> {
                ValueType.Unit
            }

            pluginContext.irBuiltIns.stringType -> {
                stringType
            }

            pluginContext.irBuiltIns.intType -> {
                ValueType.I32
            }

            pluginContext.irBuiltIns.booleanType -> {
                ValueType.Bool
            }

            pluginContext.irBuiltIns.charType -> {
                ValueType.Char
            }

            else -> {
                if (type.isExactClass(pluginContext.irBuiltIns.charArray)) {
                    charArrayType
                } else if (guestTypes.isStringArray(type)) {
                    stringArrayType
                } else {
                    throw UnsupportedKotlinIr(element, "unsupported value type")
                }
            }
        }

    private fun kotlinLibrary(): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings = listOf(MetadataText.of("kotlin.CharArray"), MetadataText.of("kotlin.String")),
            types =
                listOf(
                    NominalType.Array(name = StringId.of(0u), element = ValueType.Char),
                    NominalType.Class(name = StringId.of(1u), final = true),
                ),
            exports =
                listOf(
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(0u),
                        localSymbol = 0u,
                        signature = TypeRef.Local(TypeId.of(0u)),
                    ),
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(1u),
                        localSymbol = 1u,
                        signature = TypeRef.Local(TypeId.of(1u)),
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
    private val charArrayType: ValueType,
    private val stringArrayType: ValueType,
    private val guestTypes: GuestTypeRegistry,
    private val unitType: IrType,
    private val kotlinStringType: IrType,
    private val kotlinCharArrayClass: IrClassSymbol,
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

            is IrConstructorCall -> {
                compileCharArrayConstructor(expression)
            }

            is IrCall -> {
                compileCall(expression)
                    ?: throw UnsupportedKotlinIr(expression, "Unit call used as a value")
            }

            is IrWhen -> {
                compileWhenValue(expression)
            }

            is IrBlock -> {
                compileBlockValue(expression)
            }

            is IrTypeOperatorCall -> {
                compileExpression(expression.argument)
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "unsupported expression ${expression::class.simpleName}")
            }
        }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCharArrayConstructor(call: IrConstructorCall): RegisterId {
        val target = call.symbol.owner
        val arguments = call.arguments.filterNotNull()
        if (target.parentAsClass.symbol != kotlinCharArrayClass ||
            !call.type.isExactClass(kotlinCharArrayClass) ||
            arguments.size != 1 ||
            arguments[0].type != intType
        ) {
            throw UnsupportedKotlinIr(call, "constructor is outside the project subset")
        }
        val length = compileExpression(arguments.single())
        prepareAllocationBlock()
        return allocate(charArrayType).also { destination ->
            emit(Instruction.NewArray(destination, (charArrayType as ValueType.Ref).type, length))
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
        compileStringArrayFactory(call, target)?.let { return it }
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
        val targetId = functionIds[target.symbol]
        val argumentExpressions =
            if (targetId == null) {
                call.arguments.filterNotNull()
            } else {
                resolveProjectCallArguments(call, target)
            }
        val arguments = argumentExpressions.map(::compileExpression)
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

    private fun resolveProjectCallArguments(
        call: IrCall,
        target: IrSimpleFunction,
    ): List<IrExpression> =
        target.parameters.mapIndexed { index, parameter ->
            call.arguments.getOrNull(index)
                ?: parameter.defaultValue
                    ?.expression
                    ?.takeIf(::isSupportedStringArrayDefault)
                ?: throw UnsupportedKotlinIr(call, "omitted argument is outside the project subset")
        }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun isSupportedStringArrayDefault(expression: IrExpression): Boolean {
        val call = expression as? IrCall ?: return false
        if (!guestTypes.isStringArray(call.type)) return false
        return when (
            call.symbol.owner.fqNameWhenAvailable
                ?.asString()
        ) {
            "kotlin.emptyArray" -> {
                call.arguments.all { it == null }
            }

            "kotlin.arrayOf" -> {
                (call.arguments.filterNotNull().singleOrNull() as? IrVararg)
                    ?.elements
                    ?.all { it is IrExpression } == true
            }

            else -> {
                false
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileStringArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        if (!guestTypes.isStringArray(call.type)) return null
        val fqName = target.fqNameWhenAvailable?.asString() ?: return null
        val elements =
            when (fqName) {
                "kotlin.emptyArray" -> {
                    if (call.arguments.any { it != null }) {
                        throw UnsupportedKotlinIr(call, "emptyArray arguments are outside the project subset")
                    }
                    emptyList()
                }

                "kotlin.arrayOf" -> {
                    val vararg =
                        call.arguments.filterNotNull().singleOrNull() as? IrVararg
                            ?: throw UnsupportedKotlinIr(call, "arrayOf requires a direct vararg")
                    vararg.elements.map { element ->
                        element as? IrExpression
                            ?: throw UnsupportedKotlinIr(call, "spread arrayOf arguments are outside the project subset")
                    }
                }

                else -> {
                    return null
                }
            }
        val values = elements.map(::compileExpression)
        val length = emitI32Constant(elements.size, call)
        prepareAllocationBlock()
        val array = allocate(stringArrayType)
        emit(Instruction.NewArray(array, (stringArrayType as ValueType.Ref).type, length))
        values.forEachIndexed { index, value ->
            emit(Instruction.ArrayStore(array, emitI32Constant(index, call), value))
        }
        return array
    }

    private fun emitI32Constant(
        value: Int,
        element: IrElement,
    ): RegisterId {
        val id =
            constantIds[Constant.I32(value)]
                ?: throw UnsupportedKotlinIr(element, "generated array constant is absent from canonical pool")
        return allocate(ValueType.I32).also { emit(Instruction.Const(it, id)) }
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
                "times" -> return result(ValueType.I32) { Instruction.MultiplyI32(it, arguments[0], arguments[1]) }
                "div" -> return result(ValueType.I32) { Instruction.DivideI32(it, arguments[0], arguments[1]) }
                "rem" -> return result(ValueType.I32) { Instruction.RemainderI32(it, arguments[0], arguments[1]) }
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
        if (arguments.size == 3 &&
            guestTypes.isStringArray(argumentExpressions[0].type) &&
            argumentExpressions[1].type == intType &&
            argumentExpressions[2].type == intType &&
            name == "copyOfRange" &&
            fqName == "kotlin.collections.copyOfRange"
        ) {
            return compileStringArrayCopyOfRange(call, arguments)
        }
        if (arguments.size == 1 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "<get-size>" &&
            fqName == "kotlin.CharArray.<get-size>"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 1 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "<get-size>") {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 2 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "get" &&
            fqName == "kotlin.CharArray.get"
        ) {
            return result(ValueType.Char) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "get") {
            return result(stringType) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "set" &&
            fqName == "kotlin.CharArray.set"
        ) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "set") {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "concatToString" &&
            fqName == "kotlin.text.concatToString"
        ) {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringFromCharArray(it, arguments[0], arguments[1], arguments[2]) }
        }
        throw UnsupportedKotlinIr(call, "call target ${fqName.ifEmpty { name }} is outside the project subset")
    }

    private fun compileStringArrayCopyOfRange(
        call: IrCall,
        arguments: List<RegisterId>,
    ): RegisterId {
        val source = arguments[0]
        val start = arguments[1]
        val end = arguments[2]
        val length = allocate(ValueType.I32)
        emit(Instruction.SubtractI32(length, end, start))
        prepareAllocationBlock()
        val destination = allocate(stringArrayType)
        emit(Instruction.NewArray(destination, (stringArrayType as ValueType.Ref).type, length))

        val sourceIndex = allocate(ValueType.I32)
        emit(Instruction.Move(sourceIndex, start))
        val destinationIndex = allocate(ValueType.I32)
        emit(Instruction.Move(destinationIndex, emitI32Constant(0, call)))
        val one = emitI32Constant(1, call)

        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, condition, destinationIndex, length))
        val body = createBlock()
        val exit = createBlock()
        emit(Instruction.Branch(condition, blockId(body), blockId(exit)))

        currentBlock = body
        val value = allocate(stringType)
        emit(Instruction.ArrayLoad(value, source, sourceIndex))
        emit(Instruction.ArrayStore(destination, destinationIndex, value))
        val nextSource = allocate(ValueType.I32)
        emit(Instruction.AddI32(nextSource, sourceIndex, one))
        emit(Instruction.Move(sourceIndex, nextSource))
        val nextDestination = allocate(ValueType.I32)
        emit(Instruction.AddI32(nextDestination, destinationIndex, one))
        emit(Instruction.Move(destinationIndex, nextDestination))
        jumpTo(header)

        currentBlock = exit
        return destination
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
        val exits = mutableListOf<Int>()
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
                if (!isTerminated()) exits += currentBlock
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) exits += currentBlock
        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
    }

    private fun compileWhenValue(expression: IrWhen): RegisterId {
        val destination = allocate(valueType(expression.type, expression))
        val exits = mutableListOf<Int>()
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
                exits += currentBlock
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) exits += currentBlock
        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    private fun compileBlockValue(block: IrBlock): RegisterId {
        val result =
            block.statements.lastOrNull() as? IrExpression
                ?: throw UnsupportedKotlinIr(block, "value block has no result expression")
        block.statements.dropLast(1).forEach(::compileStatement)
        return compileExpression(result)
    }

    private fun trustedOperation(function: IrSimpleFunction): TrustedIntrinsic.CapabilityOperation? =
        resolveTrustedOperation(function, session, unitType, kotlinStringType, intType, booleanType, charType)

    private fun valueType(
        type: IrType,
        element: IrElement,
    ): ValueType =
        when (type) {
            unitType -> {
                ValueType.Unit
            }

            kotlinStringType -> {
                stringType
            }

            intType -> {
                ValueType.I32
            }

            booleanType -> {
                ValueType.Bool
            }

            charType -> {
                ValueType.Char
            }

            else -> {
                if (type.isExactClass(kotlinCharArrayClass)) {
                    charArrayType
                } else if (guestTypes.isStringArray(type)) {
                    stringArrayType
                } else {
                    throw UnsupportedKotlinIr(element, "unsupported value type")
                }
            }
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

private fun IrType.isExactClass(symbol: IrClassSymbol): Boolean = (this as? IrSimpleType)?.classifier == symbol

@OptIn(UnsafeDuringIrConstructionAPI::class)
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

    override fun visitCall(expression: IrCall) {
        val fqName =
            expression.symbol.owner.fqNameWhenAvailable
                ?.asString()
        if (fqName == "kotlin.Boolean.not") {
            values += false
        }
        if (fqName == "kotlin.emptyArray") {
            values += 0
        } else if (fqName == "kotlin.arrayOf") {
            val size = (expression.arguments.filterNotNull().singleOrNull() as? IrVararg)?.elements?.size
            if (size != null) values.addAll(0..size)
        } else if (fqName == "kotlin.collections.copyOfRange") {
            values.addAll(listOf(0, 1))
        }
        super.visitCall(expression)
    }
}

private class StringArrayUsageCollector(
    private val guestTypes: GuestTypeRegistry,
) : IrVisitorVoid() {
    var used: Boolean = false
        private set

    override fun visitElement(element: IrElement) {
        if (element is IrExpression && guestTypes.isStringArray(element.type)) used = true
        element.acceptChildren(this, null)
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
    booleanType: IrType,
    charType: IrType,
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
            booleanType -> TrustedValueType.BOOL
            charType -> TrustedValueType.CHAR
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
