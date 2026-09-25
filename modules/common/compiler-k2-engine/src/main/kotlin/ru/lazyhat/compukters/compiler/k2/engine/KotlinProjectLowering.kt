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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.compiler.artifact.analysis.ExecutionStorage
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
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
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
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CapabilityOperationHandler
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.IntrinsicBlockingMode
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.PlatformCapabilityId
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformScalarConstant
import ru.lazyhat.compukters.platform.bundle.PlatformScalarRepresentation
import ru.lazyhat.compukters.platform.bundle.PlatformScalarType
import ru.lazyhat.compukters.platform.bundle.PlatformScalarValue

internal class UnsupportedKotlinIr(
    val element: IrElement,
    message: String,
) : IllegalArgumentException(message)

private data class GuestFieldLayout(
    val property: IrProperty,
    val id: FieldId,
    val type: ValueType,
)

private data class GuestEnumEntryLayout(
    val declaration: IrEnumEntry,
    val fieldId: FieldId,
    val ownerType: TypeRef.Local,
)

private sealed interface TopLevelInitializer {
    data object Null : TopLevelInitializer

    data class Scalar(
        val value: Any,
    ) : TopLevelInitializer

    data class Channel(
        val capacity: Int,
    ) : TopLevelInitializer
}

private data class TopLevelProperty(
    val declaration: IrProperty,
    val initializer: TopLevelInitializer,
)

private data class TopLevelFieldLayout(
    val property: TopLevelProperty,
    val fieldId: FieldId,
    val type: ValueType,
)

private data class GuestClassLayout(
    val instance: GuestClassInstance,
    val typeId: TypeId,
    val firstField: UInt,
    val fields: List<GuestFieldLayout>,
    val enumEntries: List<GuestEnumEntryLayout>,
) {
    val declaration: IrClass get() = instance.declaration
}

private data class GuestClassInstance(
    val declaration: IrClass,
    val arguments: List<IrType>,
) {
    val substitution: IrTypeSubstitutor =
        IrTypeSubstitutor(
            declaration.typeParameters.map { it.symbol },
            arguments.map { makeTypeProjection(it, Variance.INVARIANT) },
            false,
        )

    fun substitute(type: IrType): IrType = substitution.substitute(type)

    val name: String
        get() {
            val base = declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
            return if (arguments.isEmpty()) base else "$base<${arguments.joinToString(",") { it.canonicalPlatformType() }}>"
        }
}

private fun IrType.classInstance(classes: Map<IrClassSymbol, IrClass>): GuestClassInstance? {
    val simple = this as? IrSimpleType ?: return null
    val declaration = classes[simple.classifier as? IrClassSymbol] ?: return null
    val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return null }
    if (arguments.size != declaration.typeParameters.size) return null
    return GuestClassInstance(declaration, arguments)
}

private fun collectGuestClassInstances(
    classes: List<IrClass>,
    functions: List<GuestFunctionInstance>,
    properties: List<TopLevelProperty>,
): List<GuestClassInstance> {
    val bySymbol = classes.associateBy { it.symbol }
    val byName = classes.associateBy { it.fqNameWhenAvailable?.asString() }
    val instances = linkedSetOf<GuestClassInstance>()
    val pending = ArrayDeque<GuestClassInstance>()

    fun add(instance: GuestClassInstance) {
        if (
            instance.declaration.fqNameWhenAvailable?.asString() in
            setOf(
                "kotlin.collections.Iterable",
                "kotlin.collections.Iterator",
                "kotlin.collections.Collection",
                "kotlin.collections.List",
            ) && instance.arguments.any { it.isNullable() }
        ) {
            throw UnsupportedKotlinIr(instance.declaration, "nullable collection elements are outside the project subset")
        }
        if (instances.add(instance)) {
            if (instances.count { it.arguments.isNotEmpty() } > 256) {
                throw UnsupportedKotlinIr(instance.declaration, "generic specialization exceeds 256 class variants")
            }
            pending.addLast(instance)
        }
    }

    fun consider(
        type: IrType,
        substitution: (IrType) -> IrType = { it },
    ) {
        val resolved = substitution(type)
        resolved.classInstance(bySymbol)?.let(::add)
    }

    classes.filter { it.typeParameters.isEmpty() }.forEach { add(GuestClassInstance(it, emptyList())) }

    fun scan(
        element: IrElement,
        substitution: (IrType) -> IrType,
    ) {
        element.accept(
            object : IrVisitorVoid() {
                override fun visitCall(expression: IrCall) {
                    if (expression.symbol.owner.fqNameWhenAvailable
                            ?.asString() in
                        setOf("kotlin.collections.listOf", "kotlin.collections.emptyList")
                    ) {
                        val elementType = expression.typeArguments.singleOrNull()?.let(substitution)
                        if (elementType != null && elementType.canonicalPlatformType() != "Int") {
                            listOf("ArrayBackedList", "ArrayBackedListIterator").forEach { name ->
                                byName["kotlin.collections.$name"]?.let { add(GuestClassInstance(it, listOf(elementType))) }
                            }
                        }
                    }
                    super.visitCall(expression)
                }

                override fun visitElement(element: IrElement) {
                    when (element) {
                        is IrExpression -> consider(element.type, substitution)
                        is IrValueDeclaration -> consider(element.type, substitution)
                        is IrSimpleFunction -> consider(element.returnType, substitution)
                    }
                    element.acceptChildren(this, null)
                }
            },
            null,
        )
    }
    functions.forEach { instance -> scan(instance.declaration, instance::substitute) }
    properties.forEach { property -> scan(property.declaration, { it }) }
    while (pending.isNotEmpty()) {
        val instance = pending.removeFirst()
        instance.declaration.superTypes.forEach { consider(it, instance::substitute) }
        instance.declaration.declarations.forEach { declaration -> scan(declaration, instance::substitute) }
    }
    return instances.toList()
}

private data class GuestConstructorTarget(
    val layout: GuestClassLayout,
    val functionId: FunctionId,
)

private data class GuestFunctionInstance(
    val declaration: IrSimpleFunction,
    val arguments: List<IrType>,
    val ownerClass: GuestClassInstance? = null,
) {
    val substitution: IrTypeSubstitutor =
        IrTypeSubstitutor(
            declaration.typeParameters.map { it.symbol },
            arguments.map { makeTypeProjection(it, Variance.INVARIANT) },
            false,
        )

    fun substitute(type: IrType): IrType {
        val functionType = substitution.substitute(type)
        return ownerClass?.substitute(functionType) ?: functionType
    }
}

private fun collectGuestFunctionInstances(
    functions: List<IrSimpleFunction>,
    constructors: List<IrClass>,
    memberInstances: List<GuestFunctionInstance> = emptyList(),
): List<GuestFunctionInstance> {
    functions.firstOrNull { it.parent is IrClass && it.typeParameters.isNotEmpty() }?.let { function ->
        throw UnsupportedKotlinIr(function, "generic instance methods are not supported")
    }
    functions.firstOrNull { function -> function.typeParameters.any { it.isReified } }?.let { function ->
        throw UnsupportedKotlinIr(function, "reified generic parameters are not supported")
    }
    val projectSymbols = functions.mapTo(mutableSetOf()) { it.symbol }
    val instances = linkedSetOf<GuestFunctionInstance>()
    val pending = ArrayDeque<GuestFunctionInstance>()
    var genericVariantCount = 0

    fun add(instance: GuestFunctionInstance) {
        if (instances.add(instance)) {
            if (instance.arguments.isNotEmpty() && ++genericVariantCount > 256) {
                throw UnsupportedKotlinIr(instance.declaration, "generic specialization exceeds 256 function variants")
            }
            pending.addLast(instance)
        }
    }

    functions
        .filter { function ->
            function.typeParameters.isEmpty() && (function.parent as? IrClass)?.typeParameters?.isEmpty() != false
        }.forEach { add(GuestFunctionInstance(it, emptyList())) }
    memberInstances.forEach(::add)
    val constructorBodies =
        constructors.flatMap { declaration -> declaration.constructors.filter { it.isPrimary } }

    fun scan(
        element: IrElement,
        owner: GuestFunctionInstance?,
    ) {
        element.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitCall(expression: IrCall) {
                    val target = expression.symbol.owner
                    if (target.symbol in projectSymbols && target.typeParameters.isNotEmpty()) {
                        val arguments =
                            expression.typeArguments.map { argument ->
                                val type = argument ?: throw UnsupportedKotlinIr(expression, "generic call has an inferred type hole")
                                owner?.substitute(type) ?: type
                            }
                        if (arguments.size != target.typeParameters.size ||
                            arguments.any { (it as? IrSimpleType)?.classifier is IrTypeParameterSymbol }
                        ) {
                            throw UnsupportedKotlinIr(expression, "generic call has unresolved type arguments")
                        }
                        add(GuestFunctionInstance(target, arguments))
                    }
                    super.visitCall(expression)
                }
            },
            null,
        )
    }

    constructorBodies.forEach { constructor -> constructor.body?.let { scan(it, null) } }
    while (pending.isNotEmpty()) {
        val instance = pending.removeFirst()
        instance.declaration.body?.let { scan(it, instance) }
    }
    return instances.toList()
}

private data class GuestClosureCapture(
    val symbol: IrValueSymbol?,
    val initialValue: IrExpression?,
    val fieldId: FieldId,
    val type: ValueType,
    val cell: GuestCaptureCellLayout?,
)

private data class GuestCaptureCellLayout(
    val declaration: IrVariable,
    val ordinal: Int,
    val typeId: TypeId,
    val fieldId: FieldId,
    val valueType: ValueType,
) {
    val name: String get() = "app.<capture-cell-$ordinal>"
}

private data class GuestClosureSource(
    val expression: IrExpression,
    val function: IrSimpleFunction?,
    val referenceTarget: IrSimpleFunction?,
    val constructorTarget: IrConstructorSymbol?,
    val boundReceiver: IrExpression?,
    val ordinal: Int,
    val captures: List<IrValueDeclaration>,
)

private data class GuestFunctionShape(
    val parameters: List<IrType>,
    val result: IrType,
) {
    val arity: Int get() = parameters.size
}

private data class GuestClosureLayout(
    val expression: IrExpression,
    val function: IrSimpleFunction?,
    val referenceTarget: IrSimpleFunction?,
    val constructorTarget: IrConstructorSymbol?,
    val ordinal: Int,
    val typeId: TypeId,
    val invokeFunctionId: FunctionId,
    val invokeSignatureTypeId: TypeId,
    val shape: GuestFunctionShape,
    val captures: List<GuestClosureCapture>,
) {
    val name: String get() = closureName(ordinal)
}

private data class InlineValueClassLayout(
    val declaration: IrClass,
    val constructor: IrConstructorSymbol,
    val getter: IrSimpleFunctionSymbol,
    val underlyingType: IrType,
    val intRange: InlineIntRange?,
)

private data class InlineIntRange(
    val minimum: Int,
    val maximum: Int,
)

private data class InlineScalarConstant(
    val value: Any,
)

private class PlatformScalarRegistry(
    scalarTypes: List<PlatformScalarType>,
    scalarConstants: List<PlatformScalarConstant>,
) {
    private val typesBySymbol = scalarTypes.associateBy(PlatformScalarType::symbol)
    private val constantsBySymbol = scalarConstants.associateBy(PlatformScalarConstant::symbol)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun representation(type: IrType): PlatformScalarRepresentation? =
        ((type as? IrSimpleType)?.classifier as? IrClassSymbol)
            ?.owner
            ?.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)
            ?.representation

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun constructor(symbol: IrConstructorSymbol): PlatformScalarType? =
        symbol.owner.parentAsClass.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun isUnderlyingGetter(function: IrSimpleFunction): Boolean {
        val propertyName =
            function.name
                .asString()
                .removePrefix("<get-")
                .removeSuffix(">")
        return (function.parent as? IrClass)
            ?.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)
            ?.underlyingProperty == propertyName
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun constant(function: IrSimpleFunction): PlatformScalarValue? {
        val owner = function.parent as? IrClass ?: return null
        val scalarOwner =
            if (owner.name.asString() == "Companion") {
                owner.parent as? IrClass
            } else {
                owner
            } ?: return null
        val scalarSymbol = scalarOwner.fqNameWhenAvailable?.asString() ?: return null
        if (scalarSymbol !in typesBySymbol) return null
        val propertyName =
            function.name
                .asString()
                .removePrefix("<get-")
                .removeSuffix(">")
        return constantsBySymbol["$scalarSymbol.$propertyName"]?.value
    }

    fun constantValues(): List<Any> =
        constantsBySymbol.values.map { it.value.scalarValue() } +
            typesBySymbol.values.flatMap { type -> listOfNotNull(type.minimumInt, type.maximumInt) }
}

private fun PlatformScalarValue.scalarValue(): Any =
    when (this) {
        is PlatformScalarValue.IntValue -> value
        is PlatformScalarValue.BooleanValue -> value
        is PlatformScalarValue.CharValue -> value
    }

private class InlineValueClassRegistry private constructor(
    private val byClass: Map<IrClassSymbol, InlineValueClassLayout>,
    private val byConstructor: Map<IrConstructorSymbol, InlineValueClassLayout>,
    private val byGetter: Map<IrSimpleFunctionSymbol, InlineValueClassLayout>,
    private val companionClasses: Set<IrClassSymbol>,
    private val constantsByGetter: Map<IrSimpleFunctionSymbol, InlineScalarConstant>,
) {
    operator fun get(symbol: IrClassSymbol): InlineValueClassLayout? = byClass[symbol]

    fun constructor(symbol: IrConstructorSymbol): InlineValueClassLayout? = byConstructor[symbol]

    fun getter(symbol: IrSimpleFunctionSymbol): InlineValueClassLayout? = byGetter[symbol]

    fun contains(symbol: IrClassSymbol?): Boolean = symbol != null && symbol in byClass

    fun isCompanion(symbol: IrClassSymbol): Boolean = symbol in companionClasses

    fun constant(getter: IrSimpleFunctionSymbol): InlineScalarConstant? = constantsByGetter[getter]

    fun constantValues(): List<Any> =
        constantsByGetter.values.map(InlineScalarConstant::value) +
            byClass.values.flatMap { layout ->
                layout.intRange?.let { listOf(it.minimum, it.maximum) }.orEmpty()
            }

    companion object {
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun build(
            classes: List<IrClass>,
            pluginContext: IrPluginContext,
        ): InlineValueClassRegistry {
            val layouts = classes.filter { it.isValue }.map { declaration -> validate(declaration, pluginContext) }
            val layoutsByClass = layouts.associateBy { it.declaration.symbol }
            val companions =
                classes.filter { declaration ->
                    declaration.kind == ClassKind.OBJECT &&
                        declaration.name.asString() == "Companion" &&
                        layoutsByClass.containsKey((declaration.parent as? IrClass)?.symbol)
                }
            val constants =
                companions
                    .flatMap { companion ->
                        companion.declarations.filterIsInstance<IrProperty>().map { property ->
                            if (property.isVar || property.getter == null || property.backingField == null) {
                                throw UnsupportedKotlinIr(property, "value class companion properties must be immutable scalar constants")
                            }
                            val initializer =
                                requireNotNull(property.backingField).initializer?.expression
                                    ?: throw UnsupportedKotlinIr(property, "value class companion constant requires an initializer")
                            requireNotNull(property.getter).symbol to
                                InlineScalarConstant(
                                    scalarConstant(
                                        initializer,
                                        layouts.associateBy(InlineValueClassLayout::constructor),
                                        pluginContext,
                                    ),
                                )
                        }
                    }.toMap()
            return InlineValueClassRegistry(
                byClass = layoutsByClass,
                byConstructor = layouts.associateBy(InlineValueClassLayout::constructor),
                byGetter = layouts.associateBy(InlineValueClassLayout::getter),
                companionClasses = companions.mapTo(mutableSetOf()) { it.symbol },
                constantsByGetter = constants,
            )
        }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun scalarConstant(
            expression: IrExpression,
            layouts: Map<IrConstructorSymbol, InlineValueClassLayout>,
            pluginContext: IrPluginContext,
        ): Any =
            when (expression) {
                is IrConst -> {
                    expression.value
                        ?: throw UnsupportedKotlinIr(expression, "null companion constants are not supported")
                }

                is IrConstructorCall -> {
                    val layout =
                        layouts[expression.symbol]
                            ?: throw UnsupportedKotlinIr(expression, "companion constant constructor is not a supported value class")
                    val argument =
                        expression.symbol.owner.parameters
                            .mapIndexedNotNull { index, parameter ->
                                expression.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                            }.singleOrNull()
                            ?: throw UnsupportedKotlinIr(expression, "value class companion constant requires one scalar argument")
                    val value = scalarConstant(argument, layouts, pluginContext)
                    val valid =
                        when (layout.underlyingType) {
                            pluginContext.irBuiltIns.intType -> value is Int
                            pluginContext.irBuiltIns.booleanType -> value is Boolean
                            pluginContext.irBuiltIns.charType -> value is Char
                            else -> false
                        }
                    if (!valid) throw UnsupportedKotlinIr(expression, "value class companion constant type mismatch")
                    layout.intRange?.let { range ->
                        val intValue = value as Int
                        if (intValue !in range.minimum..range.maximum) {
                            throw UnsupportedKotlinIr(expression, "value class companion constant violates its precondition")
                        }
                    }
                    value
                }

                else -> {
                    throw UnsupportedKotlinIr(expression, "value class companion initializer must be a scalar constant")
                }
            }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun validate(
            declaration: IrClass,
            pluginContext: IrPluginContext,
        ): InlineValueClassLayout {
            if (declaration.typeParameters.isNotEmpty()) {
                throw UnsupportedKotlinIr(declaration, "generic value classes are not supported")
            }
            val constructor =
                declaration.constructors.singleOrNull { it.isPrimary }
                    ?: throw UnsupportedKotlinIr(declaration, "value class must have one primary constructor")
            if (declaration.constructors.any { !it.isPrimary }) {
                throw UnsupportedKotlinIr(declaration, "value class secondary constructors are not supported")
            }
            val parameter =
                constructor.parameters.singleOrNull { it.kind == IrParameterKind.Regular }
                    ?: throw UnsupportedKotlinIr(declaration, "value class must have one underlying property")
            val property =
                declaration.declarations.filterIsInstance<IrProperty>().singleOrNull { property ->
                    property.backingField != null && property.name == parameter.name
                } ?: throw UnsupportedKotlinIr(declaration, "value class must have one underlying property")
            if (property.isVar || property.getter == null || property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                throw UnsupportedKotlinIr(property, "value class underlying property must be immutable")
            }
            if (parameter.type.isNullable() ||
                parameter.type !in
                setOf(
                    pluginContext.irBuiltIns.intType,
                    pluginContext.irBuiltIns.booleanType,
                    pluginContext.irBuiltIns.charType,
                )
            ) {
                throw UnsupportedKotlinIr(parameter, "value class underlying type must be a supported non-null scalar")
            }
            val initializers = declaration.declarations.filterIsInstance<IrAnonymousInitializer>()
            val intRange =
                when (initializers.size) {
                    0 -> null
                    1 -> parseIntRange(initializers.single(), requireNotNull(property.getter).symbol)
                    else -> throw UnsupportedKotlinIr(declaration, "value class supports at most one scalar precondition")
                }
            val unsupportedParent =
                declaration.superTypes
                    .mapNotNull { (it as? IrSimpleType)?.classifier as? IrClassSymbol }
                    .firstOrNull { it.owner.fqNameWhenAvailable?.asString() != "kotlin.Any" }
            if (unsupportedParent != null) {
                throw UnsupportedKotlinIr(declaration, "value class interfaces and custom supertypes are not supported")
            }
            return InlineValueClassLayout(
                declaration = declaration,
                constructor = constructor.symbol,
                getter = requireNotNull(property.getter).symbol,
                underlyingType = parameter.type,
                intRange = intRange,
            )
        }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun parseIntRange(
            initializer: IrAnonymousInitializer,
            getter: IrSimpleFunctionSymbol,
        ): InlineIntRange {
            val requireCall =
                initializer.body.statements.singleOrNull() as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class initializer must be one require call")
            if (requireCall.symbol.owner.fqNameWhenAvailable
                    ?.asString() != "kotlin.require"
            ) {
                throw UnsupportedKotlinIr(initializer, "value class initializer must be one require call")
            }
            val contains =
                requireCall.arguments.filterNotNull().singleOrNull() as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class require must check one inclusive Int range")
            if (contains.symbol.owner.fqNameWhenAvailable
                    ?.asString() != "kotlin.ranges.IntRange.contains"
            ) {
                throw UnsupportedKotlinIr(initializer, "value class require must check one inclusive Int range")
            }
            val containsArguments = contains.arguments.filterNotNull()
            val range =
                containsArguments.getOrNull(0) as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class require must use a constant Int range")
            val value = containsArguments.getOrNull(1) as? IrCall
            if (range.symbol.owner.name
                    .asString() != "rangeTo" || value?.symbol != getter
            ) {
                throw UnsupportedKotlinIr(initializer, "value class require must check its underlying property")
            }
            val bounds = range.arguments.filterNotNull().map { (it as? IrConst)?.value }
            val minimum = bounds.getOrNull(0) as? Int
            val maximum = bounds.getOrNull(1) as? Int
            if (minimum == null || maximum == null || minimum > maximum) {
                throw UnsupportedKotlinIr(initializer, "value class require bounds must be ordered Int constants")
            }
            return InlineIntRange(minimum, maximum)
        }
    }
}

private const val ANY_RUNTIME_TYPE = 5u
private const val INT_BOX_RUNTIME_TYPE = 6u
private const val INT_BOX_VALUE_IMPORT = 7u
private const val INT_ARRAY_RUNTIME_TYPE = 4u
private const val INT_BOX_VALUE_NAME = "kotlin.Int.<boxed-value>"

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun mapGuestValueType(
    type: IrType,
    pluginContext: IrPluginContext,
    guestTypes: GuestTypeRegistry,
    stringType: ValueType,
    charArrayType: ValueType,
    stringArrayType: ValueType,
    classTypeIds: Map<IrClassSymbol, TypeId>,
    externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
    inlineValueClasses: InlineValueClassRegistry,
    platformScalars: PlatformScalarRegistry,
    element: IrElement,
    functionTypes: Map<GuestFunctionShape, TypeRef.Local> = emptyMap(),
    instance: GuestFunctionInstance? = null,
    classInstanceTypeIds: Map<GuestClassInstance, TypeId> = emptyMap(),
    resolveUnderlyingType: (IrType) -> IrType = { it },
): ValueType {
    if (instance != null) {
        return mapGuestValueType(
            instance.substitute(type),
            pluginContext,
            guestTypes,
            stringType,
            charArrayType,
            stringArrayType,
            classTypeIds,
            externalClassTypes,
            inlineValueClasses,
            platformScalars,
            element,
            functionTypes,
            classInstanceTypeIds = classInstanceTypeIds,
            resolveUnderlyingType = resolveUnderlyingType,
        )
    }
    if (type.isNullable()) {
        val stringClass = (pluginContext.irBuiltIns.stringType as IrSimpleType).classifier
        val guestClass = (type as? IrSimpleType)?.classifier as? IrClassSymbol
        val guestInstance = type.classInstance(classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration })
        if (!type.isKotlinAny() && guestClass != stringClass && guestClass !in classTypeIds && guestInstance !in classInstanceTypeIds) {
            throw UnsupportedKotlinIr(element, "nullable type is outside the supported reference subset")
        }
    }
    return when (type) {
        pluginContext.irBuiltIns.unitType -> {
            ValueType.Unit
        }

        pluginContext.irBuiltIns.stringType -> {
            stringType
        }

        pluginContext.irBuiltIns.intType -> {
            ValueType.I32
        }

        pluginContext.irBuiltIns.longType -> {
            ValueType.I64
        }

        pluginContext.irBuiltIns.floatType -> {
            ValueType.F32
        }

        pluginContext.irBuiltIns.booleanType -> {
            ValueType.Bool
        }

        pluginContext.irBuiltIns.charType -> {
            ValueType.Char
        }

        else -> {
            if (type.isKotlinAny()) {
                ValueType.Ref(nullable = type.isNullable(), type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))
            } else if ((type as? IrSimpleType)?.classifier == (pluginContext.irBuiltIns.stringType as IrSimpleType).classifier) {
                (stringType as ValueType.Ref).copy(nullable = type.isNullable())
            } else if (type.isNothing()) {
                ValueType.Unit
            } else if (type.isExactClass(pluginContext.irBuiltIns.charArray)) {
                charArrayType
            } else if (type.isExactClass(pluginContext.irBuiltIns.intArray)) {
                ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(INT_ARRAY_RUNTIME_TYPE)))
            } else if (guestTypes.isStringArray(type)) {
                stringArrayType
            } else if (guestTypes.referenceArrayType(type) != null) {
                requireNotNull(guestTypes.referenceArrayType(type))
            } else if (functionTypes.forType(type) != null) {
                ValueType.Ref(nullable = false, type = requireNotNull(functionTypes.forType(type)))
            } else if (type is IrSimpleType && type.classifier is IrClassSymbol) {
                val classifier = type.classifier as IrClassSymbol
                val inline = inlineValueClasses[classifier]
                val id =
                    if (classifier.owner.typeParameters.isEmpty()) {
                        classTypeIds[classifier]
                    } else {
                        type
                            .classInstance(classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration })
                            ?.let(classInstanceTypeIds::get)
                    }
                val external = externalClassTypes[classifier]
                val platformScalar = platformScalars.representation(type)
                if (platformScalar != null) {
                    if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable platform scalar types are not supported")
                    when (platformScalar) {
                        PlatformScalarRepresentation.INT -> ValueType.I32
                        PlatformScalarRepresentation.BOOLEAN -> ValueType.Bool
                        PlatformScalarRepresentation.CHAR -> ValueType.Char
                    }
                } else if (inline != null) {
                    if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable value classes are not supported")
                    mapGuestValueType(
                        resolveUnderlyingType(inline.underlyingType),
                        pluginContext,
                        guestTypes,
                        stringType,
                        charArrayType,
                        stringArrayType,
                        classTypeIds,
                        externalClassTypes,
                        inlineValueClasses,
                        platformScalars,
                        element,
                        functionTypes,
                        classInstanceTypeIds = classInstanceTypeIds,
                        resolveUnderlyingType = resolveUnderlyingType,
                    )
                } else if (id != null) {
                    ValueType.Ref(nullable = type.isNullable(), type = TypeRef.Local(id))
                } else if (external != null) {
                    ValueType.Ref(nullable = type.isNullable(), type = external)
                } else {
                    throw UnsupportedKotlinIr(element, "unsupported value type")
                }
            } else {
                throw UnsupportedKotlinIr(element, "unsupported value type")
            }
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object KotlinProjectLowering {
    private const val MAXIMUM_TASKS = 64u
    private const val INT_CHANNEL = "compukter.concurrent.IntChannel"
    private const val APPLICATION_STATE = "app.<state>"
    private const val CHAR_ARRAY_RUNTIME_TYPE = 0u
    private const val STRING_RUNTIME_TYPE = 1u
    private val runtimeTypeNames =
        listOf(
            "kotlin.CharArray",
            "kotlin.String",
            "kotlin.Throwable",
            "runtime.IllegalArgumentException",
            "kotlin.IntArray",
            "kotlin.Any",
            "kotlin.Int",
        )

    fun lower(
        functions: List<IrSimpleFunction>,
        properties: List<IrProperty>,
        classes: List<IrClass>,
        entry: IrSimpleFunction,
        pluginContext: IrPluginContext,
        session: CompilationSession,
        includeTrustedPlatformBodies: Boolean = false,
    ): Artifact {
        val guestTypes = GuestTypeRegistry(pluginContext)
        val platformScalars = PlatformScalarRegistry(session.platformScalarTypes, session.platformScalarConstants)
        var usesListFactory = false
        (functions + properties).forEach { root ->
            root.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                    override fun visitCall(expression: IrCall) {
                        if (expression.symbol.owner.fqNameWhenAvailable
                                ?.asString() in
                            setOf("kotlin.collections.listOf", "kotlin.collections.emptyList")
                        ) {
                            usesListFactory = true
                        }
                        super.visitCall(expression)
                    }
                },
                null,
            )
        }
        val specializedCollectionInterfaces =
            setOf(
                "kotlin.collections.Iterable",
                "kotlin.collections.Iterator",
                "kotlin.collections.Collection",
                "kotlin.collections.List",
            )
        val collectionInterfaceClasses =
            if (includeTrustedPlatformBodies) {
                emptyList()
            } else {
                specializedCollectionInterfaces.mapNotNull { name ->
                    pluginContext.referenceClass(ClassId.topLevel(FqName(name)))?.owner
                }
            }
        val sourceClasses =
            (classes + collectionInterfaceClasses)
                .distinctBy { it.symbol }
                .filterNot { includeTrustedPlatformBodies && it.kind == ClassKind.OBJECT }
                .filterNot { declaration ->
                    !includeTrustedPlatformBodies && !usesListFactory &&
                        declaration !in collectionInterfaceClasses &&
                        session.virtualSourcePath(declaration.file.fileEntry.name)?.value?.startsWith("platform/stdlib/collections/") ==
                        true
                }.filterNot {
                    !includeTrustedPlatformBodies &&
                        it.fqNameWhenAvailable?.asString() !in specializedCollectionInterfaces &&
                        session.trustedPlatformModule(it.file.fileEntry.name) != null
                }.sortedBy { it.fqNameWhenAvailable?.asString().orEmpty() }
        sourceClasses
            .firstOrNull { declaration ->
                declaration.typeParameters.isNotEmpty() && declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE)
            }?.let { declaration ->
                throw UnsupportedKotlinIr(declaration, "generic interfaces and non-class declarations are not supported")
            }
        sourceClasses
            .firstOrNull { declaration ->
                declaration.typeParameters.any { parameter ->
                    parameter.variance != Variance.INVARIANT &&
                        !(declaration.kind == ClassKind.INTERFACE && parameter.variance == Variance.OUT_VARIANCE)
                }
            }?.let { declaration ->
                throw UnsupportedKotlinIr(declaration, "generic declaration-site variance is not supported")
            }
        val topLevelProperties =
            properties
                .filterNot {
                    !includeTrustedPlatformBodies &&
                        session.trustedPlatformModule(it.file.fileEntry.name) != null
                }.sortedWith(
                    compareBy(
                        { session.virtualSourcePath(it.file.fileEntry.name)?.value.orEmpty() },
                        IrProperty::startOffset,
                        { it.name.asString() },
                    ),
                ).map(::topLevelProperty)
        val inlineValueClasses = InlineValueClassRegistry.build(classes, pluginContext)
        val sourceClassSymbols = sourceClasses.mapTo(mutableSetOf()) { it.symbol }
        val playerFunctions =
            (
                functions +
                    collectionInterfaceClasses.flatMap { declaration ->
                        declaration.declarations.flatMap { member ->
                            when (member) {
                                is IrSimpleFunction -> listOf(member)
                                is IrProperty -> listOfNotNull(member.getter)
                                else -> emptyList()
                            }
                        }
                    }
            ).distinctBy { it.symbol }
                .filter { function ->
                    val owner = function.parent as? IrClass
                    includeTrustedPlatformBodies ||
                        function.parent is IrFile ||
                        (
                            inlineValueClasses.contains(owner?.symbol) &&
                                function.origin == IrDeclarationOrigin.DEFINED
                        ) ||
                        (
                            owner?.symbol in sourceClassSymbols &&
                                function.origin != IrDeclarationOrigin.FAKE_OVERRIDE &&
                                (function.correspondingPropertySymbol == null || !function.isDirectFieldAccessor())
                        )
                }.filter { function -> function.body != null || function.modality == Modality.ABSTRACT }
                .filterNot { function ->
                    !includeTrustedPlatformBodies &&
                        (function.parent as? IrClass)?.fqNameWhenAvailable?.asString() !in specializedCollectionInterfaces &&
                        session.trustedPlatformModule(function.file.fileEntry.name) != null
                }
        val userFunctions =
            playerFunctions.sortedWith(
                compareBy<IrSimpleFunction>(
                    { if (it === entry) 0 else 1 },
                    { if (it.parent is IrFile || inlineValueClasses.contains((it.parent as? IrClass)?.symbol)) 0 else 1 },
                    { (it.parent as? IrClass)?.fqNameWhenAvailable?.asString().orEmpty() },
                    {
                        if ((it.parent as? IrClass)?.fqNameWhenAvailable?.asString() in specializedCollectionInterfaces) {
                            "<builtins-collection>"
                        } else {
                            session.virtualSourcePath(it.file.fileEntry.name)?.value.orEmpty()
                        }
                    },
                    { it.startOffset },
                    { it.name.asString() },
                ),
            )
        val closureRoots: List<IrElement> =
            userFunctions +
                sourceClasses.flatMap { declaration ->
                    declaration.declarations.filter {
                        it is IrConstructor || it is IrProperty || it is IrAnonymousInitializer
                    }
                }
        val closureSources = collectGuestClosures(closureRoots)
        val captureCellDeclarations =
            closureSources
                .flatMap { it.captures }
                .filterIsInstance<IrVariable>()
                .filter { it.isVar }
                .associateByTo(linkedMapOf()) { it.symbol }
                .values
                .toList()
        val functionShapes =
            buildList {
                fun include(shape: GuestFunctionShape) {
                    if (shape in this) return
                    add(shape)
                    (shape.parameters + shape.result).mapNotNull(IrType::guestFunctionShape).forEach(::include)
                }
                closureSources.mapNotNull { it.expression.type.guestFunctionShape() }.forEach(::include)
                userFunctions.forEach { function ->
                    function.returnType.guestFunctionShape()?.let(::include)
                    loweredParameters(function, session).mapNotNull { it.type.guestFunctionShape() }.forEach(::include)
                }
            }
        val unitBlockShape = GuestFunctionShape(emptyList(), pluginContext.irBuiltIns.unitType)
        val usesFunction0Unit = unitBlockShape in functionShapes
        require(userFunctions.firstOrNull() === entry)
        val userClasses =
            collectGuestClasses(
                sourceClasses.filterNot {
                    inlineValueClasses.contains(it.symbol) || inlineValueClasses.isCompanion(it.symbol)
                },
                userFunctions,
            ).filterNot {
                inlineValueClasses.contains(it.symbol) || inlineValueClasses.isCompanion(it.symbol)
            }
        val constructorClasses =
            userClasses.filter { declaration ->
                declaration.kind == ClassKind.CLASS &&
                    declaration.constructors.any { it.isPrimary }
            }
        val baseFunctionInstances = collectGuestFunctionInstances(userFunctions, constructorClasses)
        val classInstances = collectGuestClassInstances(userClasses, baseFunctionInstances, topLevelProperties)
        val functionInstances =
            collectGuestFunctionInstances(
                userFunctions,
                constructorClasses,
                classInstances.flatMap { classInstance ->
                    if (classInstance.arguments.isEmpty()) {
                        emptyList()
                    } else {
                        userFunctions.filter { it.parent == classInstance.declaration }.map { function ->
                            GuestFunctionInstance(function, emptyList(), classInstance)
                        }
                    }
                },
            )
        val constructorInstances =
            classInstances.filter { instance ->
                instance.declaration.kind == ClassKind.CLASS && instance.declaration.constructors.any { it.isPrimary }
            }
        val initializerClasses =
            userClasses.filter { declaration ->
                declaration.kind == ClassKind.ENUM_CLASS && declaration.declarations.any { it is IrEnumEntry }
            }
        val externalFunctions = linkedPlatformFunctions(userFunctions + constructorClasses, session)
        val linkedSymbols = linkedPlatformSymbols(userFunctions + constructorClasses, session)

        val intrinsicCollector =
            IntrinsicCollector { function ->
                resolveTrustedIntrinsic(function, session)
            }
        userFunctions.forEach { function -> function.accept(intrinsicCollector, null) }
        constructorClasses.forEach { declaration -> declaration.accept(intrinsicCollector, null) }
        val capabilityIdentities =
            (
                intrinsicCollector.capabilities +
                    session.canonicalIntrinsicRegistry
                        ?.handlers
                        ?.values
                        ?.filterIsInstance<CapabilityOperationHandler>()
                        ?.map { handler ->
                            val capability = handler.requiredCapability
                            LoweredCapabilityIdentity(
                                capability.namespace,
                                capability.name,
                                capability.abiMajor.toUShort(),
                                capabilityShape(capability, session).abiMinor.toUShort(),
                                capabilityShape(capability, session).operationCount,
                            )
                        }.orEmpty()
            ).distinct().sorted()
        val capabilityIds =
            capabilityIdentities.withIndex().associate { (index, identity) -> identity to CapabilityId.of(index.toUInt()) }

        val stringArrayUsage = StringArrayUsageCollector(guestTypes)
        userFunctions.forEach { function -> function.accept(stringArrayUsage, null) }
        val usesStringArray =
            stringArrayUsage.used ||
                userFunctions.any { function ->
                    guestTypes.isStringArray(function.returnType) ||
                        loweredParameters(function, session).any { parameter -> guestTypes.isStringArray(parameter.type) }
                }
        val referenceArrayUsage =
            ReferenceArrayUsageCollector(guestTypes, userClasses.associateBy { it.symbol }, classInstances.toSet())
        functionInstances.forEach { instance ->
            referenceArrayUsage.consider(instance.declaration.returnType, instance::substitute)
            loweredParameters(instance.declaration, session).forEach {
                referenceArrayUsage.consider(it.type, instance::substitute)
            }
            referenceArrayUsage.scan(instance.declaration, instance::substitute)
        }
        classInstances.forEach { instance ->
            referenceArrayUsage.scan(instance.declaration, instance::substitute)
        }
        topLevelProperties.forEach { referenceArrayUsage.scan(it.declaration) }
        val referenceArrays = referenceArrayUsage.arrays.toSortedMap()
        val functionArtifactNames =
            functionInstances.associateWith { instance ->
                val function = instance.declaration
                val bridgeName =
                    when (function.fqNameWhenAvailable?.asString()) {
                        "kotlin.collections.IntArrayBackedList.getAny" -> "get"
                        "kotlin.collections.IntArrayBackedList.iteratorAny" -> "iterator"
                        "kotlin.collections.IntArrayBackedListIterator.nextAny" -> "next"
                        "kotlin.collections.ArrayBackedList.getAny" -> "get"
                        "kotlin.collections.ArrayBackedList.iteratorAny" -> "iterator"
                        "kotlin.collections.ArrayBackedListIterator.nextAny" -> "next"
                        else -> null
                    }
                if (bridgeName != null) {
                    bridgeName
                } else if (instance.ownerClass != null) {
                    function.name.asString()
                } else if (instance.arguments.isEmpty()) {
                    artifactFunctionName(function, pluginContext, inlineValueClasses, session)
                } else {
                    val arguments = instance.arguments.joinToString(",") { it.canonicalPlatformType() }
                    "${function.fqNameWhenAvailable?.asString() ?: function.name.asString()}<$arguments>"
                }
            }
        val metadataValues =
            (
                listOf("app") +
                    runtimeTypeNames +
                    INT_BOX_VALUE_NAME +
                    listOfNotNull("kotlin.Array".takeIf { usesStringArray }) +
                    referenceArrays.keys +
                    capabilityIdentities.flatMap { listOf(it.namespace, it.name) } +
                    externalFunctions.values.map(ExternalFunctionTarget::exportName) +
                    linkedSymbols.types.values.map(ExternalTypeTarget::exportName) +
                    linkedSymbols.fieldsByGetter.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.enumEntries.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.defaultEnumEntries.values.map(ExternalFieldTarget::exportName) +
                    functionInstances.map { requireNotNull(functionArtifactNames[it]) } +
                    functionShapes.indices.map { index -> functionShapeName(index, functionShapes[index], unitBlockShape) } +
                    listOfNotNull("invoke".takeIf { functionShapes.isNotEmpty() }) +
                    listOfNotNull("<task-launch>".takeIf { usesFunction0Unit }) +
                    closureSources.flatMap { closure ->
                        listOf(closureName(closure.ordinal)) +
                            (0 until closure.captures.size + if (closure.boundReceiver == null) 0 else 1)
                                .map(::closureCaptureName)
                    } +
                    captureCellDeclarations.indices.map { cell -> captureCellName(cell) } +
                    listOfNotNull("<value>".takeIf { captureCellDeclarations.isNotEmpty() }) +
                    classInstances.map(GuestClassInstance::name) +
                    userClasses.flatMap { declaration ->
                        val owner = declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
                        val fieldNames =
                            declaration.declarations.filterIsInstance<IrProperty>().map { it.name.asString() } +
                                declaration.declarations.filterIsInstance<IrEnumEntry>().map { it.name.asString() }
                        fieldNames +
                            if (includeTrustedPlatformBodies) {
                                fieldNames.map { field -> "$owner.$field" }
                            } else {
                                emptyList()
                            }
                    } +
                    topLevelProperties.map { it.declaration.name.asString() } +
                    listOfNotNull(APPLICATION_STATE.takeIf { topLevelProperties.isNotEmpty() }) +
                    constructorInstances.map(::constructorName) +
                    listOfNotNull("<clinit>".takeIf { initializerClasses.isNotEmpty() || topLevelProperties.isNotEmpty() })
            ).distinct()
                .map(MetadataText::of)
                .sorted()
        val metadataIds = metadataValues.withIndex().associate { (index, value) -> value.toString() to StringId.of(index.toUInt()) }
        val literalCollector =
            LiteralCollector(
                pluginContext.irBuiltIns.unitType,
                pluginContext.irBuiltIns.longType,
                pluginContext.irBuiltIns.floatType,
            ).also { collector ->
                userFunctions.forEach { function -> function.accept(collector, null) }
                constructorClasses.forEach { declaration -> declaration.accept(collector, null) }
                topLevelProperties.forEach { property ->
                    property.declaration.backingField
                        ?.initializer
                        ?.expression
                        ?.accept(collector, null)
                }
            }
        var needsAllBitsI32 = false
        var needsIntegerCompareToResult = false
        var needsFloatCompareToResult = false
        var needsStringCompareToStep = false
        var needsZeroI64 = false
        var needsAllBitsI64 = false
        (userFunctions + constructorClasses).forEach { function ->
            function.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildren(this, null)
                    }

                    override fun visitCall(expression: IrCall) {
                        val callee =
                            expression.symbol.owner.fqNameWhenAvailable
                                ?.asString()
                        if (callee == "kotlin.Int.inv") needsAllBitsI32 = true
                        if (callee == "kotlin.Long.unaryMinus") needsZeroI64 = true
                        if (callee == "kotlin.Long.inv") needsAllBitsI64 = true
                        if (callee == "kotlin.String.compareTo") needsStringCompareToStep = true
                        if (
                            callee == "kotlin.Int.compareTo" ||
                            callee == "kotlin.Long.compareTo" ||
                            callee == "kotlin.Float.compareTo"
                        ) {
                            val operands = expression.arguments.filterNotNull()
                            if (operands.size == 2) {
                                val types = operands.map { it.type }
                                if (types.all { it == pluginContext.irBuiltIns.intType || it == pluginContext.irBuiltIns.longType }) {
                                    needsIntegerCompareToResult = true
                                } else if (
                                    types.any { it == pluginContext.irBuiltIns.floatType } &&
                                    types.all {
                                        it == pluginContext.irBuiltIns.intType ||
                                            it == pluginContext.irBuiltIns.longType ||
                                            it == pluginContext.irBuiltIns.floatType
                                    }
                                ) {
                                    needsFloatCompareToResult = true
                                }
                            }
                        }
                        super.visitCall(expression)
                    }
                },
                null,
            )
        }
        val literals =
            literalCollector.strings
                .distinct()
                .map {
                    Utf16Literal.fromString(it)
                }.sorted()
        val literalIds = literals.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        val constantPool = ConstantPoolBuilder()
        (
            (
                literalCollector.values +
                    topLevelProperties.mapNotNull { property ->
                        when (val initializer = property.initializer) {
                            TopLevelInitializer.Null -> null
                            is TopLevelInitializer.Scalar -> initializer.value
                            is TopLevelInitializer.Channel -> initializer.capacity
                        }
                    } +
                    inlineValueClasses.constantValues() +
                    platformScalars.constantValues() +
                    linkedSymbols.defaultIntValues
            ).map { value -> value.toArtifactConstant(literalIds) } +
                Constant.I32(0) +
                listOfNotNull(Constant.I32(-1).takeIf { needsAllBitsI32 || needsIntegerCompareToResult || needsFloatCompareToResult }) +
                listOfNotNull(
                    Constant.I32(1).takeIf {
                        needsIntegerCompareToResult || needsFloatCompareToResult || needsStringCompareToStep
                    },
                ) +
                listOfNotNull(Constant.I64(0).takeIf { literalCollector.usesLong || needsZeroI64 }) +
                listOfNotNull(Constant.I64(-1).takeIf { needsAllBitsI64 }) +
                listOfNotNull(Constant.F32(0u).takeIf { literalCollector.usesFloat }) +
                listOfNotNull(Constant.F32((-1.0f).toBits().toUInt()).takeIf { literalCollector.usesFloat }) +
                listOfNotNull(Constant.F32(1.0f.toBits().toUInt()).takeIf { needsFloatCompareToResult }) +
                Constant.Bool(false)
        ).forEach(constantPool::intern)
        val constants = constantPool.freeze().records
        val constantIds = constants.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }

        val library = kotlinLibrary()
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val charArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(CHAR_ARRAY_RUNTIME_TYPE)))
        val stringType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(STRING_RUNTIME_TYPE)))
        val intArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(INT_ARRAY_RUNTIME_TYPE)))
        val instanceFunctionIds =
            functionInstances.withIndex().associate { (index, instance) -> instance to FunctionId.of(index.toUInt()) }
        val instanceTypeIds =
            functionInstances.withIndex().associate { (index, instance) -> instance to TypeId.of(index.toUInt()) }
        val functionIds =
            instanceFunctionIds.entries
                .filter { it.key.arguments.isEmpty() && it.key.ownerClass == null }
                .associate { it.key.declaration.symbol to it.value }
        val shapeInvokeFunctionIds =
            functionShapes.withIndex().associate { (index, shape) ->
                shape to FunctionId.of((functionInstances.size + index).toUInt())
            }
        val shapeInvokeSignatureTypeIds =
            functionShapes.withIndex().associate { (index, shape) ->
                shape to TypeId.of((functionInstances.size + index).toUInt())
            }
        val closureInvokeFunctionIds =
            closureSources.withIndex().associate { (index, source) ->
                source.expression to FunctionId.of((functionInstances.size + functionShapes.size + index).toUInt())
            }
        val closureInvokeSignatureTypeIds =
            closureSources.withIndex().associate { (index, source) ->
                source.expression to TypeId.of((functionInstances.size + functionShapes.size + index).toUInt())
            }
        val taskLaunchTrampolineFunctionId =
            FunctionId.of((functionInstances.size + functionShapes.size + closureSources.size).toUInt()).takeIf { usesFunction0Unit }
        val taskLaunchTrampolineSignatureTypeId =
            TypeId.of((functionInstances.size + functionShapes.size + closureSources.size).toUInt()).takeIf { usesFunction0Unit }
        val syntheticFunctionCount = closureSources.size + functionShapes.size + if (usesFunction0Unit) 1 else 0
        val constructorFunctionBase = functionInstances.size + syntheticFunctionCount
        val constructorFunctionIds =
            constructorInstances.withIndex().associate { (index, instance) ->
                instance to FunctionId.of((constructorFunctionBase + index).toUInt())
            }
        val constructorTypeIds =
            constructorInstances.withIndex().associate { (index, instance) ->
                instance to TypeId.of((constructorFunctionBase + index).toUInt())
            }
        val initializerFunctionIds =
            initializerClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to FunctionId.of((constructorFunctionBase + constructorInstances.size + index).toUInt())
            }
        val classTypeBase = constructorFunctionBase + constructorInstances.size
        val classInstanceTypeIds =
            classInstances.withIndex().associate { (index, instance) ->
                instance to TypeId.of((classTypeBase + index).toUInt())
            }
        val classTypeIds =
            classInstanceTypeIds.entries.filter { it.key.arguments.isEmpty() }.associate { it.key.declaration.symbol to it.value }
        val shapeInterfaceTypeIds =
            functionShapes.withIndex().associate { (index, shape) ->
                shape to TypeId.of((classTypeBase + classInstances.size + index).toUInt())
            }
        val shapeInterfaceTypes = shapeInterfaceTypeIds.mapValues { (_, id) -> TypeRef.Local(id) }
        val closureTypeIds =
            closureSources.withIndex().associate { (index, source) ->
                source.expression to TypeId.of((classTypeBase + classInstances.size + functionShapes.size + index).toUInt())
            }
        val captureCellTypeIds =
            captureCellDeclarations.withIndex().associate { (index, declaration) ->
                declaration.symbol to
                    TypeId.of(
                        (classTypeBase + classInstances.size + functionShapes.size + closureSources.size + index).toUInt(),
                    )
            }
        val syntheticClassCount =
            closureSources.size + captureCellDeclarations.size + functionShapes.size
        val topLevelStateTypeId =
            TypeId
                .of((classTypeBase + classInstances.size + syntheticClassCount).toUInt())
                .takeIf { topLevelProperties.isNotEmpty() }
        val stateTypeCount = if (topLevelStateTypeId == null) 0 else 1
        val initializerTypeBase =
            classTypeBase +
                classInstances.size +
                syntheticClassCount +
                stateTypeCount +
                (if (usesStringArray) 1 else 0) +
                referenceArrays.size
        val initializerTypeIds =
            initializerClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to TypeId.of((initializerTypeBase + index).toUInt())
            }
        val topLevelInitializerFunctionId =
            FunctionId
                .of((constructorFunctionBase + constructorInstances.size + initializerClasses.size).toUInt())
                .takeIf { topLevelProperties.isNotEmpty() }
        val topLevelInitializerTypeId =
            TypeId
                .of((initializerTypeBase + initializerClasses.size).toUInt())
                .takeIf { topLevelProperties.isNotEmpty() }
        val externalTypeImports =
            linkedSymbols.types.entries
                .sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (symbol, target) ->
                    symbol to target.copy(importId = ImportId.of((runtimeTypeNames.size + 1 + index).toUInt()))
                }.toMap()
        val externalClassTypes = externalTypeImports.mapValues { (_, target) -> TypeRef.Imported(target.importId) }
        val externalFieldImports =
            (linkedSymbols.fieldsByGetter.values + linkedSymbols.enumEntries.values + linkedSymbols.defaultEnumEntries.values)
                .distinctBy(ExternalFieldTarget::sortKey)
                .sortedBy(ExternalFieldTarget::sortKey)
                .mapIndexed { index, target ->
                    target.copy(importId = ImportId.of((runtimeTypeNames.size + 1 + externalTypeImports.size + index).toUInt()))
                }
        val externalFieldsBySortKey = externalFieldImports.associateBy(ExternalFieldTarget::sortKey)
        val externalGetterFieldImports =
            linkedSymbols.fieldsByGetter.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalEnumFieldImports =
            linkedSymbols.enumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalDefaultEnumFieldImports =
            linkedSymbols.defaultEnumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalFieldImportCount = externalFieldImports.size
        val externalFunctionTypeBase = initializerTypeBase + initializerClasses.size + stateTypeCount
        val externalFunctionImports =
            externalFunctions.entries
                .sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (symbol, target) ->
                    symbol to
                        target.copy(
                            importId =
                                ImportId.of(
                                    (runtimeTypeNames.size + 1 + externalTypeImports.size + externalFieldImportCount + index).toUInt(),
                                ),
                        )
                }.toMap()
        val stringArrayType =
            ValueType.Ref(
                nullable = false,
                type =
                    TypeRef.Local(
                        TypeId.of(
                            (classTypeBase + classInstances.size + syntheticClassCount + stateTypeCount).toUInt(),
                        ),
                    ),
            )
        val referenceArrayTypeBase =
            classTypeBase + classInstances.size + syntheticClassCount + stateTypeCount + (if (usesStringArray) 1 else 0)
        val referenceArrayTypes =
            referenceArrays.keys.withIndex().associate { (index, name) ->
                name to
                    ValueType.Ref(
                        nullable = false,
                        type = TypeRef.Local(TypeId.of((referenceArrayTypeBase + index).toUInt())),
                    )
            }
        guestTypes.registerReferenceArrays(referenceArrayTypes)
        functionInstances.forEach { instance ->
            validateFunction(
                instance.declaration,
                pluginContext,
                guestTypes,
                classTypeIds,
                classInstanceTypeIds,
                externalClassTypes,
                inlineValueClasses,
                platformScalars,
                session,
                shapeInterfaceTypes,
                instance,
            )
        }
        val classLayouts =
            buildClassLayouts(
                classInstances,
                classTypeIds,
                classInstanceTypeIds,
                pluginContext,
                guestTypes,
                stringType,
                charArrayType,
                stringArrayType,
                inlineValueClasses,
                platformScalars,
                externalClassTypes,
            )
        val classLayoutsByInstance = classLayouts.associateBy(GuestClassLayout::instance)
        val classLayoutsBySymbol =
            classLayouts.filter { it.instance.arguments.isEmpty() }.associateBy { it.declaration.symbol }
        val fieldsByBacking =
            classLayouts
                .filter { it.instance.arguments.isEmpty() }
                .flatMap { layout ->
                    layout.fields.map { field -> requireNotNull(field.property.backingField).symbol to field }
                }.toMap()
        val fieldsByGetter =
            classLayouts
                .filter { it.instance.arguments.isEmpty() }
                .flatMap { layout ->
                    layout.fields.mapNotNull { field ->
                        field.property.getter
                            ?.takeIf(IrSimpleFunction::isDirectFieldAccessor)
                            ?.symbol
                            ?.let { it to field }
                    }
                }.toMap()
        val fieldsBySetter =
            classLayouts
                .filter { it.instance.arguments.isEmpty() }
                .flatMap { layout ->
                    layout.fields.mapNotNull { field ->
                        field.property.setter
                            ?.takeIf(IrSimpleFunction::isDirectFieldAccessor)
                            ?.symbol
                            ?.let { it to field }
                    }
                }.toMap()
        val constructorTargets =
            classLayouts
                .filter { it.instance.arguments.isEmpty() }
                .mapNotNull { layout ->
                    layout.declaration.constructors.singleOrNull { it.isPrimary }?.symbol?.let { symbol ->
                        constructorFunctionIds[layout.instance]?.let { symbol to GuestConstructorTarget(layout, it) }
                    }
                }.toMap()
        val genericConstructorTargets =
            classLayouts.filter { it.instance.arguments.isNotEmpty() && it.declaration.kind == ClassKind.CLASS }.associate { layout ->
                layout.instance to GuestConstructorTarget(layout, requireNotNull(constructorFunctionIds[layout.instance]))
            }
        val genericFieldsByBacking =
            classLayouts
                .filter { it.instance.arguments.isNotEmpty() }
                .flatMap { layout ->
                    layout.fields.map { field ->
                        (requireNotNull(field.property.backingField).symbol to layout.instance) to field
                    }
                }.toMap()
        val genericFieldsByGetter =
            classLayouts
                .filter { it.instance.arguments.isNotEmpty() }
                .flatMap { layout ->
                    layout.fields.mapNotNull { field ->
                        field.property.getter?.takeIf(IrSimpleFunction::isDirectFieldAccessor)?.symbol?.let { symbol ->
                            (symbol to layout.instance) to field
                        }
                    }
                }.toMap()
        val genericFieldsBySetter =
            classLayouts
                .filter { it.instance.arguments.isNotEmpty() }
                .flatMap { layout ->
                    layout.fields.mapNotNull { field ->
                        field.property.setter?.takeIf(IrSimpleFunction::isDirectFieldAccessor)?.symbol?.let { symbol ->
                            (symbol to layout.instance) to field
                        }
                    }
                }.toMap()
        var nextClosureField = classLayouts.sumOf { layout -> layout.fields.size + layout.enumEntries.size }
        val captureCellLayouts =
            captureCellDeclarations.mapIndexed { ordinal, declaration ->
                GuestCaptureCellLayout(
                    declaration = declaration,
                    ordinal = ordinal,
                    typeId = requireNotNull(captureCellTypeIds[declaration.symbol]),
                    fieldId = FieldId.of(nextClosureField++.toUInt()),
                    valueType =
                        valueType(
                            declaration.type,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            declaration,
                            shapeInterfaceTypes,
                        ),
                )
            }
        val captureCellLayoutsBySymbol: Map<IrValueSymbol, GuestCaptureCellLayout> =
            captureCellLayouts.associateBy { it.declaration.symbol }
        val closureLayouts =
            closureSources.map { source ->
                GuestClosureLayout(
                    expression = source.expression,
                    function = source.function,
                    referenceTarget = source.referenceTarget,
                    constructorTarget = source.constructorTarget,
                    ordinal = source.ordinal,
                    typeId = requireNotNull(closureTypeIds[source.expression]),
                    invokeFunctionId = requireNotNull(closureInvokeFunctionIds[source.expression]),
                    invokeSignatureTypeId = requireNotNull(closureInvokeSignatureTypeIds[source.expression]),
                    shape = requireNotNull(source.expression.type.guestFunctionShape()),
                    captures =
                        source.captures.map { declaration ->
                            val cell = captureCellLayoutsBySymbol[declaration.symbol]
                            GuestClosureCapture(
                                symbol = declaration.symbol,
                                initialValue = null,
                                fieldId = FieldId.of(nextClosureField++.toUInt()),
                                type =
                                    cell?.let {
                                        ValueType.Ref(nullable = false, type = TypeRef.Local(it.typeId))
                                    } ?: valueType(
                                        declaration.type,
                                        pluginContext,
                                        guestTypes,
                                        stringType,
                                        charArrayType,
                                        stringArrayType,
                                        classTypeIds,
                                        externalClassTypes,
                                        inlineValueClasses,
                                        platformScalars,
                                        declaration,
                                        shapeInterfaceTypes,
                                    ),
                                cell = cell,
                            )
                        } +
                            listOfNotNull(
                                source.boundReceiver?.let { receiver ->
                                    GuestClosureCapture(
                                        symbol = null,
                                        initialValue = receiver,
                                        fieldId = FieldId.of(nextClosureField++.toUInt()),
                                        type =
                                            valueType(
                                                receiver.type,
                                                pluginContext,
                                                guestTypes,
                                                stringType,
                                                charArrayType,
                                                stringArrayType,
                                                classTypeIds,
                                                externalClassTypes,
                                                inlineValueClasses,
                                                platformScalars,
                                                receiver,
                                                shapeInterfaceTypes,
                                            ),
                                        cell = null,
                                    )
                                },
                            ),
                )
            }
        val closureLayoutsByExpression = closureLayouts.associateBy { it.expression }
        val memberFunctionsByOwner =
            userFunctions
                .filter { function ->
                    val owner = function.parent as? IrClass
                    owner?.symbol in classLayoutsBySymbol && !inlineValueClasses.contains(owner?.symbol)
                }.groupBy { function -> (function.parent as IrClass).symbol }
        val genericMemberFunctionsByOwner =
            functionInstances.filter { it.ownerClass != null }.groupBy { requireNotNull(it.ownerClass) }
        val genericMemberFunctionIds =
            functionInstances.filter { it.ownerClass != null }.associate { instance ->
                (instance.declaration.symbol to requireNotNull(instance.ownerClass)) to requireNotNull(instanceFunctionIds[instance])
            }
        val firstTopLevelField = nextClosureField
        val topLevelFields =
            topLevelProperties.mapIndexed { index, property ->
                val backingField = requireNotNull(property.declaration.backingField)
                TopLevelFieldLayout(
                    property = property,
                    fieldId = FieldId.of((firstTopLevelField + index).toUInt()),
                    type =
                        valueType(
                            backingField.type,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            property.declaration,
                        ),
                )
            }
        val topLevelFieldsByBacking =
            topLevelFields.associateBy { requireNotNull(it.property.declaration.backingField).symbol }
        val topLevelFieldsByGetter =
            topLevelFields.associateBy { requireNotNull(it.property.declaration.getter).symbol }
        val blocks = mutableListOf<Block>()
        val loweredFunctions = mutableListOf<Function>()

        functionInstances.forEach { instance ->
            val function = instance.declaration
            val functionId = requireNotNull(instanceFunctionIds[instance])
            val firstBlock = blocks.size
            val compiled =
                if (function.body == null) {
                    CompiledFunction(emptyList(), emptyList())
                } else {
                    try {
                        FunctionCompiler(
                            pluginContext = pluginContext,
                            function = function,
                            functionId = functionId,
                            blockBase = firstBlock,
                            stringType = stringType,
                            charArrayType = charArrayType,
                            intArrayType = intArrayType,
                            stringArrayType = stringArrayType,
                            guestTypes = guestTypes,
                            unitType = pluginContext.irBuiltIns.unitType,
                            kotlinStringType = pluginContext.irBuiltIns.stringType,
                            kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                            kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                            intType = pluginContext.irBuiltIns.intType,
                            longType = pluginContext.irBuiltIns.longType,
                            floatType = pluginContext.irBuiltIns.floatType,
                            booleanType = pluginContext.irBuiltIns.booleanType,
                            charType = pluginContext.irBuiltIns.charType,
                            functionIds = functionIds,
                            genericFunctionIds = instanceFunctionIds,
                            genericMemberFunctionIds = genericMemberFunctionIds,
                            currentInstance = instance,
                            currentClassInstance = instance.ownerClass,
                            constantIds = constantIds,
                            literalIds = literalIds,
                            session = session,
                            capabilityIds = capabilityIds,
                            classTypeIds = classTypeIds,
                            classInstanceTypeIds = classInstanceTypeIds,
                            externalClassTypes = externalClassTypes,
                            inlineValueClasses = inlineValueClasses,
                            platformScalars = platformScalars,
                            constructorLayouts = constructorTargets,
                            genericConstructorLayouts = genericConstructorTargets,
                            fieldsBySetter = fieldsBySetter,
                            fieldsByGetter = fieldsByGetter,
                            fieldsByBacking = fieldsByBacking,
                            genericFieldsBySetter = genericFieldsBySetter,
                            genericFieldsByGetter = genericFieldsByGetter,
                            genericFieldsByBacking = genericFieldsByBacking,
                            topLevelFieldsByBacking = topLevelFieldsByBacking,
                            topLevelFieldsByGetter = topLevelFieldsByGetter,
                            enumEntries =
                                classLayouts.flatMap { layout -> layout.enumEntries }.associateBy { it.declaration.symbol },
                            externalFieldsByGetter = externalGetterFieldImports,
                            externalEnumEntries = externalEnumFieldImports,
                            externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                            externalFunctions = externalFunctionImports,
                            functionTypes = shapeInterfaceTypes,
                            invokeFunctionIds = shapeInvokeFunctionIds,
                            taskLaunchTrampolineFunctionId = taskLaunchTrampolineFunctionId,
                            closureLayouts = closureLayoutsByExpression,
                            captureCells = captureCellLayoutsBySymbol,
                        ).compile()
                    } catch (unsupported: UnsupportedKotlinIr) {
                        if (session.virtualSourcePath(function.file.fileEntry.name)?.value?.startsWith("platform/") == true) {
                            throw UnsupportedKotlinIr(function, unsupported.message.orEmpty())
                        }
                        throw unsupported
                    }
                }
            blocks += compiled.blocks
            val resultType =
                valueType(
                    function.returnType,
                    pluginContext,
                    guestTypes,
                    stringType,
                    charArrayType,
                    stringArrayType,
                    classTypeIds,
                    externalClassTypes,
                    inlineValueClasses,
                    platformScalars,
                    function,
                    shapeInterfaceTypes,
                    instance,
                    classInstanceTypeIds,
                )
            val parameterTypes =
                loweredParameters(function, session).map {
                    valueType(
                        it.type,
                        pluginContext,
                        guestTypes,
                        stringType,
                        charArrayType,
                        stringArrayType,
                        classTypeIds,
                        externalClassTypes,
                        inlineValueClasses,
                        platformScalars,
                        it,
                        shapeInterfaceTypes,
                        instance,
                        classInstanceTypeIds,
                    )
                }
            val ownerClass = function.parent as? IrClass
            val memberOwner =
                instance.ownerClass?.let { owner -> TypeRef.Local(requireNotNull(classInstanceTypeIds[owner])) }
                    ?: ownerClass
                        ?.takeIf { it.symbol in classLayoutsBySymbol && !inlineValueClasses.contains(it.symbol) }
                        ?.let { TypeRef.Local(requireNotNull(classTypeIds[it.symbol])) }
            val flags =
                setOfNotNull(
                    FunctionFlag.STATIC.takeIf { memberOwner == null },
                    FunctionFlag.SUSPENDING.takeIf { function.isSuspend },
                    FunctionFlag.ABSTRACT.takeIf { function.body == null },
                    FunctionFlag.VIRTUAL.takeIf {
                        memberOwner != null && ownerClass?.kind != ClassKind.INTERFACE &&
                            (function.modality != Modality.FINAL || function.overriddenSymbols.isNotEmpty())
                    },
                )
            loweredFunctions +=
                Function(
                    owner = memberOwner,
                    name = requireNotNull(metadataIds[functionArtifactNames[instance]]),
                    signature = TypeRef.Local(requireNotNull(instanceTypeIds[instance])),
                    flags = flags,
                    values = (parameterTypes + compiled.localTypes).map(FunctionValue::scalar),
                    parameterCount = parameterTypes.size.toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        val shapeValueType: (IrType) -> ValueType = { type ->
            valueType(
                type,
                pluginContext,
                guestTypes,
                stringType,
                charArrayType,
                stringArrayType,
                classTypeIds,
                externalClassTypes,
                inlineValueClasses,
                platformScalars,
                entry,
                shapeInterfaceTypes,
            )
        }
        functionShapes.forEach { shape ->
            val interfaceType = requireNotNull(shapeInterfaceTypes[shape])
            loweredFunctions +=
                Function(
                    owner = interfaceType,
                    name = requireNotNull(metadataIds["invoke"]),
                    signature = TypeRef.Local(requireNotNull(shapeInvokeSignatureTypeIds[shape])),
                    flags = setOf(FunctionFlag.ABSTRACT),
                    values =
                        (listOf(ValueType.Ref(nullable = false, type = interfaceType)) + shape.parameters.map(shapeValueType))
                            .map(FunctionValue::scalar),
                    parameterCount = (shape.arity + 1).toUInt(),
                    firstBlock = BlockId.of(blocks.size.toUInt()),
                    blockCount = 0u,
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        closureLayouts.forEach { layout ->
            val closureType = TypeRef.Local(layout.typeId)
            val receiverType = ValueType.Ref(nullable = false, type = closureType)
            val firstBlock = blocks.size
            val compiled =
                if (layout.referenceTarget != null || layout.constructorTarget != null) {
                    val targetId =
                        layout.referenceTarget?.let { functionIds[it.symbol] }
                            ?: layout.constructorTarget?.let { constructor ->
                                constructorFunctionIds[GuestClassInstance(constructor.owner.parentAsClass, emptyList())]
                                    ?: throw UnsupportedKotlinIr(
                                        layout.expression,
                                        "constructor reference target is outside the supported Guest project subset",
                                    )
                            }
                            ?: throw UnsupportedKotlinIr(layout.expression, "function reference target is not in the Guest project")
                    val boundCapture = layout.captures.singleOrNull { it.initialValue != null }
                    val boundRegister = boundCapture?.let { RegisterId.of((layout.shape.arity + 1).toUInt()) }
                    val resultType = shapeValueType(layout.shape.result)
                    val destination =
                        if (resultType ==
                            ValueType.Unit
                        ) {
                            Destination.Unit
                        } else {
                            Destination.Register(RegisterId.of((layout.shape.arity + 1 + if (boundRegister == null) 0 else 1).toUInt()))
                        }
                    val arguments = listOfNotNull(boundRegister) + (1..layout.shape.arity).map { RegisterId.of(it.toUInt()) }
                    val owner = layout.referenceTarget?.parent as? IrClass
                    val constructorType =
                        layout.constructorTarget?.let { constructor ->
                            TypeRef.Local(
                                (
                                    constructorTargets[constructor]
                                        ?: throw UnsupportedKotlinIr(
                                            layout.expression,
                                            "constructor reference target is outside the supported Guest project subset",
                                        )
                                ).layout.typeId,
                            )
                        }
                    val call =
                        when {
                            constructorType != null -> {
                                Instruction.Call(
                                    Destination.Unit,
                                    FunctionRef.Local(targetId),
                                    listOf((destination as Destination.Register).id) + arguments,
                                )
                            }

                            owner?.kind == ClassKind.INTERFACE -> {
                                Instruction.CallInterface(destination, FunctionRef.Local(targetId), arguments)
                            }

                            owner != null &&
                                (
                                    layout.referenceTarget.modality != Modality.FINAL ||
                                        layout.referenceTarget.overriddenSymbols.isNotEmpty()
                                ) -> {
                                Instruction.CallVirtual(destination, FunctionRef.Local(targetId), arguments)
                            }

                            else -> {
                                Instruction.Call(destination, FunctionRef.Local(targetId), arguments)
                            }
                        }
                    CompiledFunction(
                        localTypes =
                            listOfNotNull(boundCapture?.type) + if (resultType == ValueType.Unit) emptyList() else listOf(resultType),
                        blocks =
                            listOf(
                                Block(
                                    layout.invokeFunctionId,
                                    false,
                                    listOfNotNull(
                                        boundCapture?.let {
                                            Instruction.FieldGet(
                                                requireNotNull(boundRegister),
                                                RegisterId.of(0u),
                                                FieldRef.Local(it.fieldId),
                                            )
                                        },
                                        constructorType?.let {
                                            Instruction.NewObject((destination as Destination.Register).id, it)
                                        },
                                    ) + listOf(call, Instruction.Return(destination)),
                                ),
                            ),
                    )
                } else {
                    FunctionCompiler(
                        pluginContext = pluginContext,
                        function = requireNotNull(layout.function),
                        functionId = layout.invokeFunctionId,
                        blockBase = firstBlock,
                        stringType = stringType,
                        charArrayType = charArrayType,
                        intArrayType = intArrayType,
                        stringArrayType = stringArrayType,
                        guestTypes = guestTypes,
                        unitType = pluginContext.irBuiltIns.unitType,
                        kotlinStringType = pluginContext.irBuiltIns.stringType,
                        kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                        kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                        intType = pluginContext.irBuiltIns.intType,
                        longType = pluginContext.irBuiltIns.longType,
                        floatType = pluginContext.irBuiltIns.floatType,
                        booleanType = pluginContext.irBuiltIns.booleanType,
                        charType = pluginContext.irBuiltIns.charType,
                        functionIds = functionIds,
                        genericFunctionIds = instanceFunctionIds,
                        genericMemberFunctionIds = genericMemberFunctionIds,
                        constantIds = constantIds,
                        literalIds = literalIds,
                        session = session,
                        capabilityIds = capabilityIds,
                        classTypeIds = classTypeIds,
                        classInstanceTypeIds = classInstanceTypeIds,
                        externalClassTypes = externalClassTypes,
                        inlineValueClasses = inlineValueClasses,
                        platformScalars = platformScalars,
                        constructorLayouts = constructorTargets,
                        genericConstructorLayouts = genericConstructorTargets,
                        fieldsBySetter = fieldsBySetter,
                        fieldsByGetter = fieldsByGetter,
                        fieldsByBacking = fieldsByBacking,
                        genericFieldsBySetter = genericFieldsBySetter,
                        genericFieldsByGetter = genericFieldsByGetter,
                        genericFieldsByBacking = genericFieldsByBacking,
                        topLevelFieldsByBacking = topLevelFieldsByBacking,
                        topLevelFieldsByGetter = topLevelFieldsByGetter,
                        enumEntries = classLayouts.flatMap { it.enumEntries }.associateBy { it.declaration.symbol },
                        externalFieldsByGetter = externalGetterFieldImports,
                        externalEnumEntries = externalEnumFieldImports,
                        externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                        externalFunctions = externalFunctionImports,
                        functionTypes = shapeInterfaceTypes,
                        invokeFunctionIds = shapeInvokeFunctionIds,
                        taskLaunchTrampolineFunctionId = taskLaunchTrampolineFunctionId,
                        closureLayouts = closureLayoutsByExpression,
                        captureCells = captureCellLayoutsBySymbol,
                        leadingParameterTypes = listOf(receiverType),
                        captureFields = layout.captures.mapNotNull { capture -> capture.symbol?.let { it to capture } }.toMap(),
                        closureReceiver = RegisterId.of(0u),
                    ).compile()
                }
            blocks += compiled.blocks
            loweredFunctions +=
                Function(
                    owner = closureType,
                    name = requireNotNull(metadataIds["invoke"]),
                    signature = TypeRef.Local(layout.invokeSignatureTypeId),
                    flags = setOf(FunctionFlag.VIRTUAL),
                    values =
                        (listOf(receiverType) + layout.shape.parameters.map(shapeValueType) + compiled.localTypes)
                            .map(FunctionValue::scalar),
                    parameterCount = (layout.shape.arity + 1).toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        if (taskLaunchTrampolineFunctionId != null) {
            val interfaceType = requireNotNull(shapeInterfaceTypes[unitBlockShape])
            val receiverType = ValueType.Ref(nullable = false, type = interfaceType)
            val firstBlock = blocks.size
            blocks +=
                Block(
                    taskLaunchTrampolineFunctionId,
                    false,
                    listOf(
                        Instruction.CallInterface(
                            Destination.Unit,
                            FunctionRef.Local(requireNotNull(shapeInvokeFunctionIds[unitBlockShape])),
                            listOf(RegisterId.of(0u)),
                        ),
                        Instruction.Return(Destination.Unit),
                    ),
                )
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds["<task-launch>"]),
                    signature = TypeRef.Local(requireNotNull(taskLaunchTrampolineSignatureTypeId)),
                    flags = setOf(FunctionFlag.STATIC),
                    values = listOf(FunctionValue.scalar(receiverType)),
                    parameterCount = 1u,
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = 1u,
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        constructorInstances.forEach { classInstance ->
            val declaration = classInstance.declaration
            val layout = requireNotNull(classLayoutsByInstance[classInstance])
            val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
            val functionId = requireNotNull(constructorFunctionIds[classInstance])
            val receiverType = ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId))
            val parameterTypes =
                constructor.parameters.filter { it.kind == IrParameterKind.Regular }.map { parameter ->
                    valueType(
                        classInstance.substitute(parameter.type),
                        pluginContext,
                        guestTypes,
                        stringType,
                        charArrayType,
                        stringArrayType,
                        classTypeIds,
                        externalClassTypes,
                        inlineValueClasses,
                        platformScalars,
                        parameter,
                        classInstanceTypeIds = classInstanceTypeIds,
                    )
                }
            val firstBlock = blocks.size
            val compiled =
                FunctionCompiler(
                    pluginContext = pluginContext,
                    function = constructor,
                    functionId = functionId,
                    blockBase = firstBlock,
                    stringType = stringType,
                    charArrayType = charArrayType,
                    intArrayType = intArrayType,
                    stringArrayType = stringArrayType,
                    guestTypes = guestTypes,
                    unitType = pluginContext.irBuiltIns.unitType,
                    kotlinStringType = pluginContext.irBuiltIns.stringType,
                    kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                    kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                    intType = pluginContext.irBuiltIns.intType,
                    longType = pluginContext.irBuiltIns.longType,
                    floatType = pluginContext.irBuiltIns.floatType,
                    booleanType = pluginContext.irBuiltIns.booleanType,
                    charType = pluginContext.irBuiltIns.charType,
                    functionIds = functionIds,
                    genericFunctionIds = instanceFunctionIds,
                    genericMemberFunctionIds = genericMemberFunctionIds,
                    currentClassInstance = classInstance,
                    constantIds = constantIds,
                    literalIds = literalIds,
                    session = session,
                    capabilityIds = capabilityIds,
                    classTypeIds = classTypeIds,
                    classInstanceTypeIds = classInstanceTypeIds,
                    externalClassTypes = externalClassTypes,
                    inlineValueClasses = inlineValueClasses,
                    platformScalars = platformScalars,
                    constructorLayouts = constructorTargets,
                    genericConstructorLayouts = genericConstructorTargets,
                    fieldsBySetter = fieldsBySetter,
                    fieldsByGetter = fieldsByGetter,
                    fieldsByBacking = fieldsByBacking,
                    genericFieldsBySetter = genericFieldsBySetter,
                    genericFieldsByGetter = genericFieldsByGetter,
                    genericFieldsByBacking = genericFieldsByBacking,
                    topLevelFieldsByBacking = topLevelFieldsByBacking,
                    topLevelFieldsByGetter = topLevelFieldsByGetter,
                    enumEntries = classLayouts.flatMap { it.enumEntries }.associateBy { it.declaration.symbol },
                    externalFieldsByGetter = externalGetterFieldImports,
                    externalEnumEntries = externalEnumFieldImports,
                    externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                    externalFunctions = externalFunctionImports,
                    functionTypes = shapeInterfaceTypes,
                    invokeFunctionIds = shapeInvokeFunctionIds,
                    taskLaunchTrampolineFunctionId = taskLaunchTrampolineFunctionId,
                    closureLayouts = closureLayoutsByExpression,
                    captureCells = captureCellLayoutsBySymbol,
                    leadingParameterTypes = listOf(receiverType),
                    constructorOwner = layout,
                ).compile()
            blocks += compiled.blocks
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds[constructorName(classInstance)]),
                    signature = TypeRef.Local(requireNotNull(constructorTypeIds[classInstance])),
                    flags = setOf(FunctionFlag.STATIC),
                    values = (listOf(receiverType) + parameterTypes + compiled.localTypes).map(FunctionValue::scalar),
                    parameterCount = (parameterTypes.size + 1).toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        initializerClasses.forEach { declaration ->
            val layout = requireNotNull(classLayoutsBySymbol[declaration.symbol])
            val functionId = requireNotNull(initializerFunctionIds[declaration.symbol])
            val firstBlock = blocks.size
            layout.enumEntries.forEachIndexed { index, enumEntry ->
                val nextBlock = firstBlock + index + 1
                blocks +=
                    Block(
                        owner = functionId,
                        loopHeaderSafepoint = false,
                        instructions =
                            listOf(
                                Instruction.NewObject(RegisterId.of(index.toUInt()), TypeRef.Local(layout.typeId)),
                                Instruction.StaticSet(FieldRef.Local(enumEntry.fieldId), RegisterId.of(index.toUInt())),
                                Instruction.Jump(BlockId.of(nextBlock.toUInt())),
                            ),
                    )
            }
            blocks += Block(functionId, false, listOf(Instruction.Return(Destination.Unit)))
            loweredFunctions +=
                Function(
                    owner = TypeRef.Local(layout.typeId),
                    name = requireNotNull(metadataIds["<clinit>"]),
                    signature = TypeRef.Local(requireNotNull(initializerTypeIds[declaration.symbol])),
                    flags = setOf(FunctionFlag.STATIC),
                    values =
                        List(layout.enumEntries.size) {
                            FunctionValue.scalar(ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId)))
                        },
                    parameterCount = 0u,
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = (layout.enumEntries.size + 1).toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        if (topLevelFields.isNotEmpty()) {
            val stateType = TypeRef.Local(requireNotNull(topLevelStateTypeId))
            val functionId = requireNotNull(topLevelInitializerFunctionId)
            val functionValues = mutableListOf<FunctionValue>()
            val firstBlock = blocks.size
            topLevelFields.forEachIndexed { index, field ->
                val instructions = mutableListOf<Instruction>()
                val valueRegister =
                    when (val initializer = field.property.initializer) {
                        TopLevelInitializer.Null -> {
                            val type = field.type as? ValueType.Ref
                            if (type == null || !type.nullable) {
                                throw UnsupportedKotlinIr(field.property.declaration, "null top-level value requires a nullable reference")
                            }
                            val register = RegisterId.of(functionValues.size.toUInt())
                            functionValues += FunctionValue.scalar(type)
                            instructions += Instruction.Null(register)
                            register
                        }

                        is TopLevelInitializer.Scalar -> {
                            val register = RegisterId.of(functionValues.size.toUInt())
                            val literalType =
                                if (initializer.value is String && field.type is ValueType.Ref) {
                                    field.type.copy(nullable = false)
                                } else {
                                    field.type
                                }
                            functionValues += FunctionValue.scalar(literalType)
                            val constant = initializer.value.toArtifactConstant(literalIds)
                            val constantId =
                                constantIds[constant]
                                    ?: throw UnsupportedKotlinIr(
                                        field.property.declaration,
                                        "top-level scalar initializer is absent from the canonical constant pool",
                                    )
                            instructions += Instruction.Const(register, constantId)
                            register
                        }

                        is TopLevelInitializer.Channel -> {
                            if (field.type != ValueType.I32) {
                                throw UnsupportedKotlinIr(
                                    field.property.declaration,
                                    "IntChannel must use its canonical scalar representation",
                                )
                            }
                            val capacity = RegisterId.of(functionValues.size.toUInt())
                            functionValues += FunctionValue.scalar(ValueType.I32)
                            val handle = RegisterId.of(functionValues.size.toUInt())
                            functionValues += FunctionValue.scalar(ValueType.I32)
                            val constantId =
                                constantIds[Constant.I32(initializer.capacity)]
                                    ?: throw UnsupportedKotlinIr(
                                        field.property.declaration,
                                        "IntChannel capacity is absent from the canonical constant pool",
                                    )
                            instructions += Instruction.Const(capacity, constantId)
                            instructions += Instruction.ChannelCreate(handle, capacity)
                            handle
                        }
                    }
                instructions += Instruction.StaticSet(FieldRef.Local(field.fieldId), valueRegister)
                instructions += Instruction.Jump(BlockId.of((firstBlock + index + 1).toUInt()))
                blocks += Block(functionId, false, instructions)
            }
            blocks += Block(functionId, false, listOf(Instruction.Return(Destination.Unit)))
            loweredFunctions +=
                Function(
                    owner = stateType,
                    name = requireNotNull(metadataIds["<clinit>"]),
                    signature = TypeRef.Local(requireNotNull(topLevelInitializerTypeId)),
                    flags = setOf(FunctionFlag.STATIC),
                    values = functionValues,
                    parameterCount = 0u,
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = (topLevelFields.size + 1).toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        val functionTypes =
            functionInstances.map { instance ->
                val function = instance.declaration
                NominalType.Function(
                    name = requireNotNull(metadataIds[functionArtifactNames[instance]]),
                    suspending = function.isSuspend,
                    result =
                        valueType(
                            function.returnType,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            function,
                            shapeInterfaceTypes,
                            instance,
                            classInstanceTypeIds,
                        ),
                    parameters =
                        loweredParameters(function, session).map {
                            valueType(
                                it.type,
                                pluginContext,
                                guestTypes,
                                stringType,
                                charArrayType,
                                stringArrayType,
                                classTypeIds,
                                externalClassTypes,
                                inlineValueClasses,
                                platformScalars,
                                it,
                                shapeInterfaceTypes,
                                instance,
                                classInstanceTypeIds,
                            )
                        },
                )
            }
        val closureFunctionTypes =
            functionShapes.map { shape ->
                val interfaceType = requireNotNull(shapeInterfaceTypes[shape])
                NominalType.Function(
                    name = requireNotNull(metadataIds["invoke"]),
                    suspending = false,
                    result = shapeValueType(shape.result),
                    parameters =
                        listOf(ValueType.Ref(nullable = false, type = interfaceType)) +
                            shape.parameters.map(shapeValueType),
                )
            } +
                closureLayouts.map { layout ->
                    NominalType.Function(
                        name = requireNotNull(metadataIds["invoke"]),
                        suspending = false,
                        result = shapeValueType(layout.shape.result),
                        parameters =
                            listOf(ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId))) +
                                layout.shape.parameters.map(shapeValueType),
                    )
                } +
                listOfNotNull(
                    shapeInterfaceTypes[unitBlockShape]?.let { interfaceType ->
                        NominalType.Function(
                            name = requireNotNull(metadataIds["<task-launch>"]),
                            suspending = false,
                            result = ValueType.Unit,
                            parameters = listOf(ValueType.Ref(nullable = false, type = interfaceType)),
                        )
                    },
                )
        val constructorTypes =
            constructorInstances.map { classInstance ->
                val declaration = classInstance.declaration
                val layout = requireNotNull(classLayoutsByInstance[classInstance])
                val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
                NominalType.Function(
                    name = requireNotNull(metadataIds[constructorName(classInstance)]),
                    suspending = false,
                    result = ValueType.Unit,
                    parameters =
                        listOf(ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId))) +
                            constructor.parameters.filter { it.kind == IrParameterKind.Regular }.map { parameter ->
                                valueType(
                                    classInstance.substitute(parameter.type),
                                    pluginContext,
                                    guestTypes,
                                    stringType,
                                    charArrayType,
                                    stringArrayType,
                                    classTypeIds,
                                    externalClassTypes,
                                    inlineValueClasses,
                                    platformScalars,
                                    parameter,
                                    classInstanceTypeIds = classInstanceTypeIds,
                                )
                            },
                )
            }
        val initializerTypes =
            initializerClasses.map {
                NominalType.Function(
                    name = requireNotNull(metadataIds["<clinit>"]),
                    suspending = false,
                    result = ValueType.Unit,
                    parameters = emptyList(),
                )
            }
        val topLevelInitializerTypes =
            listOfNotNull(
                topLevelInitializerTypeId?.let {
                    NominalType.Function(
                        name = requireNotNull(metadataIds["<clinit>"]),
                        suspending = false,
                        result = ValueType.Unit,
                        parameters = emptyList(),
                    )
                },
            )
        val classTypes =
            classLayouts.map { layout ->
                val declaration = layout.declaration
                val sourceParents =
                    declaration.superTypes.mapNotNull { superType ->
                        val resolved = layout.instance.substitute(superType)
                        val parentInstance =
                            resolved.classInstance(
                                classInstanceTypeIds.keys.associate {
                                    it.declaration.symbol to
                                        it.declaration
                                },
                            )
                        parentInstance?.let { parent ->
                            classInstanceTypeIds[parent]?.let { parent.declaration.symbol to TypeRef.Local(it) }
                        }
                    }
                val bridgeInterfaceName =
                    when (declaration.fqNameWhenAvailable?.asString()) {
                        "kotlin.collections.IntArrayBackedList" -> "kotlin.collections.List<Any>"
                        "kotlin.collections.IntArrayBackedListIterator" -> "kotlin.collections.Iterator<Any>"
                        "kotlin.collections.ArrayBackedList" -> "kotlin.collections.List<Any>"
                        "kotlin.collections.ArrayBackedListIterator" -> "kotlin.collections.Iterator<Any>"
                        else -> null
                    }
                val bridgeInterface =
                    bridgeInterfaceName
                        ?.let { name -> classInstanceTypeIds.entries.singleOrNull { it.key.name == name }?.value }
                        ?.let(TypeRef::Local)
                val interfaces =
                    (
                        sourceParents.filter { (symbol, _) -> symbol.owner.kind == ClassKind.INTERFACE }.map { it.second } +
                            listOfNotNull(bridgeInterface)
                    ).distinct()
                        .sortedBy { (it as TypeRef.Local).id.value }
                val superType = sourceParents.firstOrNull { (symbol, _) -> symbol.owner.kind != ClassKind.INTERFACE }?.second
                val name = layout.instance.name
                if (declaration.kind == ClassKind.INTERFACE) {
                    val methods = memberFunctionsByOwner[declaration.symbol].orEmpty()
                    val genericMethods = genericMemberFunctionsByOwner[layout.instance].orEmpty()
                    NominalType.Interface(
                        name = requireNotNull(metadataIds[name]),
                        sealed = declaration.modality == Modality.SEALED,
                        superType = superType,
                        interfaces = interfaces,
                        methodStart =
                            genericMethods.firstOrNull()?.let { requireNotNull(instanceFunctionIds[it]).value }
                                ?: methods.firstOrNull()?.let { requireNotNull(functionIds[it.symbol]).value }
                                ?: 0u,
                        methodCount = (methods.size + genericMethods.size).toUInt(),
                    )
                } else {
                    val methods = memberFunctionsByOwner[declaration.symbol].orEmpty()
                    val genericMethods = genericMemberFunctionsByOwner[layout.instance].orEmpty()
                    NominalType.Class(
                        name = requireNotNull(metadataIds[name]),
                        abstract = declaration.modality == Modality.ABSTRACT || declaration.modality == Modality.SEALED,
                        final = declaration.modality == Modality.FINAL,
                        superType = superType ?: TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)),
                        interfaces = interfaces,
                        fieldStart = layout.firstField,
                        fieldCount = (layout.fields.size + layout.enumEntries.size).toUInt(),
                        methodStart =
                            genericMethods.firstOrNull()?.let { requireNotNull(instanceFunctionIds[it]).value }
                                ?: methods.firstOrNull()?.let { requireNotNull(functionIds[it.symbol]).value }
                                ?: 0u,
                        methodCount = (methods.size + genericMethods.size).toUInt(),
                        initializer = initializerFunctionIds[declaration.symbol],
                    )
                }
            }
        val closureClassTypes =
            functionShapes.mapIndexed { index, shape ->
                NominalType.Interface(
                    name = requireNotNull(metadataIds[functionShapeName(index, shape, unitBlockShape)]),
                    methodStart = requireNotNull(shapeInvokeFunctionIds[shape]).value,
                    methodCount = 1u,
                )
            } +
                closureLayouts.map { layout ->
                    NominalType.Class(
                        name = requireNotNull(metadataIds[layout.name]),
                        final = true,
                        interfaces = listOf(requireNotNull(shapeInterfaceTypes[layout.shape])),
                        fieldStart =
                            layout.captures
                                .firstOrNull()
                                ?.fieldId
                                ?.value ?: 0u,
                        fieldCount = layout.captures.size.toUInt(),
                        methodStart = layout.invokeFunctionId.value,
                        methodCount = 1u,
                    )
                }
        val captureCellClassTypes =
            captureCellLayouts.map { cell ->
                NominalType.Class(
                    name = requireNotNull(metadataIds[captureCellName(cell.ordinal)]),
                    final = true,
                    fieldStart = cell.fieldId.value,
                    fieldCount = 1u,
                )
            }
        val topLevelStateTypes =
            listOfNotNull(
                topLevelStateTypeId?.let { stateType ->
                    NominalType.Class(
                        name = requireNotNull(metadataIds[APPLICATION_STATE]),
                        final = true,
                        fieldStart = firstTopLevelField.toUInt(),
                        fieldCount = topLevelFields.size.toUInt(),
                        initializer = topLevelInitializerFunctionId,
                    )
                },
            )
        val artifactFields =
            classLayouts.flatMap { layout ->
                val owner = TypeRef.Local(layout.typeId)
                layout.fields.map { field ->
                    Field(
                        owner = owner,
                        name = requireNotNull(metadataIds[field.property.name.asString()]),
                        type = field.type,
                        mutable = true,
                        static = false,
                    )
                } +
                    layout.enumEntries.map { enumEntry ->
                        Field(
                            owner = owner,
                            name = requireNotNull(metadataIds[enumEntry.declaration.name.asString()]),
                            type = ValueType.Ref(nullable = false, type = owner),
                            mutable = true,
                            static = true,
                        )
                    }
            } +
                captureCellLayouts.map { cell ->
                    Field(
                        owner = TypeRef.Local(cell.typeId),
                        name = requireNotNull(metadataIds["<value>"]),
                        type = cell.valueType,
                        mutable = true,
                        static = false,
                    )
                } +
                closureLayouts.flatMap { layout ->
                    val owner = TypeRef.Local(layout.typeId)
                    layout.captures.mapIndexed { index, capture ->
                        Field(
                            owner = owner,
                            name = requireNotNull(metadataIds[closureCaptureName(index)]),
                            type = capture.type,
                            mutable = true,
                            static = false,
                        )
                    }
                } +
                topLevelFields.map { field ->
                    Field(
                        owner = TypeRef.Local(requireNotNull(topLevelStateTypeId)),
                        name =
                            requireNotNull(
                                metadataIds[
                                    field.property.declaration.name
                                        .asString(),
                                ],
                            ),
                        type = field.type,
                        mutable = true,
                        static = true,
                    )
                }
        val externalFunctionTypes =
            externalFunctionImports.entries
                .sortedBy { (_, target) -> target.sortKey }
                .map { (symbol, target) ->
                    val function = symbol.owner
                    NominalType.Function(
                        name = requireNotNull(metadataIds[target.exportName]),
                        suspending = function.isSuspend,
                        result =
                            valueType(
                                function.returnType,
                                pluginContext,
                                guestTypes,
                                stringType,
                                charArrayType,
                                stringArrayType,
                                classTypeIds,
                                externalClassTypes,
                                inlineValueClasses,
                                platformScalars,
                                function,
                            ),
                        parameters =
                            loweredParameters(function, session).map { parameter ->
                                valueType(
                                    parameter.type,
                                    pluginContext,
                                    guestTypes,
                                    stringType,
                                    charArrayType,
                                    stringArrayType,
                                    classTypeIds,
                                    externalClassTypes,
                                    inlineValueClasses,
                                    platformScalars,
                                    parameter,
                                )
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
                        closureFunctionTypes +
                        constructorTypes +
                        classTypes +
                        closureClassTypes +
                        captureCellClassTypes +
                        topLevelStateTypes +
                        if (usesStringArray) {
                            listOf(
                                NominalType.Array(
                                    name = requireNotNull(metadataIds["kotlin.Array"]),
                                    element = stringType,
                                ),
                            )
                        } else {
                            emptyList()
                        } +
                        referenceArrays.map { (name, element) ->
                            NominalType.Array(
                                name = requireNotNull(metadataIds[name]),
                                element =
                                    ValueType.Ref(
                                        nullable = false,
                                        type =
                                            when (element) {
                                                ReferenceArrayElement.Universal -> {
                                                    TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))
                                                }

                                                is ReferenceArrayElement.GuestClass -> {
                                                    TypeRef.Local(requireNotNull(classInstanceTypeIds[element.instance]))
                                                }
                                            },
                                    ),
                            )
                        } + initializerTypes + topLevelInitializerTypes + externalFunctionTypes,
                constants = constants,
                fields = artifactFields,
                imports =
                    runtimeTypeNames.indices.map { index ->
                        runtimeTypeImport(index, requireNotNull(metadataIds[runtimeTypeNames[index]]), libraryHash)
                    } +
                        Import(
                            kind = SymbolKind.FIELD,
                            targetModule = ModuleId.of(1u),
                            targetName = requireNotNull(metadataIds[INT_BOX_VALUE_NAME]),
                            expectedSignature = TypeRef.Imported(ImportId.of(INT_BOX_RUNTIME_TYPE)),
                            targetModuleHash = libraryHash,
                        ) +
                        externalTypeImports.entries
                            .sortedBy { (_, target) -> target.sortKey }
                            .mapIndexed { index, (_, target) ->
                                Import(
                                    kind = SymbolKind.TYPE,
                                    targetModule = ModuleId.of((2 + index).toUInt()),
                                    targetName = requireNotNull(metadataIds[target.exportName]),
                                    expectedSignature = TypeRef.Imported(target.importId),
                                    targetModuleHash = target.moduleHash,
                                )
                            } +
                        externalFieldImports.mapIndexed { index, target ->
                            Import(
                                kind = SymbolKind.FIELD,
                                targetModule = ModuleId.of((2 + externalTypeImports.size + index).toUInt()),
                                targetName = requireNotNull(metadataIds[target.exportName]),
                                expectedSignature = requireNotNull(externalClassTypes[target.ownerSymbol]),
                                targetModuleHash = target.moduleHash,
                            )
                        } +
                        externalFunctionImports.entries
                            .sortedBy { (_, target) -> target.sortKey }
                            .mapIndexed { index, (_, target) ->
                                Import(
                                    kind = SymbolKind.FUNCTION,
                                    targetModule = ModuleId.of((2 + externalTypeImports.size + externalFieldImportCount + index).toUInt()),
                                    targetName = requireNotNull(metadataIds[target.exportName]),
                                    expectedSignature = TypeRef.Local(TypeId.of((externalFunctionTypeBase + index).toUInt())),
                                    targetModuleHash = target.moduleHash,
                                )
                            },
                functions = loweredFunctions,
                blocks = blocks,
            )
        val modules = listOf(app, library)
        val maximumCallDepth = 16u
        val usesTasks = blocks.any { block -> block.instructions.any { it is Instruction.TaskSpawn || it is Instruction.TaskJoin } }
        val usesChannels =
            blocks.any { block ->
                block.instructions.any {
                    it is Instruction.ChannelCreate || it is Instruction.ChannelSend || it is Instruction.ChannelReceive
                }
            }
        val usesI64StringConversion =
            blocks.any { block ->
                block.instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.I64 }
            }
        val usesF32StringConversion =
            blocks.any { block ->
                block.instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.F32 }
            }
        val maximumChannels = topLevelProperties.count { it.initializer is TopLevelInitializer.Channel }.toUInt()
        val channelValueCount =
            topLevelProperties.fold(0uL) { total, property ->
                total + ((property.initializer as? TopLevelInitializer.Channel)?.capacity?.toULong() ?: 0uL)
            }
        require(channelValueCount <= UInt.MAX_VALUE.toULong()) { "total IntChannel capacity exceeds u32" }
        val maximumChannelValues = channelValueCount.toUInt()
        val maximumCoroutines = if (usesTasks) MAXIMUM_TASKS else 1u
        val singleTaskStackBytes = ExecutionStorage.requiredStackBytes(modules, maximumCallDepth)
        require(singleTaskStackBytes <= UInt.MAX_VALUE / maximumCoroutines) {
            "required task frame storage exceeds u32"
        }
        val requiredStackBytes = singleTaskStackBytes * maximumCoroutines
        return Artifact(
            minimumRuntimeAbi =
                when {
                    usesF32StringConversion -> AbiVersion(1u, 4u)
                    usesI64StringConversion -> AbiVersion(1u, 3u)
                    usesChannels -> AbiVersion(1u, 2u)
                    usesTasks -> AbiVersion(1u, 1u)
                    else -> AbiVersion(1u, 0u)
                },
            semanticFeatures =
                setOfNotNull(
                    SemanticFeature.COROUTINES.takeIf { userFunctions.any { it.isSuspend } },
                    SemanticFeature.CAPABILITIES.takeIf { capabilityIdentities.isNotEmpty() },
                    SemanticFeature.CHANNELS.takeIf { usesChannels },
                    SemanticFeature.MODULE_IMPORTS,
                ),
            manifest =
                Manifest(
                    requiredHeapBytes = 64u * 1024u,
                    requiredStackBytes = requiredStackBytes,
                    maximumCoroutines = maximumCoroutines,
                    maximumCallDepth = maximumCallDepth,
                    maximumHostRequests = 64u,
                    maximumEvents = 0u,
                    maximumBlockCost = 64u,
                    minimumSliceCost = 64u,
                    compilerAbi = ByteArray(32),
                    platformAbi = ByteArray(32),
                    maximumChannels = maximumChannels,
                    maximumChannelValues = maximumChannelValues,
                ),
            entry =
                EntryPoint(
                    ModuleId.of(0u),
                    requireNotNull(functionIds[entry.symbol]),
                    if (entry.parameters.isEmpty()) EntryArguments.NONE else EntryArguments.STRING_ARRAY,
                ),
            modules = modules,
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

    private fun topLevelProperty(property: IrProperty): TopLevelProperty {
        if (property.isVar ||
            property.getter == null ||
            property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR ||
            property.backingField == null
        ) {
            throw UnsupportedKotlinIr(property, "top-level state must be an immutable property with a default getter")
        }
        val expression =
            requireNotNull(property.backingField).initializer?.expression
                ?: throw UnsupportedKotlinIr(property, "top-level val requires a direct initializer")
        val initializer =
            if (expression is IrConstructorCall &&
                expression.symbol.owner.parentAsClass.fqNameWhenAvailable
                    ?.asString() == INT_CHANNEL
            ) {
                val argument =
                    expression.symbol.owner.parameters
                        .mapIndexedNotNull { index, parameter ->
                            expression.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                        }.singleOrNull() as? IrConst
                        ?: throw UnsupportedKotlinIr(expression, "IntChannel capacity must be a positive Int constant")
                val capacity = argument.value as? Int
                if (capacity == null || capacity <= 0) {
                    throw UnsupportedKotlinIr(expression, "IntChannel capacity must be a positive Int constant")
                }
                TopLevelInitializer.Channel(capacity)
            } else if (expression is IrConst && expression.value == null) {
                TopLevelInitializer.Null
            } else {
                val value = (expression as? IrConst)?.value
                if (value !is Int && value !is Long && value !is Float && value !is Boolean && value !is Char && value !is String) {
                    throw UnsupportedKotlinIr(
                        expression,
                        "top-level val initializer must be a scalar literal or direct IntChannel construction",
                    )
                }
                TopLevelInitializer.Scalar(requireNotNull(value))
            }
        return TopLevelProperty(property, initializer)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun artifactFunctionName(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
        inlineValueClasses: InlineValueClassRegistry,
        session: CompilationSession,
    ): String {
        val signatureTypes = loweredParameters(function, session).map { it.type } + function.returnType
        if (signatureTypes.none { type ->
                inlineValueClasses.contains((type as? IrSimpleType)?.classifier as? IrClassSymbol)
            }
        ) {
            return function.name.asString()
        }
        val parameters =
            loweredParameters(function, session).joinToString(",") { parameter ->
                sourceTypeName(parameter.type, pluginContext, inlineValueClasses)
            }
        val result = sourceTypeName(function.returnType, pluginContext, inlineValueClasses)
        return "${function.name.asString()}#($parameters)->$result"
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun sourceTypeName(
        type: IrType,
        pluginContext: IrPluginContext,
        inlineValueClasses: InlineValueClassRegistry,
    ): String =
        when (type) {
            pluginContext.irBuiltIns.unitType -> {
                "kotlin.Unit"
            }

            pluginContext.irBuiltIns.intType -> {
                "kotlin.Int"
            }

            pluginContext.irBuiltIns.longType -> {
                "kotlin.Long"
            }

            pluginContext.irBuiltIns.floatType -> {
                "kotlin.Float"
            }

            pluginContext.irBuiltIns.booleanType -> {
                "kotlin.Boolean"
            }

            pluginContext.irBuiltIns.charType -> {
                "kotlin.Char"
            }

            pluginContext.irBuiltIns.stringType -> {
                "kotlin.String"
            }

            else -> {
                val classifier = requireNotNull((type as? IrSimpleType)?.classifier as? IrClassSymbol)
                inlineValueClasses[classifier]
                    ?.declaration
                    ?.name
                    ?.asString()
                    ?: classifier.owner.fqNameWhenAvailable?.asString()
                    ?: classifier.owner.name.asString()
            }
        }

    private fun validateFunction(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        classTypeIds: Map<IrClassSymbol, TypeId>,
        classInstanceTypeIds: Map<GuestClassInstance, TypeId>,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        session: CompilationSession,
        functionTypes: Map<GuestFunctionShape, TypeRef.Local>,
        instance: GuestFunctionInstance,
    ) {
        val owner = function.parent as? IrClass
        if (owner != null && !inlineValueClasses.contains(owner.symbol)) {
            if (function.isSuspend) {
                throw UnsupportedKotlinIr(function, "suspending instance methods are not supported")
            }
            if (function.typeParameters.isNotEmpty()) {
                throw UnsupportedKotlinIr(function, "generic instance methods are not supported")
            }
            if (function.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }) {
                throw UnsupportedKotlinIr(function, "member extension functions are not supported")
            }
        }
        val supported =
            setOf(
                pluginContext.irBuiltIns.unitType,
                pluginContext.irBuiltIns.stringType,
                pluginContext.irBuiltIns.intType,
                pluginContext.irBuiltIns.longType,
                pluginContext.irBuiltIns.floatType,
                pluginContext.irBuiltIns.booleanType,
                pluginContext.irBuiltIns.charType,
                pluginContext.irBuiltIns.anyType,
            )

        fun isSupported(sourceType: IrType): Boolean {
            val type = instance.substitute(sourceType)
            val stringClass = (pluginContext.irBuiltIns.stringType as IrSimpleType).classifier
            if (type.isNullable()) {
                return type.isKotlinAny() || (type as? IrSimpleType)?.classifier == stringClass ||
                    classTypeIds.containsKey((type as? IrSimpleType)?.classifier) ||
                    type.classInstance(
                        classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration },
                    ) in classInstanceTypeIds
            }
            return type in supported ||
                type.isNothing() ||
                type.isExactClass(pluginContext.irBuiltIns.charArray) ||
                type.isExactClass(pluginContext.irBuiltIns.intArray) ||
                guestTypes.isStringArray(type) ||
                guestTypes.referenceArrayType(type) != null ||
                (
                    !type.isNullable() &&
                        inlineValueClasses.contains((type as? IrSimpleType)?.classifier as? IrClassSymbol)
                ) ||
                (!type.isNullable() && platformScalars.representation(type) != null) ||
                (functionTypes.forType(type) != null) ||
                classTypeIds.containsKey((type as? IrSimpleType)?.classifier) ||
                type.classInstance(
                    classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration },
                ) in classInstanceTypeIds ||
                externalClassTypes.containsKey((type as? IrSimpleType)?.classifier)
        }
        if (loweredParameters(function, session).any { !isSupported(it.type) } ||
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
        classTypeIds: Map<IrClassSymbol, TypeId>,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        element: IrElement,
        functionTypes: Map<GuestFunctionShape, TypeRef.Local> = emptyMap(),
        instance: GuestFunctionInstance? = null,
        classInstanceTypeIds: Map<GuestClassInstance, TypeId> = emptyMap(),
    ): ValueType =
        mapGuestValueType(
            type,
            pluginContext,
            guestTypes,
            stringType,
            charArrayType,
            stringArrayType,
            classTypeIds,
            externalClassTypes,
            inlineValueClasses,
            platformScalars,
            element,
            functionTypes,
            instance,
            classInstanceTypeIds,
        )

    private fun buildClassLayouts(
        classes: List<GuestClassInstance>,
        classTypeIds: Map<IrClassSymbol, TypeId>,
        classInstanceTypeIds: Map<GuestClassInstance, TypeId>,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        stringType: ValueType,
        charArrayType: ValueType,
        stringArrayType: ValueType,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
    ): List<GuestClassLayout> {
        var nextField = 0u
        return classes.map { instance ->
            val declaration = instance.declaration
            if (declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.ENUM_CLASS) ||
                (
                    declaration.typeParameters.isNotEmpty() &&
                        (
                            declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE) ||
                                declaration.isData ||
                                (declaration.kind == ClassKind.CLASS && declaration.modality != Modality.FINAL) ||
                                (
                                    declaration.kind == ClassKind.CLASS &&
                                        declaration.superTypes.any { superType ->
                                            val parent = (superType as? IrSimpleType)?.classifier as? IrClassSymbol
                                            parent != pluginContext.irBuiltIns.anyClass && parent?.owner?.kind != ClassKind.INTERFACE
                                        }
                                )
                        )
                )
            ) {
                throw UnsupportedKotlinIr(
                    declaration,
                    "class ${declaration.fqNameWhenAvailable?.asString() ?: declaration.name} (${declaration.kind}) is outside the project subset",
                )
            }
            val typeId = requireNotNull(classInstanceTypeIds[instance])
            val firstField = nextField
            val constructor = declaration.constructors.singleOrNull { it.isPrimary }
            if (declaration.constructors.any { !it.isPrimary }) {
                throw UnsupportedKotlinIr(declaration, "secondary constructors are not supported")
            }
            val parameters = constructor?.parameters?.filter { it.kind == IrParameterKind.Regular }.orEmpty()
            val declaredProperties = declaration.declarations.filterIsInstance<IrProperty>()
            if (declaredProperties.any { property ->
                    property.backingField == null &&
                        property.origin == IrDeclarationOrigin.DEFINED &&
                        (
                            (property.getter?.body == null && property.getter?.modality != Modality.ABSTRACT) ||
                                (
                                    property.isVar && property.setter?.body == null &&
                                        property.setter?.modality != Modality.ABSTRACT
                                )
                        )
                }
            ) {
                throw UnsupportedKotlinIr(declaration, "property accessor body is missing")
            }
            val properties = declaredProperties.filter { it.backingField != null }
            if (declaration.kind == ClassKind.ENUM_CLASS && (parameters.isNotEmpty() || properties.isNotEmpty())) {
                throw UnsupportedKotlinIr(declaration, "enum constructor state is not supported")
            }
            val fields =
                properties.map { property ->
                    val parameterIndex = parameters.indexOfFirst { it.name == property.name }
                    if (parameterIndex < 0 && property.backingField?.initializer == null) {
                        throw UnsupportedKotlinIr(property, "body property requires an initializer")
                    }
                    val fieldType =
                        valueType(
                            instance.substitute(requireNotNull(property.backingField).type),
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            property,
                            classInstanceTypeIds = classInstanceTypeIds,
                        )
                    GuestFieldLayout(property, FieldId.of(nextField++), fieldType)
                }
            val owner = TypeRef.Local(typeId)
            val entries =
                declaration.declarations.filterIsInstance<IrEnumEntry>().map { enumEntry ->
                    GuestEnumEntryLayout(enumEntry, FieldId.of(nextField++), owner)
                }
            GuestClassLayout(instance, typeId, firstField, fields, entries)
        }
    }

    private fun kotlinLibrary(): Module {
        val names = (runtimeTypeNames + INT_BOX_VALUE_NAME).sorted()
        val ids = names.withIndex().associate { (index, name) -> name to StringId.of(index.toUInt()) }
        val anyType = TypeRef.Local(TypeId.of(ANY_RUNTIME_TYPE))
        return Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings = names.map(MetadataText::of),
            types =
                listOf(
                    NominalType.Array(name = requireNotNull(ids["kotlin.CharArray"]), element = ValueType.Char),
                    NominalType.Class(name = requireNotNull(ids["kotlin.String"]), final = true, superType = anyType),
                    NominalType.Class(name = requireNotNull(ids["kotlin.Throwable"]), superType = anyType),
                    NominalType.Class(
                        name = requireNotNull(ids["runtime.IllegalArgumentException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(2u)),
                    ),
                    NominalType.Array(name = requireNotNull(ids["kotlin.IntArray"]), element = ValueType.I32),
                    NominalType.Class(name = requireNotNull(ids["kotlin.Any"])),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.Int"]),
                        final = true,
                        superType = anyType,
                        fieldStart = 0u,
                        fieldCount = 1u,
                    ),
                ),
            fields =
                listOf(
                    Field(
                        owner = TypeRef.Local(TypeId.of(INT_BOX_RUNTIME_TYPE)),
                        name = requireNotNull(ids[INT_BOX_VALUE_NAME]),
                        type = ValueType.I32,
                        mutable = true,
                        static = false,
                    ),
                ),
            exports =
                runtimeTypeNames.mapIndexed { index, name ->
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = requireNotNull(ids[name]),
                        localSymbol = index.toUInt(),
                        signature = TypeRef.Local(TypeId.of(index.toUInt())),
                    )
                } +
                    Export(
                        kind = SymbolKind.FIELD,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = requireNotNull(ids[INT_BOX_VALUE_NAME]),
                        localSymbol = 0u,
                        signature = TypeRef.Local(TypeId.of(INT_BOX_RUNTIME_TYPE)),
                    ),
        )
    }

    private fun runtimeTypeImport(
        index: Int,
        targetName: StringId,
        libraryHash: ByteArray,
    ): Import {
        val id = index.toUInt()
        return Import(
            kind = SymbolKind.TYPE,
            targetModule = ModuleId.of(1u),
            targetName = targetName,
            expectedSignature = TypeRef.Imported(ImportId.of(id)),
            targetModuleHash = libraryHash,
        )
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun collectGuestClasses(
    sourceClasses: List<IrClass>,
    functions: List<IrSimpleFunction>,
): List<IrClass> {
    val sourceSymbols = sourceClasses.mapTo(mutableSetOf()) { it.symbol }
    val collected = linkedMapOf<IrClassSymbol, IrClass>()
    val pending = ArrayDeque<IrClass>()

    fun consider(declaration: IrClass) {
        val source = declaration.symbol in sourceSymbols
        if (source) {
            if (collected.putIfAbsent(declaration.symbol, declaration) == null) pending.addLast(declaration)
        }
    }

    fun consider(type: IrType) {
        ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.let(::consider)
    }

    sourceClasses.forEach(::consider)
    val references =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitExpression(expression: IrExpression) {
                consider(expression.type)
                super.visitExpression(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                consider(expression.typeOperand)
                super.visitTypeOperator(expression)
            }
        }
    functions.forEach { function ->
        consider(function.returnType)
        function.parameters.forEach { consider(it.type) }
        function.accept(references, null)
    }

    while (pending.isNotEmpty()) {
        val declaration = pending.removeFirst()
        declaration.superTypes.forEach(::consider)
        declaration.declarations.filterIsInstance<IrProperty>().forEach { property ->
            property.backingField?.type?.let(::consider)
        }
    }
    return collected.values.sortedBy { it.fqNameWhenAvailable?.asString().orEmpty() }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun constructorName(instance: GuestClassInstance): String = "<init:${instance.name}>"

private fun closureName(ordinal: Int): String = "app.<lambda-$ordinal>"

private fun closureCaptureName(ordinal: Int): String = "<capture-$ordinal>"

private fun captureCellName(ordinal: Int): String = "app.<capture-cell-$ordinal>"

private data class CompiledFunction(
    val localTypes: List<ValueType>,
    val blocks: List<Block>,
)

private sealed interface ResolvedCallArgument {
    data class Expression(
        val expression: IrExpression,
    ) : ResolvedCallArgument

    data class PlatformDefault(
        val value: PlatformDefaultArgument,
    ) : ResolvedCallArgument
}

private data class ExternalFunctionTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class ExternalTypeTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class ExternalFieldTarget(
    val exportName: String,
    val ownerSymbol: IrClassSymbol,
    val moduleHash: ByteArray,
    val static: Boolean,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class LinkedPlatformSymbols(
    val types: Map<IrClassSymbol, ExternalTypeTarget>,
    val fieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    val enumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    val defaultEnumEntries: Map<String, ExternalFieldTarget>,
    val defaultIntValues: Set<Int>,
)

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun linkedPlatformSymbols(
    elements: List<IrElement>,
    session: CompilationSession,
): LinkedPlatformSymbols {
    val typeLinks = session.platformTypes.associateBy { it.symbol }
    val fieldLinks = session.platformFields.associateBy { it.symbol }
    val types = linkedMapOf<IrClassSymbol, ExternalTypeTarget>()
    val fieldsByGetter = linkedMapOf<IrSimpleFunctionSymbol, ExternalFieldTarget>()
    val enumEntries = linkedMapOf<IrEnumEntrySymbol, ExternalFieldTarget>()
    val classSymbols = linkedMapOf<String, IrClassSymbol>()
    val neededDefaultArguments = mutableListOf<PlatformDefaultArgument>()

    fun considerTypeSymbol(symbol: IrClassSymbol) {
        val fqName = symbol.owner.fqNameWhenAvailable?.asString() ?: return
        classSymbols[fqName] = symbol
        if (fqName == "kotlin.IntArray") return
        val link = typeLinks[fqName] ?: return
        types[symbol] = ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
    }

    fun considerType(type: IrType) {
        val symbol = (type as? IrSimpleType)?.classifier as? IrClassSymbol ?: return
        considerTypeSymbol(symbol)
    }

    fun fieldTarget(
        symbol: String,
        owner: IrClassSymbol,
    ): ExternalFieldTarget? {
        val link = fieldLinks[symbol] ?: return null
        considerTypeSymbol(owner)
        return ExternalFieldTarget(link.exportName, owner, link.moduleHash.copyOf(), link.static)
    }

    val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrExpression) considerType(element.type)
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                considerType(expression.type)
                val target = expression.symbol.owner
                considerType(target.returnType)
                target.parameters.forEach { considerType(it.type) }
                val targetSymbol = target.fqNameWhenAvailable?.asString()
                val targetSignature = target.canonicalPlatformSignature()
                session.platformFunctions
                    .singleOrNull { link -> link.symbol == targetSymbol && link.signature == targetSignature }
                    ?.defaultArguments
                    ?.filterNotNull()
                    ?.let(neededDefaultArguments::addAll)
                val property = target.correspondingPropertySymbol?.owner ?: target.parent as? IrProperty
                val owner = property?.parent as? IrClass
                val fieldSymbol = property?.fqNameWhenAvailable?.asString()
                if (owner != null && fieldSymbol != null) {
                    fieldTarget(fieldSymbol, owner.symbol)?.let { field ->
                        if (field.static) throw UnsupportedKotlinIr(expression, "platform property getter resolves to a static field")
                        fieldsByGetter[target.symbol] = field
                    }
                }
                super.visitCall(expression)
            }

            override fun visitGetEnumValue(expression: IrGetEnumValue) {
                considerType(expression.type)
                val entry = expression.symbol.owner
                val owner = entry.parent as? IrClass
                val fieldSymbol = entry.fqNameWhenAvailable?.asString()
                if (owner != null && fieldSymbol != null) {
                    fieldTarget(fieldSymbol, owner.symbol)?.let { field ->
                        if (!field.static) throw UnsupportedKotlinIr(expression, "platform enum entry resolves to an instance field")
                        enumEntries[expression.symbol] = field
                    }
                }
                super.visitGetEnumValue(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                considerType(expression.typeOperand)
                considerType(expression.type)
                super.visitTypeOperator(expression)
            }
        }
    elements.forEach { element ->
        if (element is IrFunction) {
            considerType(element.returnType)
            element.parameters.forEach { considerType(it.type) }
        }
        element.accept(visitor, null)
    }
    val defaultEnumEntries = linkedMapOf<String, ExternalFieldTarget>()
    neededDefaultArguments
        .filterIsInstance<PlatformDefaultArgument.EnumEntry>()
        .forEach { argument ->
            val owner =
                classSymbols[argument.symbol.substringBeforeLast('.')]
                    ?: throw IllegalArgumentException("platform enum default owner is unavailable: ${argument.symbol}")
            val field =
                fieldTarget(argument.symbol, owner)
                    ?: throw IllegalArgumentException("platform enum default entry is unavailable: ${argument.symbol}")
            require(field.static) { "platform enum default entry is not static: ${argument.symbol}" }
            defaultEnumEntries[argument.symbol] = field
        }
    val defaultIntValues =
        neededDefaultArguments
            .filterIsInstance<PlatformDefaultArgument.IntValue>()
            .mapTo(linkedSetOf(), PlatformDefaultArgument.IntValue::value)
    return LinkedPlatformSymbols(types, fieldsByGetter, enumEntries, defaultEnumEntries, defaultIntValues)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun linkedPlatformFunctions(
    elements: List<IrElement>,
    session: CompilationSession,
): Map<IrSimpleFunctionSymbol, ExternalFunctionTarget> {
    val result = linkedMapOf<IrSimpleFunctionSymbol, ExternalFunctionTarget>()
    val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                val target = expression.symbol.owner
                val symbol = target.fqNameWhenAvailable?.asString()
                if (symbol != null) {
                    val signature = target.canonicalPlatformSignature()
                    session.platformFunctions
                        .singleOrNull { link -> link.symbol == symbol && link.signature == signature }
                        ?.let { link ->
                            result[target.symbol] = ExternalFunctionTarget(link.exportName, link.moduleHash.copyOf())
                        }
                }
                super.visitCall(expression)
            }
        }
    elements.forEach { it.accept(visitor, null) }
    return result
}

private class FunctionCompiler(
    private val pluginContext: IrPluginContext,
    private val function: IrFunction,
    private val functionId: FunctionId,
    private val blockBase: Int,
    private val stringType: ValueType,
    private val charArrayType: ValueType,
    private val intArrayType: ValueType,
    private val stringArrayType: ValueType,
    private val guestTypes: GuestTypeRegistry,
    private val unitType: IrType,
    private val kotlinStringType: IrType,
    private val kotlinCharArrayClass: IrClassSymbol,
    private val kotlinIntArrayClass: IrClassSymbol,
    private val intType: IrType,
    private val longType: IrType,
    private val floatType: IrType,
    private val booleanType: IrType,
    private val charType: IrType,
    private val functionIds: Map<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, FunctionId>,
    private val genericFunctionIds: Map<GuestFunctionInstance, FunctionId> = emptyMap(),
    private val genericMemberFunctionIds: Map<Pair<IrSimpleFunctionSymbol, GuestClassInstance>, FunctionId> = emptyMap(),
    private val currentInstance: GuestFunctionInstance? = null,
    private val currentClassInstance: GuestClassInstance? = null,
    private val constantIds: Map<Constant, ConstantId>,
    private val literalIds: Map<Utf16Literal, Utf16LiteralId>,
    private val session: CompilationSession,
    private val capabilityIds: Map<LoweredCapabilityIdentity, CapabilityId>,
    private val classTypeIds: Map<IrClassSymbol, TypeId>,
    private val classInstanceTypeIds: Map<GuestClassInstance, TypeId> = emptyMap(),
    private val externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
    private val inlineValueClasses: InlineValueClassRegistry,
    private val platformScalars: PlatformScalarRegistry,
    private val constructorLayouts: Map<IrConstructorSymbol, GuestConstructorTarget>,
    private val genericConstructorLayouts: Map<GuestClassInstance, GuestConstructorTarget> = emptyMap(),
    private val fieldsBySetter: Map<IrSimpleFunctionSymbol, GuestFieldLayout>,
    private val fieldsByGetter: Map<IrSimpleFunctionSymbol, GuestFieldLayout>,
    private val fieldsByBacking: Map<IrFieldSymbol, GuestFieldLayout>,
    private val genericFieldsBySetter: Map<Pair<IrSimpleFunctionSymbol, GuestClassInstance>, GuestFieldLayout> = emptyMap(),
    private val genericFieldsByGetter: Map<Pair<IrSimpleFunctionSymbol, GuestClassInstance>, GuestFieldLayout> = emptyMap(),
    private val genericFieldsByBacking: Map<Pair<IrFieldSymbol, GuestClassInstance>, GuestFieldLayout> = emptyMap(),
    private val topLevelFieldsByBacking: Map<IrFieldSymbol, TopLevelFieldLayout>,
    private val topLevelFieldsByGetter: Map<IrSimpleFunctionSymbol, TopLevelFieldLayout>,
    private val enumEntries: Map<IrEnumEntrySymbol, GuestEnumEntryLayout>,
    private val externalFieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    private val externalEnumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    private val externalDefaultEnumEntries: Map<String, ExternalFieldTarget>,
    private val externalFunctions: Map<IrSimpleFunctionSymbol, ExternalFunctionTarget>,
    private val functionTypes: Map<GuestFunctionShape, TypeRef.Local>,
    private val invokeFunctionIds: Map<GuestFunctionShape, FunctionId>,
    private val taskLaunchTrampolineFunctionId: FunctionId?,
    private val closureLayouts: Map<IrExpression, GuestClosureLayout>,
    private val captureCells: Map<IrValueSymbol, GuestCaptureCellLayout>,
    private val leadingParameterTypes: List<ValueType> = emptyList(),
    private val captureFields: Map<IrValueSymbol, GuestClosureCapture> = emptyMap(),
    private val closureReceiver: RegisterId? = null,
    private val constructorOwner: GuestClassLayout? = null,
) {
    private val localTypes = mutableListOf<ValueType>()
    private val values = mutableMapOf<IrValueSymbol, RegisterId>()
    private val blocks = mutableListOf(MutableBlock())
    private val loopContexts = ArrayDeque<LoopContext>()
    private var currentBlock = 0
    private val sourceParameters =
        when (function) {
            is IrSimpleFunction -> loweredParameters(function, session)
            is IrConstructor -> function.parameters.filter { it.kind == IrParameterKind.Regular }
            else -> throw UnsupportedKotlinIr(function, "unsupported function declaration")
        }

    fun compile(): CompiledFunction {
        sourceParameters.forEachIndexed { index, parameter ->
            values[parameter.symbol] = RegisterId.of((leadingParameterTypes.size + index).toUInt())
        }
        constructorOwner?.declaration?.thisReceiver?.let { receiver ->
            values[receiver.symbol] = RegisterId.of(0u)
        }
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
                rejectFunctionVariance(initializer.type, statement.type, statement)
                val source = coerceLocalValue(compileExpression(initializer, statement.type), statement.type, statement)
                val cell = captureCells[statement.symbol]
                if (cell == null) {
                    if (initializer is IrFunctionExpression ||
                        initializer is IrFunctionReference ||
                        initializer is IrRichFunctionReference
                    ) {
                        values[statement.symbol] = source
                    } else {
                        val destination = allocate(valueType(statement.type, statement))
                        emit(Instruction.Move(destination, source))
                        values[statement.symbol] = destination
                    }
                } else {
                    prepareAllocationBlock()
                    val cellType = TypeRef.Local(cell.typeId)
                    val destination = allocate(ValueType.Ref(nullable = false, type = cellType))
                    emit(Instruction.NewObject(destination, cellType))
                    emit(Instruction.FieldSet(destination, FieldRef.Local(cell.fieldId), source))
                    values[statement.symbol] = destination
                }
            }

            is IrSetValue -> {
                rejectFunctionVariance(statement.value.type, statement.symbol.owner.type, statement)
                val source =
                    coerceLocalValue(
                        compileExpression(statement.value, statement.symbol.owner.type),
                        statement.symbol.owner.type,
                        statement,
                    )
                val cell = captureCells[statement.symbol]
                if (cell == null) {
                    val destination = values[statement.symbol] ?: throw UnsupportedKotlinIr(statement, "unknown mutable local")
                    emit(Instruction.Move(destination, source))
                } else {
                    emit(
                        Instruction.FieldSet(
                            loadCellReference(statement.symbol, statement),
                            FieldRef.Local(cell.fieldId),
                            source,
                        ),
                    )
                }
            }

            is IrSetField -> {
                val field =
                    fieldsByBacking[statement.symbol]
                        ?: resolveClassInstance(statement.receiver?.type)?.let { instance ->
                            genericFieldsByBacking[statement.symbol to instance]
                        }
                        ?: throw UnsupportedKotlinIr(statement, "unknown instance field")
                val receiver =
                    statement.receiver?.let(::compileExpression)
                        ?: throw UnsupportedKotlinIr(statement, "instance field receiver is missing")
                val value = compileExpression(statement.value, statement.symbol.owner.type)
                emit(Instruction.FieldSet(receiver, FieldRef.Local(field.id), value))
            }

            is IrCall -> {
                compileCall(statement)
            }

            is IrDelegatingConstructorCall -> {
                compileDelegatingConstructorCall(statement)
            }

            is IrInstanceInitializerCall -> {
                compileInstanceInitializer(statement)
            }

            is IrReturn -> {
                rejectFunctionVariance(statement.value.type, function.returnType, statement)
                if (function.returnType.isNothing()) {
                    compileStatement(statement.value)
                } else {
                    val destination =
                        if (function.returnType == unitType) {
                            when (val value = statement.value) {
                                is IrCall, is IrBlock -> compileStatement(value)
                            }
                            Destination.Unit
                        } else {
                            Destination.Register(compileExpression(statement.value, function.returnType))
                        }
                    emit(Instruction.Return(destination))
                }
            }

            is IrWhen -> {
                compileWhenStatement(statement)
            }

            is IrWhileLoop -> {
                compileWhile(statement)
            }

            is IrBlock -> {
                if (statement.origin?.toString() == "FOR_LOOP") {
                    compileForLoop(statement)
                } else {
                    statement.statements.forEach(::compileStatement)
                }
            }

            is IrComposite -> {
                statement.statements.forEach(::compileStatement)
            }

            is IrTypeOperatorCall -> {
                if (statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
                    compileStatement(statement.argument)
                } else {
                    compileExpression(statement)
                }
            }

            is IrThrow -> {
                emit(Instruction.Throw(compileExpression(statement.value)))
            }

            is IrBreak -> {
                compileLoopJump(statement, breakJump = true)
            }

            is IrContinue -> {
                compileLoopJump(statement, breakJump = false)
            }

            is IrSimpleFunction -> {
                if (closureLayouts.values.any { layout ->
                        layout.function === statement && layout.expression.constructorReferenceTarget() != null
                    }
                ) {
                    return
                }
                throw UnsupportedKotlinIr(
                    statement,
                    "local functions are unsupported; Tasks.launch requires a direct reference to a top-level, " +
                        "zero-argument function",
                )
            }

            is IrExpression -> {
                compileExpression(statement)
            }

            else -> {
                throw UnsupportedKotlinIr(statement, "unsupported statement ${statement::class.simpleName}")
            }
        }
    }

    private fun compileDelegatingConstructorCall(call: IrDelegatingConstructorCall) {
        if (constructorOwner == null) throw UnsupportedKotlinIr(call, "delegating constructor call is outside a constructor")
        val target = call.symbol.owner
        if (target.parentAsClass.fqNameWhenAvailable?.asString() == "kotlin.Any") return
        val targetConstructor =
            constructorLayouts[call.symbol]
                ?: throw UnsupportedKotlinIr(call, "super constructor is outside the Guest class subset")
        val arguments =
            target.parameters.mapIndexedNotNull { index, parameter ->
                call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
            }
        if (arguments.size != target.parameters.count { it.kind == IrParameterKind.Regular }) {
            throw UnsupportedKotlinIr(call, "super constructor arguments are missing")
        }
        val compiled = arguments.map(::compileExpression)
        emit(
            Instruction.Call(
                Destination.Unit,
                FunctionRef.Local(targetConstructor.functionId),
                listOf(RegisterId.of(0u)) + compiled,
            ),
        )
    }

    private fun compileInstanceInitializer(call: IrInstanceInitializerCall) {
        val layout = constructorOwner ?: throw UnsupportedKotlinIr(call, "class initializer is outside a constructor")
        layout.declaration.declarations.forEach { declaration ->
            when (declaration) {
                is IrProperty -> {
                    val field = layout.fields.firstOrNull { it.property === declaration } ?: return@forEach
                    val initializer =
                        declaration.backingField?.initializer?.expression
                            ?: throw UnsupportedKotlinIr(declaration, "class field initializer is missing")
                    val value = compileExpression(initializer, field.property.backingField?.type)
                    emit(Instruction.FieldSet(RegisterId.of(0u), FieldRef.Local(field.id), value))
                }

                is IrAnonymousInitializer -> {
                    declaration.body.statements.forEach(::compileStatement)
                }
            }
        }
    }

    private fun compileExpression(expression: IrExpression): RegisterId = compileExpression(expression, null)

    private fun compileExpression(
        expression: IrExpression,
        expectedType: IrType?,
    ): RegisterId {
        val source = compileRawExpression(expression, expectedType)
        if (expectedType?.isKotlinAny() != true) return source
        val target = valueType(expectedType, expression) as ValueType.Ref
        return when (val actual = valueType(expression.type, expression)) {
            ValueType.I32 -> {
                boxInt(source, target)
            }

            is ValueType.Ref -> {
                if (actual == target) {
                    source
                } else {
                    allocate(target).also { destination ->
                        emit(Instruction.CheckedCast(destination, source, target.type))
                    }
                }
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "universal boxing is supported only for Int and references")
            }
        }
    }

    private fun compileRawExpression(
        expression: IrExpression,
        expectedType: IrType?,
    ): RegisterId =
        when (expression) {
            is IrConst -> {
                if (expression.value == null) {
                    val type = valueType(expectedType ?: expression.type, expression)
                    if (type !is ValueType.Ref || !type.nullable) {
                        throw UnsupportedKotlinIr(expression, "null requires a nullable reference type")
                    }
                    allocate(type).also { emit(Instruction.Null(it)) }
                } else {
                    val constant = expression.toArtifactConstant(literalIds)
                    val constantId =
                        constantIds[constant]
                            ?: throw UnsupportedKotlinIr(expression, "constant is absent from canonical pool")
                    allocate(valueType(expression.type, expression)).also { emit(Instruction.Const(it, constantId)) }
                }
            }

            is IrGetValue -> {
                loadValue(expression.symbol, expression)
            }

            is IrGetField -> {
                val instanceField =
                    fieldsByBacking[expression.symbol]
                        ?: resolveClassInstance(expression.receiver?.type)?.let { instance ->
                            genericFieldsByBacking[expression.symbol to instance]
                        }
                if (instanceField != null) {
                    val receiver =
                        expression.receiver?.let(::compileExpression)
                            ?: throw UnsupportedKotlinIr(expression, "instance field receiver is missing")
                    allocate(instanceField.type).also { destination ->
                        emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(instanceField.id)))
                    }
                } else {
                    val field =
                        topLevelFieldsByBacking[expression.symbol]
                            ?: throw UnsupportedKotlinIr(expression, "unknown field")
                    allocate(field.type).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Local(field.fieldId)))
                    }
                }
            }

            is IrStringConcatenation -> {
                compileConcat(expression)
            }

            is IrConstructorCall -> {
                compileConstructor(expression)
            }

            is IrFunctionExpression -> {
                compileClosure(expression)
            }

            is IrRichFunctionReference, is IrFunctionReference -> {
                compileClosure(expression)
            }

            is IrGetEnumValue -> {
                val entry = enumEntries[expression.symbol]
                if (entry != null) {
                    allocate(ValueType.Ref(nullable = false, type = entry.ownerType)).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Local(entry.fieldId)))
                    }
                } else {
                    val external = externalEnumEntries[expression.symbol] ?: throw UnsupportedKotlinIr(expression, "unknown enum entry")
                    allocate(valueType(expression.type, expression)).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Imported(external.importId)))
                    }
                }
            }

            is IrCall -> {
                compileCall(expression)
                    ?: throw UnsupportedKotlinIr(expression, "Unit call used as a value")
            }

            is IrWhen -> {
                compileWhenValue(expression)
            }

            is IrBlock -> {
                compileBlockValue(expression, expectedType)
            }

            is IrTypeOperatorCall -> {
                compileTypeOperator(expression)
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "unsupported expression ${expression::class.simpleName}")
            }
        }

    private fun loadValue(
        symbol: IrValueSymbol,
        element: IrElement,
    ): RegisterId {
        captureCells[symbol]?.let { cell ->
            return allocate(cell.valueType).also { destination ->
                emit(Instruction.FieldGet(destination, loadCellReference(symbol, element), FieldRef.Local(cell.fieldId)))
            }
        }
        values[symbol]?.let { return it }
        val capture = captureFields[symbol] ?: throw UnsupportedKotlinIr(element, "unknown local value")
        val receiver = closureReceiver ?: throw UnsupportedKotlinIr(element, "closure capture has no environment receiver")
        return allocate(capture.type).also { destination ->
            emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(capture.fieldId)))
        }
    }

    private fun loadCellReference(
        symbol: IrValueSymbol,
        element: IrElement,
    ): RegisterId {
        values[symbol]?.let { return it }
        val capture = captureFields[symbol] ?: throw UnsupportedKotlinIr(element, "unknown captured mutable local")
        val cell = capture.cell ?: throw UnsupportedKotlinIr(element, "captured value is not backed by a mutable cell")
        val receiver = closureReceiver ?: throw UnsupportedKotlinIr(element, "closure capture has no environment receiver")
        return allocate(ValueType.Ref(nullable = false, type = TypeRef.Local(cell.typeId))).also { destination ->
            emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(capture.fieldId)))
        }
    }

    private fun compileClosure(expression: IrExpression): RegisterId {
        val layout =
            closureLayouts[expression]
                ?: throw UnsupportedKotlinIr(expression, "only Guest function, method, and constructor references are supported")
        val boundValue = layout.captures.firstOrNull { it.initialValue != null }?.let { compileExpression(requireNotNull(it.initialValue)) }
        prepareAllocationBlock()
        val closureType = TypeRef.Local(layout.typeId)
        val destination = allocate(ValueType.Ref(nullable = false, type = closureType))
        emit(Instruction.NewObject(destination, closureType))
        layout.captures.forEach { capture ->
            val value =
                when {
                    capture.initialValue != null -> requireNotNull(boundValue)
                    capture.cell == null -> loadValue(requireNotNull(capture.symbol), expression)
                    else -> loadCellReference(requireNotNull(capture.symbol), expression)
                }
            emit(Instruction.FieldSet(destination, FieldRef.Local(capture.fieldId), value))
        }
        return destination
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileConstructor(call: IrConstructorCall): RegisterId {
        val target = call.symbol.owner
        val arguments = call.arguments.filterNotNull()
        if (target.parentAsClass.fqNameWhenAvailable?.asString() == "compukter.concurrent.IntChannel") {
            throw UnsupportedKotlinIr(call, "IntChannel must be initialized directly in a top-level val")
        }
        platformScalars.constructor(call.symbol)?.let { scalarType ->
            val argument =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform scalar constructor requires one argument")
            val value = compileExpression(argument)
            scalarType.minimumInt?.let { minimum ->
                emitIntRangePrecondition(value, InlineIntRange(minimum, requireNotNull(scalarType.maximumInt)), call)
            }
            return value
        }
        inlineValueClasses.constructor(call.symbol)?.let { layout ->
            val argument =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "value class constructor argument is missing")
            if (argument.type != layout.underlyingType) {
                throw UnsupportedKotlinIr(call, "value class constructor argument type does not match its underlying scalar")
            }
            val value = compileExpression(argument)
            layout.intRange?.let { emitIntRangePrecondition(value, it, call) }
            return value
        }
        val exceptionImport =
            when (target.parentAsClass.fqNameWhenAvailable?.asString()) {
                "kotlin.Throwable", "kotlin.Exception", "kotlin.RuntimeException" -> ImportId.of(2u)
                "kotlin.IllegalArgumentException" -> ImportId.of(3u)
                else -> null
            }
        if (exceptionImport != null) {
            arguments.forEach(::compileExpression)
            prepareAllocationBlock()
            val type = TypeRef.Imported(exceptionImport)
            return allocate(ValueType.Ref(nullable = false, type = type)).also { destination ->
                emit(Instruction.NewObject(destination, type))
            }
        }
        if (target.parentAsClass.symbol == kotlinCharArrayClass &&
            call.type.isExactClass(kotlinCharArrayClass) &&
            arguments.size == 1 &&
            arguments[0].type == intType
        ) {
            val length = compileExpression(arguments.single())
            prepareAllocationBlock()
            return allocate(charArrayType).also { destination ->
                emit(Instruction.NewArray(destination, (charArrayType as ValueType.Ref).type, length))
            }
        }
        if (target.parentAsClass.symbol == kotlinIntArrayClass &&
            call.type.isExactClass(kotlinIntArrayClass) &&
            arguments.size == 1 &&
            arguments[0].type == intType
        ) {
            val length = compileExpression(arguments.single())
            prepareAllocationBlock()
            return allocate(intArrayType).also { destination ->
                emit(Instruction.NewArray(destination, (intArrayType as ValueType.Ref).type, length))
            }
        }
        if (call.type == kotlinStringType &&
            arguments.size == 3 &&
            arguments[0].type.isExactClass(kotlinCharArrayClass) &&
            arguments[1].type == intType &&
            arguments[2].type == intType
        ) {
            val compiled = arguments.map(::compileExpression)
            val end = allocate(ValueType.I32)
            emit(Instruction.Add(end, compiled[1], compiled[2]))
            prepareAllocationBlock()
            return allocate(stringType).also { destination ->
                emit(Instruction.StringFromCharArray(destination, compiled[0], compiled[1], end))
            }
        }
        val targetConstructor =
            constructorLayouts[call.symbol]
                ?: resolveClassInstance(call.type)?.let(genericConstructorLayouts::get)
                ?: throw UnsupportedKotlinIr(call, "constructor is outside the project subset")
        val layout = targetConstructor.layout
        val parameters = target.parameters.withIndex().filter { it.value.kind == IrParameterKind.Regular }
        val previousBindings = parameters.associate { it.value.symbol to values[it.value.symbol] }
        val compiledArguments =
            try {
                val explicit =
                    parameters
                        .mapNotNull { (index, parameter) ->
                            call.arguments.getOrNull(index)?.let { expression -> Triple(index, parameter, expression) }
                        }.sortedWith(compareBy({ it.third.startOffset.takeIf { offset -> offset >= 0 } ?: Int.MAX_VALUE }, { it.first }))
                explicit.forEach { (_, parameter, expression) ->
                    rejectFunctionVariance(expression.type, parameter.type, expression)
                    values[parameter.symbol] = compileExpression(expression, parameter.type)
                }
                parameters.forEach { (index, parameter) ->
                    if (call.arguments.getOrNull(index) == null) {
                        val default =
                            parameter.defaultValue?.expression
                                ?: throw UnsupportedKotlinIr(call, "constructor argument ${parameter.name} is missing")
                        rejectFunctionVariance(default.type, parameter.type, default)
                        values[parameter.symbol] = compileExpression(default, parameter.type)
                    }
                }
                parameters.map { (_, parameter) ->
                    values[parameter.symbol] ?: throw UnsupportedKotlinIr(call, "constructor argument ${parameter.name} is missing")
                }
            } finally {
                previousBindings.forEach { (symbol, previous) ->
                    if (previous == null) values.remove(symbol) else values[symbol] = previous
                }
            }
        val ownerType = TypeRef.Local(layout.typeId)
        prepareAllocationBlock()
        return allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
            emit(Instruction.NewObject(destination, ownerType))
            emit(
                Instruction.Call(
                    Destination.Unit,
                    FunctionRef.Local(targetConstructor.functionId),
                    listOf(destination) + compiledArguments,
                ),
            )
        }
    }

    private fun compileTypeOperator(expression: IrTypeOperatorCall): RegisterId {
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST) {
            rejectFunctionVariance(expression.argument.type, expression.typeOperand, expression)
        }
        val source = compileExpression(expression.argument, expression.typeOperand)
        val target = valueType(expression.typeOperand, expression)
        val intBoxType = TypeRef.Imported(ImportId.of(INT_BOX_RUNTIME_TYPE))
        return when (expression.operator) {
            IrTypeOperator.INSTANCEOF -> {
                val reference =
                    if (expression.typeOperand == intType) {
                        intBoxType
                    } else {
                        (target as? ValueType.Ref)?.type
                            ?: throw UnsupportedKotlinIr(expression, "type test target is not a reference")
                    }
                allocate(ValueType.Bool).also { destination ->
                    emit(Instruction.IsType(destination, source, reference))
                }
            }

            IrTypeOperator.CAST -> {
                if (expression.typeOperand != intType) {
                    throw UnsupportedKotlinIr(expression, "cast target is outside the supported boxed Int subset")
                }
                if (valueType(expression.argument.type, expression) !is ValueType.Ref) {
                    throw UnsupportedKotlinIr(expression, "boxed Int cast requires a reference operand")
                }
                val box = allocate(ValueType.Ref(nullable = false, type = intBoxType))
                emit(Instruction.CheckedCast(box, source, intBoxType))
                allocate(ValueType.I32).also { destination ->
                    emit(Instruction.FieldGet(destination, box, FieldRef.Imported(ImportId.of(INT_BOX_VALUE_IMPORT))))
                }
            }

            IrTypeOperator.IMPLICIT_CAST,
            -> {
                val reference = target as? ValueType.Ref ?: return source
                allocate(reference).also { destination ->
                    emit(Instruction.CheckedCast(destination, source, reference.type))
                }
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "cast ${expression.operator} is outside the project subset")
            }
        }
    }

    private fun boxInt(
        source: RegisterId,
        target: ValueType.Ref,
    ): RegisterId {
        val boxType = TypeRef.Imported(ImportId.of(INT_BOX_RUNTIME_TYPE))
        prepareAllocationBlock()
        val box = allocate(ValueType.Ref(nullable = false, type = boxType))
        emit(Instruction.NewObject(box, boxType))
        emit(Instruction.FieldSet(box, FieldRef.Imported(ImportId.of(INT_BOX_VALUE_IMPORT)), source))
        return allocate(target).also { destination ->
            emit(Instruction.CheckedCast(destination, box, target.type))
        }
    }

    private fun coerceLocalValue(
        source: RegisterId,
        targetType: IrType,
        element: IrElement,
    ): RegisterId {
        val registerTypes = leadingParameterTypes + sourceParameters.map { valueType(it.type, it) } + localTypes
        val from = registerTypes[source.value.toInt()]
        val to = valueType(targetType, element)
        if (from == to) return source
        if (from is ValueType.Ref && to is ValueType.Ref) {
            return allocate(to).also { destination -> emit(Instruction.CheckedCast(destination, source, to.type)) }
        }
        throw UnsupportedKotlinIr(element, "local assignment types do not match")
    }

    private fun rejectFunctionVariance(
        actualType: IrType,
        expectedType: IrType,
        element: IrElement,
    ) {
        val actual = actualType.guestFunctionShape()
        val expected = expectedType.guestFunctionShape()
        if (actual != expected && (actual != null || expected != null)) {
            throw UnsupportedKotlinIr(element, "function-value variance conversions are not supported")
        }
    }

    private fun compileConcat(expression: IrStringConcatenation): RegisterId {
        val arguments = expression.arguments
        if (arguments.isEmpty()) throw UnsupportedKotlinIr(expression, "empty string concatenation")
        var result = compileStringPart(arguments.first())
        arguments.drop(1).forEach { argument ->
            val right = compileStringPart(argument)
            prepareAllocationBlock()
            val destination = allocate(stringType)
            emit(Instruction.StringConcat(destination, result, right))
            result = destination
        }
        return result
    }

    private fun compileStringPart(expression: IrExpression): RegisterId {
        if (expression.type == kotlinStringType) return compileExpression(expression)
        if (expression.type == unitType) {
            compileStatement(expression)
            return loadStringLiteral("kotlin.Unit")
        }
        val conversionType = stringValueType(expression)
        val source = compileExpression(expression)
        prepareAllocationBlock()
        return allocate(stringType).also { destination ->
            emit(Instruction.StringValueOf(conversionType, destination, source))
        }
    }

    private fun stringValueType(expression: IrExpression): StringValueType =
        when (valueType(expression.type, expression)) {
            ValueType.I32 -> StringValueType.I32
            ValueType.I64 -> StringValueType.I64
            ValueType.F32 -> StringValueType.F32
            ValueType.Bool -> StringValueType.BOOL
            ValueType.Char -> StringValueType.CHAR
            else -> throw UnsupportedKotlinIr(expression, "object string conversion requires virtual dispatch")
        }

    private fun loadStringLiteral(value: String): RegisterId {
        val constant = value.toArtifactConstant(literalIds)
        val constantId =
            constantIds[constant]
                ?: throw IllegalStateException("canonical string literal is absent from the constant pool")
        return allocate(stringType).also { destination ->
            emit(Instruction.Const(destination, constantId))
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCall(call: IrCall): RegisterId? {
        val interfaceSuper = call.superQualifierSymbol?.owner?.kind == ClassKind.INTERFACE
        val target =
            if (interfaceSuper) {
                interfaceSuperBody(call.symbol.owner)
                    ?: throw UnsupportedKotlinIr(call, "interface super call requires one concrete default body")
            } else {
                call.symbol.owner
            }
        val targetName = target.fqNameWhenAvailable?.asString()
        if (targetName == "kotlin.internal.ir.EQEQEQ") {
            val operands = call.arguments.filterNotNull()
            if (operands.size != 2 || operands.any { valueType(it.type, it) !is ValueType.Ref }) {
                throw UnsupportedKotlinIr(call, "reference identity requires two reference operands")
            }
            val left = compileExpression(operands[0])
            val right = compileExpression(operands[1])
            val anyType = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))
            val leftAny = allocate(ValueType.Ref(nullable = false, type = anyType))
            emit(Instruction.CheckedCast(leftAny, left, anyType))
            val rightAny = allocate(ValueType.Ref(nullable = false, type = anyType))
            emit(Instruction.CheckedCast(rightAny, right, anyType))
            return allocate(ValueType.Bool).also { destination -> emit(Instruction.RefEqual(destination, leftAny, rightAny)) }
        }
        if (target.isExternal && targetName in setOf("kotlin.collections.listOf", "kotlin.collections.emptyList")) {
            return compileListFactory(call, targetName == "kotlin.collections.listOf")
        }
        if ((
                targetName?.startsWith("kotlin.Function") == true ||
                    targetName?.startsWith("kotlin.reflect.KFunction") == true
            ) &&
            targetName.endsWith(".invoke")
        ) {
            val receiverExpression = dispatchReceiver(call, target, targetName)
            val shape =
                receiverExpression.type.guestFunctionShape()
                    ?: throw UnsupportedKotlinIr(call, "unsupported function-value receiver type")
            val invokeId =
                invokeFunctionIds[shape]
                    ?: throw UnsupportedKotlinIr(call, "unsupported function-value signature")
            val arguments =
                target.parameters.mapIndexedNotNull { index, parameter ->
                    call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                }
            if (arguments.size != shape.arity) {
                throw UnsupportedKotlinIr(call, "function-value call has the wrong number of arguments")
            }
            val receiver = compileExpression(receiverExpression)
            val values = arguments.map(::compileExpression)
            val destination = destinationFor(call.type, call)
            emit(
                Instruction.CallInterface(
                    destination,
                    FunctionRef.Local(invokeId),
                    listOf(receiver) + values,
                ),
            )
            return (destination as? Destination.Register)?.id
        }
        when (target.fqNameWhenAvailable?.asString().takeIf { target.isExternal }) {
            "compukter.concurrent.Tasks.launch" -> return compileTaskLaunch(call, target)
            "compukter.concurrent.Task.join" -> return compileTaskJoin(call, target)
            "compukter.concurrent.IntChannel.send" -> return compileChannelSend(call, target)
            "compukter.concurrent.IntChannel.receive" -> return compileChannelReceive(call, target)
        }
        topLevelFieldsByGetter[target.symbol]?.let { field ->
            return allocate(field.type).also { destination ->
                emit(Instruction.StaticGet(destination, FieldRef.Local(field.fieldId)))
            }
        }
        platformScalars.constant(target)?.let { value ->
            val artifactConstant = value.scalarValue().toArtifactConstant(literalIds)
            val constantId =
                constantIds[artifactConstant]
                    ?: throw UnsupportedKotlinIr(call, "platform scalar constant is absent from canonical pool")
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.Const(destination, constantId))
            }
        }
        if (platformScalars.isUnderlyingGetter(target)) {
            val receiver =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform scalar property getter receiver is missing")
            return compileExpression(receiver)
        }
        inlineValueClasses.constant(target.symbol)?.let { constant ->
            val artifactConstant = constant.value.toArtifactConstant(literalIds)
            val constantId =
                constantIds[artifactConstant]
                    ?: throw UnsupportedKotlinIr(call, "value class companion constant is absent from canonical pool")
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.Const(destination, constantId))
            }
        }
        floatCompanionConstant(target)?.let { value ->
            val artifactConstant = Constant.F32(value.toBits().toUInt())
            val constantId =
                constantIds[artifactConstant]
                    ?: throw UnsupportedKotlinIr(call, "Float companion constant is absent from canonical pool")
            return allocate(ValueType.F32).also { destination ->
                emit(Instruction.Const(destination, constantId))
            }
        }
        inlineValueClasses.getter(target.symbol)?.let {
            val receiver =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "value class property getter receiver is missing")
            return compileExpression(receiver)
        }
        val propertyReceiver = call.dispatchReceiver
        val receiverClassInstance = resolveClassInstance(propertyReceiver?.type)
        (
            resolveFieldAccessor(target.symbol, fieldsBySetter)
                ?: receiverClassInstance?.let { genericFieldsBySetter[target.symbol to it] }
        )?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "property setter receiver is missing")
            val valueExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "property setter value is missing")
            val receiver = compileExpression(receiverExpression)
            val value = compileExpression(valueExpression)
            emit(Instruction.FieldSet(receiver, FieldRef.Local(field.id), value))
            return null
        }
        (
            resolveFieldAccessor(target.symbol, fieldsByGetter)
                ?: receiverClassInstance?.let { genericFieldsByGetter[target.symbol to it] }
        )?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "property getter receiver is missing")
            val receiver = compileExpression(receiverExpression)
            return allocate(field.type).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(field.id)))
            }
        }
        externalFieldsByGetter[target.symbol]?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform property getter receiver is missing")
            val receiver = compileExpression(receiverExpression)
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Imported(field.importId)))
            }
        }
        compileIntArrayFactory(call, target)?.let { return it }
        compileReferenceArrayFactory(call, target)?.let { return it }
        trustedIntrinsic(target)?.let { intrinsic ->
            val arguments =
                target.parameters
                    .zip(call.arguments)
                    .filter { (parameter, _) -> parameter.kind == IrParameterKind.Regular }
                    .map { (_, argument) -> compileExpression(requireNotNull(argument)) }
            val capability = requireNotNull(capabilityIds[intrinsic.capability])
            val destination = if (intrinsic.terminal) Destination.Unit else destinationFor(call.type, call)
            if (intrinsic.blocking == IntrinsicBlockingMode.VM_TASK) {
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
            if (intrinsic.terminal) emit(Instruction.Unreachable)
            return (destination as? Destination.Register)?.id
        }
        externalFunctions[target.symbol]?.let { external ->
            val argumentExpressions = resolveProjectCallArguments(call, target)
            val arguments =
                argumentExpressions.zip(loweredParameters(target, session)).map { (argument, parameter) ->
                    compileCallArgument(argument, parameter.type)
                }
            val destination = destinationFor(target.returnType, call)
            if (target.isSuspend) {
                val resume = createBlock()
                emit(Instruction.CallSuspend(destination, FunctionRef.Imported(external.importId), arguments, blockId(resume)))
                currentBlock = resume
            } else {
                emit(Instruction.Call(destination, FunctionRef.Imported(external.importId), arguments))
            }
            if (target.returnType.isNothing()) emit(Instruction.Unreachable)
            return (destination as? Destination.Register)?.id
        }
        compileCompareToPredicate(call, target)?.let { return it }
        val targetId = projectFunctionId(call, target)
        if (targetId == null) {
            if (interfaceSuper) {
                throw UnsupportedKotlinIr(call, "interface super target is outside the project subset")
            }
            val argumentExpressions = call.arguments.filterNotNull()
            val universalEquality =
                target.name.asString() in setOf("EQEQ", "equals", "eqeq") &&
                    argumentExpressions.size == 2 &&
                    argumentExpressions.any { it.type.isKotlinAny() }
            val arrayStoreElementType =
                if (
                    target.name.asString() == "set" &&
                    argumentExpressions.size == 3 &&
                    isSupportedReferenceArray(argumentExpressions[0].type)
                ) {
                    guestTypes.arrayElement(resolvedType(argumentExpressions[0].type))
                } else {
                    null
                }
            val arguments =
                argumentExpressions.mapIndexed { index, argument ->
                    if (argument is IrConst && argument.value == null) {
                        val other = argumentExpressions.firstOrNull { it !== argument && it.type != argument.type }
                        val reference = other?.let { valueType(it.type, it) as? ValueType.Ref }
                        if (reference == null) {
                            compileExpression(argument)
                        } else {
                            allocate(reference.copy(nullable = true)).also { emit(Instruction.Null(it)) }
                        }
                    } else if (index == 2 && arrayStoreElementType != null) {
                        compileExpression(argument, arrayStoreElementType)
                    } else {
                        val compiled = compileExpression(argument)
                        if (universalEquality && valueType(argument.type, argument) == ValueType.I32) {
                            boxInt(compiled, ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
                        } else {
                            compiled
                        }
                    }
                }
            return compileBuiltinCall(call, target, argumentExpressions, arguments)
        }
        val specialization = projectFunctionInstance(call, target)
        val arguments =
            resolveProjectCallArguments(call, target).zip(loweredParameters(target, session)).map { (argument, parameter) ->
                compileCallArgument(argument, specialization?.substitute(parameter.type) ?: parameter.type)
            }
        val destination = destinationFor(call.type, call)
        if (target.isSuspend) {
            val resume = createBlock()
            emit(Instruction.CallSuspend(destination, FunctionRef.Local(targetId), arguments, blockId(resume)))
            currentBlock = resume
        } else {
            val owner = target.parent as? IrClass
            val instruction =
                when {
                    interfaceSuper -> {
                        Instruction.Call(destination, FunctionRef.Local(targetId), arguments)
                    }

                    owner?.kind == ClassKind.INTERFACE -> {
                        Instruction.CallInterface(destination, FunctionRef.Local(targetId), arguments)
                    }

                    owner != null && !inlineValueClasses.contains(owner.symbol) &&
                        (target.modality != Modality.FINAL || target.overriddenSymbols.isNotEmpty()) -> {
                        Instruction.CallVirtual(destination, FunctionRef.Local(targetId), arguments)
                    }

                    else -> {
                        Instruction.Call(destination, FunctionRef.Local(targetId), arguments)
                    }
                }
            emit(instruction)
        }
        if (target.returnType.isNothing()) emit(Instruction.Unreachable)
        return (destination as? Destination.Register)?.id
    }

    private fun interfaceSuperBody(function: IrSimpleFunction): IrSimpleFunction? {
        if (function.body != null) return function
        if (function.origin != IrDeclarationOrigin.FAKE_OVERRIDE) return null
        val inherited = function.overriddenSymbols.map { interfaceSuperBody(it.owner) }
        if (inherited.any { it == null }) return null
        return inherited.filterNotNull().distinctBy { it.symbol }.singleOrNull()
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileTaskLaunch(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId {
        val block =
            target.parameters
                .mapIndexedNotNull { index, parameter ->
                    call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                }.singleOrNull()
                ?: throw UnsupportedKotlinIr(call, "Tasks.launch requires a non-null () -> Unit callable")
        val referenced =
            when (block) {
                is IrRichFunctionReference -> {
                    if (block.boundValues.isNotEmpty()) null else block.reflectionTargetSymbol?.owner as? IrSimpleFunction
                }

                is IrFunctionReference -> {
                    if (block.arguments.any { it != null }) null else block.reflectionTarget?.owner as? IrSimpleFunction
                }

                else -> {
                    null
                }
            }
        return allocate(valueType(call.type, call)).also { destination ->
            if (referenced != null) {
                if (
                    referenced.parent !is IrFile ||
                    referenced.isSuspend ||
                    referenced.returnType != unitType ||
                    loweredParameters(referenced, session).isNotEmpty()
                ) {
                    throw UnsupportedKotlinIr(
                        block,
                        "Tasks.launch requires a direct reference to a top-level, zero-argument function",
                    )
                }
                val functionRef =
                    functionIds[referenced.symbol]?.let(FunctionRef::Local)
                        ?: throw UnsupportedKotlinIr(block, "Tasks.launch target must be declared in the Guest project")
                emit(Instruction.TaskSpawn(destination, functionRef, emptyList()))
            } else {
                if (block is IrRichFunctionReference || block is IrFunctionReference) {
                    throw UnsupportedKotlinIr(block, "Tasks.launch does not support bound function references")
                }
                val callable = compileExpression(block)
                val trampoline =
                    taskLaunchTrampolineFunctionId
                        ?: throw UnsupportedKotlinIr(block, "Tasks.launch requires a non-null () -> Unit callable")
                emit(Instruction.TaskSpawn(destination, FunctionRef.Local(trampoline), listOf(callable)))
            }
        }
    }

    private fun compileTaskJoin(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val receiver =
            target.parameters
                .mapIndexedNotNull { index, parameter ->
                    call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                }.singleOrNull()
                ?: throw UnsupportedKotlinIr(call, "Task.join receiver is missing")
        val task = compileExpression(receiver)
        val resume = createBlock()
        emit(Instruction.TaskJoin(Destination.Unit, task, blockId(resume)))
        currentBlock = resume
        return null
    }

    private fun compileChannelSend(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val receiver = dispatchReceiver(call, target, "IntChannel.send")
        val valueExpression =
            target.parameters
                .mapIndexedNotNull { index, parameter ->
                    call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                }.singleOrNull()
                ?: throw UnsupportedKotlinIr(call, "IntChannel.send value is missing")
        val channel = compileExpression(receiver)
        val value = compileExpression(valueExpression)
        val resume = createBlock()
        emit(Instruction.ChannelSend(channel, value, blockId(resume)))
        currentBlock = resume
        return null
    }

    private fun compileChannelReceive(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId {
        val channel = compileExpression(dispatchReceiver(call, target, "IntChannel.receive"))
        val destination = allocate(ValueType.I32)
        val resume = createBlock()
        emit(Instruction.ChannelReceive(destination, channel, blockId(resume)))
        currentBlock = resume
        return destination
    }

    private fun dispatchReceiver(
        call: IrCall,
        target: IrSimpleFunction,
        operation: String,
    ): IrExpression =
        target.parameters
            .mapIndexedNotNull { index, parameter ->
                call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
            }.singleOrNull()
            ?: throw UnsupportedKotlinIr(call, "$operation receiver is missing")

    private fun resolveProjectCallArguments(
        call: IrCall,
        target: IrSimpleFunction,
    ): List<ResolvedCallArgument> {
        val platformDefaults =
            session.platformFunctions
                .singleOrNull { link ->
                    link.symbol == target.fqNameWhenAvailable?.asString() &&
                        link.signature == target.canonicalPlatformSignature()
                }?.defaultArguments
                .orEmpty()
        return loweredParameters(target, session).mapIndexed { loweredIndex, parameter ->
            val index = target.parameters.indexOf(parameter)
            call.arguments.getOrNull(index)?.let(ResolvedCallArgument::Expression)
                ?: platformDefaults.getOrNull(loweredIndex)?.let(ResolvedCallArgument::PlatformDefault)
                ?: parameter.defaultValue
                    ?.expression
                    ?.takeIf { expression -> isSupportedScalarDefault(expression) || isSupportedStringArrayDefault(expression) }
                    ?.let(ResolvedCallArgument::Expression)
                ?: throw UnsupportedKotlinIr(
                    call,
                    "omitted argument ${parameter.name} is outside the project subset",
                )
        }
    }

    private fun compileCallArgument(
        argument: ResolvedCallArgument,
        expectedType: IrType,
    ): RegisterId =
        when (argument) {
            is ResolvedCallArgument.Expression -> {
                rejectFunctionVariance(argument.expression.type, expectedType, argument.expression)
                compileExpression(argument.expression, expectedType)
            }

            is ResolvedCallArgument.PlatformDefault -> {
                when (val value = argument.value) {
                    is PlatformDefaultArgument.IntValue -> {
                        val constantId =
                            constantIds[Constant.I32(value.value)]
                                ?: throw IllegalArgumentException("platform Int default is absent from canonical pool: ${value.value}")
                        allocate(ValueType.I32).also { destination ->
                            emit(Instruction.Const(destination, constantId))
                        }
                    }

                    is PlatformDefaultArgument.EnumEntry -> {
                        val field =
                            externalDefaultEnumEntries[value.symbol]
                                ?: throw IllegalArgumentException("platform enum default entry is not linked: ${value.symbol}")
                        val ownerType =
                            externalClassTypes[field.ownerSymbol]
                                ?: throw IllegalArgumentException("platform enum default owner is not linked: ${value.symbol}")
                        allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
                            emit(Instruction.StaticGet(destination, FieldRef.Imported(field.importId)))
                        }
                    }
                }
            }
        }

    private fun isSupportedScalarDefault(expression: IrExpression): Boolean =
        expression is IrConst &&
            expression.value != null &&
            expression.type in setOf(intType, floatType, booleanType, charType, kotlinStringType)

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
                val arguments = call.arguments.filterNotNull()
                arguments.isEmpty() ||
                    (arguments.singleOrNull() as? IrVararg)
                        ?.elements
                        ?.all { it is IrExpression } == true
            }

            else -> {
                false
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileListFactory(
        call: IrCall,
        nonemptyFactory: Boolean,
    ): RegisterId {
        val elementType =
            call.typeArguments.singleOrNull()?.let(::resolvedType)
                ?: throw UnsupportedKotlinIr(call, "list factory requires a concrete element type")
        if (elementType.isNullable()) {
            throw UnsupportedKotlinIr(call, "nullable list elements are outside the project subset")
        }
        val intElements = elementType == intType
        val target =
            if (intElements) {
                constructorLayouts.values.singleOrNull {
                    it.layout.declaration.fqNameWhenAvailable
                        ?.asString() ==
                        "kotlin.collections.IntArrayBackedList"
                }
            } else {
                genericConstructorLayouts[
                    GuestClassInstance(
                        classInstanceTypeIds.keys
                            .firstOrNull {
                                it.declaration.fqNameWhenAvailable?.asString() ==
                                    "kotlin.collections.ArrayBackedList"
                            }?.declaration
                            ?: throw UnsupportedKotlinIr(call, "reference list implementation is unavailable"),
                        listOf(elementType),
                    ),
                ]
            } ?: throw UnsupportedKotlinIr(call, "list implementation is unavailable for this element type")
        val elements =
            if (!nonemptyFactory) {
                emptyList()
            } else {
                directVarargElements(
                    call,
                    "listOf requires direct vararg elements",
                    "spread listOf arguments are outside the project subset",
                )
            }
        val arrayType =
            if (intElements) {
                intArrayType
            } else {
                target.layout.fields
                    .single()
                    .type
            }
        val arrayRef =
            arrayType as? ValueType.Ref
                ?: throw UnsupportedKotlinIr(call, "unsupported list element storage")
        val array = compileArrayElements(call, arrayRef, elements) { compileExpression(it, elementType) }
        val ownerType = TypeRef.Local(target.layout.typeId)
        prepareAllocationBlock()
        return allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
            emit(Instruction.NewObject(destination, ownerType))
            emit(Instruction.Call(Destination.Unit, FunctionRef.Local(target.functionId), listOf(destination, array)))
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileReferenceArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val resolvedCallType = resolvedType(call.type)
        val arrayType =
            if (guestTypes.isStringArray(resolvedCallType)) {
                stringArrayType
            } else {
                guestTypes.referenceArrayType(resolvedCallType) ?: return null
            }
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
                    directVarargElements(
                        call,
                        "arrayOf requires a direct vararg",
                        "spread arrayOf arguments are outside the project subset",
                    )
                }

                else -> {
                    return null
                }
            }
        val elementType = guestTypes.arrayElement(resolvedCallType)
        return compileArrayElements(call, arrayType as ValueType.Ref, elements) { compileExpression(it, elementType) }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileIntArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        if (!call.type.isExactClass(kotlinIntArrayClass) || target.fqNameWhenAvailable?.asString() != "kotlin.intArrayOf") {
            return null
        }
        val elements =
            directVarargElements(
                call,
                "intArrayOf requires a direct vararg",
                "spread intArrayOf arguments are outside the project subset",
            )
        return compileArrayElements(call, intArrayType as ValueType.Ref, elements, ::compileExpression)
    }

    private fun directVarargElements(
        call: IrCall,
        invalidVararg: String,
        spreadArgument: String,
    ): List<IrExpression> {
        val arguments = call.arguments.filterNotNull()
        if (arguments.isEmpty()) return emptyList()
        val vararg = arguments.singleOrNull() as? IrVararg ?: throw UnsupportedKotlinIr(call, invalidVararg)
        return vararg.elements.map { it as? IrExpression ?: throw UnsupportedKotlinIr(call, spreadArgument) }
    }

    private fun compileArrayElements(
        call: IrCall,
        arrayType: ValueType.Ref,
        elements: List<IrExpression>,
        compileElement: (IrExpression) -> RegisterId,
    ): RegisterId {
        val values = elements.map(compileElement)
        val length = emitI32Constant(values.size, call)
        prepareAllocationBlock()
        val array = allocate(arrayType)
        emit(Instruction.NewArray(array, arrayType.type, length))
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
                ?: throw UnsupportedKotlinIr(element, "generated Int constant is absent from canonical pool")
        return allocate(ValueType.I32).also { emit(Instruction.Const(it, id)) }
    }

    private fun emitI64Constant(
        value: Long,
        element: IrElement,
    ): RegisterId {
        val id =
            constantIds[Constant.I64(value)]
                ?: throw UnsupportedKotlinIr(element, "generated Long constant is absent from canonical pool")
        return allocate(ValueType.I64).also { emit(Instruction.Const(it, id)) }
    }

    private fun emitF32Constant(
        value: Float,
        element: IrElement,
    ): RegisterId {
        val id =
            constantIds[Constant.F32(value.toBits().toUInt())]
                ?: throw UnsupportedKotlinIr(element, "generated Float constant is absent from canonical pool")
        return allocate(ValueType.F32).also { emit(Instruction.Const(it, id)) }
    }

    private fun widenToI64(
        value: RegisterId,
        sourceType: IrType,
        element: IrElement,
    ): RegisterId =
        when (sourceType) {
            longType -> value
            intType -> allocate(ValueType.I64).also { emit(Instruction.Convert(it, value)) }
            else -> throw UnsupportedKotlinIr(element, "only Int can be widened to Long")
        }

    private fun widenToF32(
        value: RegisterId,
        sourceType: IrType,
        element: IrElement,
    ): RegisterId =
        when (sourceType) {
            floatType -> value
            intType, longType -> allocate(ValueType.F32).also { emit(Instruction.Convert(it, value)) }
            else -> throw UnsupportedKotlinIr(element, "only Int or Long can be widened to Float")
        }

    private fun emitIntRangePrecondition(
        value: RegisterId,
        range: InlineIntRange,
        element: IrElement,
    ) {
        val minimum = emitI32Constant(range.minimum, element)
        val maximum = emitI32Constant(range.maximum, element)
        val below = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, below, value, minimum))
        val upperCheck = createBlock()
        val failure = createBlock()
        val success = createBlock()
        emit(Instruction.Branch(below, blockId(failure), blockId(upperCheck)))

        currentBlock = upperCheck
        val above = allocate(ValueType.Bool)
        emit(Instruction.Greater(OrderedScalarValueType.I32, above, value, maximum))
        emit(Instruction.Branch(above, blockId(failure), blockId(success)))

        currentBlock = failure
        prepareAllocationBlock()
        val exceptionType = TypeRef.Imported(ImportId.of(3u))
        val exception = allocate(ValueType.Ref(nullable = false, type = exceptionType))
        emit(Instruction.NewObject(exception, exceptionType))
        emit(Instruction.Throw(exception))

        currentBlock = success
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCompareToPredicate(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val predicateName = target.name.asString()
        if (predicateName !in setOf("less", "lessOrEqual", "greater", "greaterOrEqual")) return null
        if (target.fqNameWhenAvailable?.asString() != "kotlin.internal.ir.$predicateName") return null
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
        var left = compileExpression(operands[0])
        var right = compileExpression(operands[1])
        if (
            compareCall.symbol.owner.fqNameWhenAvailable
                ?.asString() == "kotlin.String.compareTo" &&
            operands.all { it.type == kotlinStringType }
        ) {
            val compared = compileStringCompareTo(compareCall, listOf(left, right))
            val zeroRegister = emitI32Constant(0, call)
            return allocate(ValueType.Bool).also { destination ->
                emit(
                    when (predicateName) {
                        "less" -> Instruction.Less(OrderedScalarValueType.I32, destination, compared, zeroRegister)
                        "lessOrEqual" -> Instruction.LessOrEqual(OrderedScalarValueType.I32, destination, compared, zeroRegister)
                        "greater" -> Instruction.Greater(OrderedScalarValueType.I32, destination, compared, zeroRegister)
                        else -> Instruction.GreaterOrEqual(OrderedScalarValueType.I32, destination, compared, zeroRegister)
                    },
                )
            }
        }
        val numeric = operands.all { it.type == intType || it.type == longType || it.type == floatType }
        val mixedFloat = numeric && operands.any { it.type == floatType }
        val mixedLong =
            !mixedFloat && operands.all { it.type == intType || it.type == longType } &&
                operands.any { it.type == longType }
        val type =
            when {
                mixedFloat -> OrderedScalarValueType.F32
                mixedLong -> OrderedScalarValueType.I64
                else -> orderedType(operands[0].type, call)
            }
        if (mixedFloat) {
            left = widenToF32(left, operands[0].type, call)
            right = widenToF32(right, operands[1].type, call)
        } else if (mixedLong) {
            left = widenToI64(left, operands[0].type, call)
            right = widenToI64(right, operands[1].type, call)
        }
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
        if (
            fqName in setOf("kotlin.Int.compareTo", "kotlin.Long.compareTo") &&
            arguments.size == 2 &&
            argumentExpressions.all { it.type == intType || it.type == longType } &&
            call.type == intType
        ) {
            return compileIntegerCompareTo(call, argumentExpressions, arguments)
        }
        if (
            fqName in setOf("kotlin.Int.compareTo", "kotlin.Long.compareTo", "kotlin.Float.compareTo") &&
            arguments.size == 2 &&
            argumentExpressions.any { it.type == floatType } &&
            argumentExpressions.all { it.type == intType || it.type == longType || it.type == floatType } &&
            call.type == intType
        ) {
            return compileFloatCompareTo(call, argumentExpressions, arguments)
        }
        if (
            fqName == "kotlin.String.compareTo" &&
            arguments.size == 2 &&
            argumentExpressions.all { it.type == kotlinStringType } &&
            call.type == intType
        ) {
            return compileStringCompareTo(call, arguments)
        }
        if (arguments.size == 2 && call.type == kotlinStringType && fqName == "kotlin.String.plus") {
            val right =
                if (argumentExpressions[1].type == kotlinStringType) {
                    arguments[1]
                } else {
                    val conversionType = stringValueType(argumentExpressions[1])
                    prepareAllocationBlock()
                    allocate(stringType).also { destination ->
                        emit(Instruction.StringValueOf(conversionType, destination, arguments[1]))
                    }
                }
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringConcat(it, arguments[0], right) }
        }
        if (arguments.size == 2 && argumentExpressions.all { it.type == intType || it.type == longType || it.type == floatType }) {
            if (name in setOf("plus", "minus", "times", "div", "rem")) {
                val type =
                    when (call.type) {
                        floatType -> ScalarValueType.F32
                        longType -> ScalarValueType.I64
                        else -> ScalarValueType.I32
                    }
                val valueType =
                    when (type) {
                        ScalarValueType.F32 -> ValueType.F32
                        ScalarValueType.I64 -> ValueType.I64
                        else -> ValueType.I32
                    }
                val left =
                    when (type) {
                        ScalarValueType.F32 -> widenToF32(arguments[0], argumentExpressions[0].type, call)
                        ScalarValueType.I64 -> widenToI64(arguments[0], argumentExpressions[0].type, call)
                        else -> arguments[0]
                    }
                val right =
                    when (type) {
                        ScalarValueType.F32 -> widenToF32(arguments[1], argumentExpressions[1].type, call)
                        ScalarValueType.I64 -> widenToI64(arguments[1], argumentExpressions[1].type, call)
                        else -> arguments[1]
                    }
                return result(valueType) { destination ->
                    when (name) {
                        "plus" -> Instruction.Add(type, destination, left, right)
                        "minus" -> Instruction.Subtract(type, destination, left, right)
                        "times" -> Instruction.Multiply(type, destination, left, right)
                        "div" -> Instruction.Divide(type, destination, left, right)
                        else -> Instruction.Remainder(type, destination, left, right)
                    }
                }
            }
            if (argumentExpressions[0].type == argumentExpressions[1].type && name in setOf("and", "or", "xor")) {
                val type = if (call.type == longType) ScalarValueType.I64 else ScalarValueType.I32
                val valueType = if (type == ScalarValueType.I64) ValueType.I64 else ValueType.I32
                return result(valueType) { destination ->
                    when (name) {
                        "and" -> Instruction.BitAnd(type, destination, arguments[0], arguments[1])
                        "or" -> Instruction.BitOr(type, destination, arguments[0], arguments[1])
                        else -> Instruction.BitXor(type, destination, arguments[0], arguments[1])
                    }
                }
            }
            if (argumentExpressions[1].type == intType && name in setOf("shl", "shr", "ushr")) {
                val type = if (argumentExpressions[0].type == longType) ScalarValueType.I64 else ScalarValueType.I32
                val valueType = if (type == ScalarValueType.I64) ValueType.I64 else ValueType.I32
                return result(valueType) { destination ->
                    when (name) {
                        "shl" -> Instruction.ShiftLeft(type, destination, arguments[0], arguments[1])
                        "shr" -> Instruction.ShiftRight(type, destination, arguments[0], arguments[1])
                        else -> Instruction.ShiftUnsigned(type, destination, arguments[0], arguments[1])
                    }
                }
            }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == booleanType && name == "not") {
            val falseRegister = allocate(ValueType.Bool)
            emit(Instruction.Const(falseRegister, requireNotNull(constantIds[Constant.Bool(false)])))
            return result(ValueType.Bool) { Instruction.Equal(ScalarValueType.BOOL, it, arguments[0], falseRegister) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && name == "unaryMinus") {
            val zero = emitI32Constant(0, call)
            return result(ValueType.I32) { Instruction.Subtract(it, zero, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == longType && name == "unaryMinus") {
            val zero = emitI64Constant(0, call)
            return result(ValueType.I64) { Instruction.Subtract(ScalarValueType.I64, it, zero, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == floatType && name == "unaryMinus") {
            val negativeOne = emitF32Constant(-1.0f, call)
            return result(ValueType.F32) { Instruction.Multiply(ScalarValueType.F32, it, arguments[0], negativeOne) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && name == "inv") {
            val allBits = emitI32Constant(-1, call)
            return result(ValueType.I32) { Instruction.BitXor(it, arguments[0], allBits) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == longType && name == "inv") {
            val allBits = emitI64Constant(-1, call)
            return result(ValueType.I64) { Instruction.BitXor(ScalarValueType.I64, it, arguments[0], allBits) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && call.type == charType && name == "toChar") {
            return result(ValueType.Char) { Instruction.Convert(it, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && call.type == longType && name == "toLong") {
            return result(ValueType.I64) { Instruction.Convert(it, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == longType && call.type == intType && name == "toInt") {
            return result(ValueType.I32) { Instruction.Convert(it, arguments[0]) }
        }
        if (arguments.size == 1 &&
            argumentExpressions[0].type in setOf(intType, longType) &&
            call.type == floatType &&
            name == "toFloat"
        ) {
            return result(ValueType.F32) { Instruction.Convert(it, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == floatType && call.type == intType && name == "toInt") {
            return result(ValueType.I32) { Instruction.Convert(it, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == floatType && call.type == longType && name == "toLong") {
            return result(ValueType.I64) { Instruction.Convert(it, arguments[0]) }
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
        if (arguments.size == 1 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "<get-size>" &&
            fqName == "kotlin.IntArray.<get-size>"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 1 && isSupportedReferenceArray(argumentExpressions[0].type) && name == "<get-size>") {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 2 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "get" &&
            fqName == "kotlin.CharArray.get"
        ) {
            return result(ValueType.Char) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "get" &&
            fqName == "kotlin.IntArray.get"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 && isSupportedReferenceArray(argumentExpressions[0].type) && name == "get") {
            return result(valueType(call.type, call)) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "set" &&
            fqName == "kotlin.CharArray.set"
        ) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "set" &&
            fqName == "kotlin.IntArray.set"
        ) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 && isSupportedReferenceArray(argumentExpressions[0].type) && name == "set") {
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
        if (arguments.size == 3 &&
            call.type == kotlinStringType &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            argumentExpressions[1].type == intType &&
            argumentExpressions[2].type == intType &&
            fqName == "kotlin.text.String"
        ) {
            val end = allocate(ValueType.I32)
            emit(Instruction.Add(end, arguments[1], arguments[2]))
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringFromCharArray(it, arguments[0], arguments[1], end) }
        }
        throw UnsupportedKotlinIr(call, "call target ${fqName.ifEmpty { name }} is outside the project subset")
    }

    private fun compileIntegerCompareTo(
        call: IrCall,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId {
        val mixedLong = expressions.any { it.type == longType }
        val type = if (mixedLong) OrderedScalarValueType.I64 else OrderedScalarValueType.I32
        val left = if (mixedLong) widenToI64(arguments[0], expressions[0].type, call) else arguments[0]
        val right = if (mixedLong) widenToI64(arguments[1], expressions[1].type, call) else arguments[1]
        val negative = emitI32Constant(-1, call)
        val zero = emitI32Constant(0, call)
        val positive = emitI32Constant(1, call)
        val destination = allocate(ValueType.I32)
        val less = allocate(ValueType.Bool)
        emit(Instruction.Less(type, less, left, right))
        val lessBlock = createBlock()
        val notLessBlock = createBlock()
        emit(Instruction.Branch(less, blockId(lessBlock), blockId(notLessBlock)))

        currentBlock = lessBlock
        emit(Instruction.Move(destination, negative))

        currentBlock = notLessBlock
        val greater = allocate(ValueType.Bool)
        emit(Instruction.Greater(type, greater, left, right))
        val greaterBlock = createBlock()
        val equalBlock = createBlock()
        emit(Instruction.Branch(greater, blockId(greaterBlock), blockId(equalBlock)))

        currentBlock = greaterBlock
        emit(Instruction.Move(destination, positive))

        currentBlock = equalBlock
        emit(Instruction.Move(destination, zero))

        val join = createBlock()
        listOf(lessBlock, greaterBlock, equalBlock).forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    private fun compileStringCompareTo(
        call: IrCall,
        arguments: List<RegisterId>,
    ): RegisterId {
        val leftLength = allocate(ValueType.I32)
        emit(Instruction.StringLength(leftLength, arguments[0]))
        val rightLength = allocate(ValueType.I32)
        emit(Instruction.StringLength(rightLength, arguments[1]))
        val bound = allocate(ValueType.I32)
        val zero = emitI32Constant(0, call)
        val one = emitI32Constant(1, call)
        val index = allocate(ValueType.I32)
        val leftShorter = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, leftShorter, leftLength, rightLength))
        val useLeft = createBlock()
        val useRight = createBlock()
        emit(Instruction.Branch(leftShorter, blockId(useLeft), blockId(useRight)))
        currentBlock = useLeft
        emit(Instruction.Move(bound, leftLength))
        currentBlock = useRight
        emit(Instruction.Move(bound, rightLength))

        currentBlock = useLeft
        emit(Instruction.Move(index, zero))
        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = useRight
        emit(Instruction.Move(index, zero))
        jumpTo(header)

        val destination = allocate(ValueType.I32)
        currentBlock = header
        val withinBound = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, withinBound, index, bound))
        val body = createBlock()
        val exhausted = createBlock()
        emit(Instruction.Branch(withinBound, blockId(body), blockId(exhausted)))

        currentBlock = body
        val leftChar = allocate(ValueType.Char)
        emit(Instruction.StringGet(leftChar, arguments[0], index))
        val rightChar = allocate(ValueType.Char)
        emit(Instruction.StringGet(rightChar, arguments[1], index))
        val equal = allocate(ValueType.Bool)
        emit(Instruction.Equal(ScalarValueType.CHAR, equal, leftChar, rightChar))
        val advance = createBlock()
        val different = createBlock()
        emit(Instruction.Branch(equal, blockId(advance), blockId(different)))

        currentBlock = advance
        val next = allocate(ValueType.I32)
        emit(Instruction.Add(next, index, one))
        emit(Instruction.Move(index, next))
        jumpTo(header)

        currentBlock = different
        val leftCodeUnit = allocate(ValueType.I32)
        emit(Instruction.Convert(leftCodeUnit, leftChar))
        val rightCodeUnit = allocate(ValueType.I32)
        emit(Instruction.Convert(rightCodeUnit, rightChar))
        emit(Instruction.Subtract(destination, leftCodeUnit, rightCodeUnit))

        currentBlock = exhausted
        emit(Instruction.Subtract(destination, leftLength, rightLength))

        val join = createBlock()
        currentBlock = different
        jumpTo(join)
        currentBlock = exhausted
        jumpTo(join)
        currentBlock = join
        return destination
    }

    private fun compileFloatCompareTo(
        call: IrCall,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId {
        val left = widenToF32(arguments[0], expressions[0].type, call)
        val right = widenToF32(arguments[1], expressions[1].type, call)
        val negative = emitI32Constant(-1, call)
        val zero = emitI32Constant(0, call)
        val positive = emitI32Constant(1, call)
        val oneFloat = emitF32Constant(1.0f, call)
        val destination = allocate(ValueType.I32)
        val exits = mutableListOf<Int>()

        fun complete(value: RegisterId) {
            emit(Instruction.Move(destination, value))
            exits += currentBlock
        }

        fun select(
            condition: RegisterId,
            value: RegisterId,
        ) {
            val matched = createBlock()
            val next = createBlock()
            emit(Instruction.Branch(condition, blockId(matched), blockId(next)))
            currentBlock = matched
            complete(value)
            currentBlock = next
        }

        // A Float is ordered with itself exactly when it is not NaN.
        val leftNumeric = allocate(ValueType.Bool)
        emit(Instruction.GreaterOrEqual(OrderedScalarValueType.F32, leftNumeric, left, left))
        val rightNumeric = allocate(ValueType.Bool)
        emit(Instruction.GreaterOrEqual(OrderedScalarValueType.F32, rightNumeric, right, right))
        val numericLeft = createBlock()
        val nanLeft = createBlock()
        emit(Instruction.Branch(leftNumeric, blockId(numericLeft), blockId(nanLeft)))

        currentBlock = nanLeft
        select(rightNumeric, positive)
        complete(zero)

        currentBlock = numericLeft
        val bothNumeric = createBlock()
        val nanRight = createBlock()
        emit(Instruction.Branch(rightNumeric, blockId(bothNumeric), blockId(nanRight)))
        currentBlock = nanRight
        complete(negative)

        currentBlock = bothNumeric
        val less = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.F32, less, left, right))
        select(less, negative)
        val greater = allocate(ValueType.Bool)
        emit(Instruction.Greater(OrderedScalarValueType.F32, greater, left, right))
        select(greater, positive)

        // IEEE comparisons equate both zero signs; their reciprocals retain the sign as infinity.
        val leftReciprocal = allocate(ValueType.F32)
        emit(Instruction.Divide(ScalarValueType.F32, leftReciprocal, oneFloat, left))
        val rightReciprocal = allocate(ValueType.F32)
        emit(Instruction.Divide(ScalarValueType.F32, rightReciprocal, oneFloat, right))
        val negativeZero = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.F32, negativeZero, leftReciprocal, rightReciprocal))
        select(negativeZero, negative)
        val positiveZero = allocate(ValueType.Bool)
        emit(Instruction.Greater(OrderedScalarValueType.F32, positiveZero, leftReciprocal, rightReciprocal))
        select(positiveZero, positive)
        complete(zero)

        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    private fun compileStringArrayCopyOfRange(
        call: IrCall,
        arguments: List<RegisterId>,
    ): RegisterId {
        val source = arguments[0]
        val start = arguments[1]
        val end = arguments[2]
        val length = allocate(ValueType.I32)
        emit(Instruction.Subtract(length, end, start))
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
        emit(Instruction.Add(nextSource, sourceIndex, one))
        emit(Instruction.Move(sourceIndex, nextSource))
        val nextDestination = allocate(ValueType.I32)
        emit(Instruction.Add(nextDestination, destinationIndex, one))
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
        val numeric = listOf(leftType, rightType).all { it == intType || it == longType || it == floatType }
        val mixedFloat = numeric && (leftType == floatType || rightType == floatType)
        val mixedLong =
            !mixedFloat && listOf(leftType, rightType).all { it == intType || it == longType } &&
                (leftType == longType || rightType == longType)
        val operands =
            if (mixedFloat) {
                listOf(
                    widenToF32(arguments[0], leftType, call),
                    widenToF32(arguments[1], rightType, call),
                )
            } else if (mixedLong) {
                listOf(
                    widenToI64(arguments[0], leftType, call),
                    widenToI64(arguments[1], rightType, call),
                )
            } else {
                arguments
            }
        val floatIeeeEquality =
            name.equals("ieee754Equals", ignoreCase = true) && leftType == floatType && rightType == floatType
        if (name in setOf("EQEQ", "equals", "eqeq") || floatIeeeEquality) {
            val equalityLayouts = equalityLayoutsFor(leftType)
            val hasGuestEqualityOverride =
                equalityLayouts.any { layout ->
                    val declaration = layout.declaration
                    declaration.isData ||
                        declaration.declarations.any { member ->
                            member is IrSimpleFunction && member.name.asString() == "equals" && member.body != null
                        }
                }
            if (
                leftType.isKotlinAny() || rightType.isKotlinAny() ||
                (
                    hasGuestEqualityOverride &&
                        expressions.none { it is IrConst && it.value == null } &&
                        valueType(leftType, call) is ValueType.Ref &&
                        valueType(rightType, call) is ValueType.Ref
                )
            ) {
                return compileUniversalEquality(call, operands, equalityLayouts)
            }
            return allocate(ValueType.Bool).also { destination ->
                if (leftType == kotlinStringType && rightType == kotlinStringType) {
                    emit(Instruction.StringEquals(destination, operands[0], operands[1]))
                } else if (
                    ((expressions[0] as? IrConst)?.let { it.value == null } == true && valueType(rightType, call) is ValueType.Ref) ||
                    ((expressions[1] as? IrConst)?.let { it.value == null } == true && valueType(leftType, call) is ValueType.Ref) ||
                    (valueType(leftType, call) is ValueType.Ref && valueType(rightType, call) is ValueType.Ref)
                ) {
                    emit(Instruction.RefEqual(destination, operands[0], operands[1]))
                } else {
                    val type =
                        when {
                            mixedFloat -> ScalarValueType.F32
                            mixedLong -> ScalarValueType.I64
                            else -> scalarType(leftType, call)
                        }
                    emit(Instruction.Equal(type, destination, operands[0], operands[1]))
                }
            }
        }
        val instructionFactory: (OrderedScalarValueType, RegisterId) -> Instruction =
            when (name) {
                "less" -> { type, destination -> Instruction.Less(type, destination, operands[0], operands[1]) }
                "lessOrEqual" -> { type, destination -> Instruction.LessOrEqual(type, destination, operands[0], operands[1]) }
                "greater" -> { type, destination -> Instruction.Greater(type, destination, operands[0], operands[1]) }
                "greaterOrEqual" -> { type, destination -> Instruction.GreaterOrEqual(type, destination, operands[0], operands[1]) }
                else -> return null
            }
        val orderedType =
            when {
                mixedFloat -> OrderedScalarValueType.F32
                mixedLong -> OrderedScalarValueType.I64
                else -> orderedType(leftType, call)
            }
        return allocate(ValueType.Bool).also { destination ->
            val instruction = instructionFactory(orderedType, destination)
            emit(instruction)
        }
    }

    private fun equalityLayoutsFor(type: IrType): List<GuestClassLayout> {
        val layouts = (constructorLayouts.values + genericConstructorLayouts.values).map { it.layout }.distinctBy { it.typeId }
        if (type.isKotlinAny()) return layouts
        val sourceClass = (type as? IrSimpleType)?.classifier as? IrClassSymbol ?: return layouts

        fun inheritsFrom(declaration: IrClass): Boolean =
            declaration.symbol == sourceClass ||
                declaration.superTypes.any { superType ->
                    val parent = (superType as? IrSimpleType)?.classifier as? IrClassSymbol
                    parent != null && inheritsFrom(parent.owner)
                }
        return layouts.filter { inheritsFrom(it.declaration) }
    }

    private fun compileUniversalEquality(
        call: IrCall,
        operands: List<RegisterId>,
        equalityLayouts: List<GuestClassLayout>,
    ): RegisterId {
        val anyType = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))
        val nullableAny = ValueType.Ref(nullable = true, type = anyType)
        val registerTypes = leadingParameterTypes + sourceParameters.map { valueType(it.type, it) } + localTypes
        val references =
            operands.map { operand ->
                val type = registerTypes[operand.value.toInt()]
                if (type !is ValueType.Ref) {
                    throw UnsupportedKotlinIr(call, "universal equality supports only boxed Int and references")
                }
                allocate(nullableAny).also { destination -> emit(Instruction.CheckedCast(destination, operand, anyType)) }
            }
        val same = allocate(ValueType.Bool)
        emit(Instruction.RefEqual(same, references[0], references[1]))
        val destination = allocate(ValueType.Bool)
        val exits = mutableListOf<Int>()
        val checkInt = createBlock()
        jumpTo(checkInt)

        currentBlock = checkInt
        val boxType = TypeRef.Imported(ImportId.of(INT_BOX_RUNTIME_TYPE))
        val leftInt = allocate(ValueType.Bool)
        emit(Instruction.IsType(leftInt, references[0], boxType))
        val checkRightInt = createBlock()
        val checkString = createBlock()
        emit(Instruction.Branch(leftInt, blockId(checkRightInt), blockId(checkString)))

        currentBlock = checkRightInt
        val rightInt = allocate(ValueType.Bool)
        emit(Instruction.IsType(rightInt, references[1], boxType))
        val compareInts = createBlock()
        val differentIntKinds = createBlock()
        emit(Instruction.Branch(rightInt, blockId(compareInts), blockId(differentIntKinds)))

        currentBlock = compareInts
        val intValues =
            references.map { reference ->
                val box = allocate(ValueType.Ref(nullable = false, type = boxType))
                emit(Instruction.CheckedCast(box, reference, boxType))
                allocate(ValueType.I32).also { value ->
                    emit(Instruction.FieldGet(value, box, FieldRef.Imported(ImportId.of(INT_BOX_VALUE_IMPORT))))
                }
            }
        emit(Instruction.Equal(ScalarValueType.I32, destination, intValues[0], intValues[1]))
        exits += currentBlock

        currentBlock = differentIntKinds
        emit(Instruction.Move(destination, same))
        exits += currentBlock

        currentBlock = checkString
        val stringRef = (stringType as ValueType.Ref).type
        val leftString = allocate(ValueType.Bool)
        emit(Instruction.IsType(leftString, references[0], stringRef))
        val checkRightString = createBlock()
        val nonString = createBlock()
        emit(Instruction.Branch(leftString, blockId(checkRightString), blockId(nonString)))

        currentBlock = checkRightString
        val rightString = allocate(ValueType.Bool)
        emit(Instruction.IsType(rightString, references[1], stringRef))
        val compareStrings = createBlock()
        val differentStringKinds = createBlock()
        emit(Instruction.Branch(rightString, blockId(compareStrings), blockId(differentStringKinds)))

        currentBlock = compareStrings
        val strings =
            references.map { reference ->
                allocate(stringType).also { value -> emit(Instruction.CheckedCast(value, reference, stringRef)) }
            }
        emit(Instruction.StringEquals(destination, strings[0], strings[1]))
        exits += currentBlock

        currentBlock = differentStringKinds
        emit(Instruction.Move(destination, same))
        exits += currentBlock

        currentBlock = nonString
        equalityLayouts.forEach { layout ->
            val override =
                layout.declaration.declarations
                    .filterIsInstance<IrSimpleFunction>()
                    .firstOrNull { it.name.asString() == "equals" && it.body != null }
            val overrideId = override?.let { functionIds[it.symbol] }
            if (override != null && overrideId == null && !layout.declaration.isData) {
                throw UnsupportedKotlinIr(call, "equality override is unavailable for ${layout.declaration.name}")
            }
            if (!layout.declaration.isData && overrideId == null) return@forEach
            val classRef = TypeRef.Local(layout.typeId)
            val leftMatches = allocate(ValueType.Bool)
            emit(Instruction.IsType(leftMatches, references[0], classRef))
            val matched = createBlock()
            val next = createBlock()
            emit(Instruction.Branch(leftMatches, blockId(matched), blockId(next)))

            currentBlock = matched
            val left = allocate(ValueType.Ref(nullable = false, type = classRef))
            emit(Instruction.CheckedCast(left, references[0], classRef))
            if (overrideId != null) {
                emit(Instruction.CallVirtual(Destination.Register(destination), FunctionRef.Local(overrideId), listOf(left, references[1])))
                exits += currentBlock
            } else {
                val rightMatches = allocate(ValueType.Bool)
                emit(Instruction.IsType(rightMatches, references[1], classRef))
                val compareFields = createBlock()
                val differentClass = createBlock()
                emit(Instruction.Branch(rightMatches, blockId(compareFields), blockId(differentClass)))

                currentBlock = compareFields
                val right = allocate(ValueType.Ref(nullable = false, type = classRef))
                emit(Instruction.CheckedCast(right, references[1], classRef))
                val constructorProperties =
                    layout.declaration.constructors
                        .singleOrNull { it.isPrimary }
                        ?.parameters
                        ?.filter { it.kind == IrParameterKind.Regular }
                        ?.map { it.name.asString() }
                        ?: throw UnsupportedKotlinIr(call, "data class primary constructor is unavailable for equality")
                constructorProperties.forEach { name ->
                    val field =
                        layout.fields.singleOrNull { it.property.name.asString() == name }
                            ?: throw UnsupportedKotlinIr(call, "data class equality field $name is unavailable")
                    val leftValue = allocate(field.type)
                    emit(Instruction.FieldGet(leftValue, left, FieldRef.Local(field.id)))
                    val rightValue = allocate(field.type)
                    emit(Instruction.FieldGet(rightValue, right, FieldRef.Local(field.id)))
                    val equal = allocate(ValueType.Bool)
                    when (field.type) {
                        ValueType.I32 -> emit(Instruction.Equal(ScalarValueType.I32, equal, leftValue, rightValue))
                        ValueType.I64 -> emit(Instruction.Equal(ScalarValueType.I64, equal, leftValue, rightValue))
                        ValueType.Bool -> emit(Instruction.Equal(ScalarValueType.BOOL, equal, leftValue, rightValue))
                        ValueType.Char -> emit(Instruction.Equal(ScalarValueType.CHAR, equal, leftValue, rightValue))
                        stringType -> emit(Instruction.StringEquals(equal, leftValue, rightValue))
                        else -> throw UnsupportedKotlinIr(call, "data class equality field $name needs virtual equals dispatch")
                    }
                    val nextField = createBlock()
                    val differentField = createBlock()
                    emit(Instruction.Branch(equal, blockId(nextField), blockId(differentField)))
                    currentBlock = differentField
                    emit(Instruction.Move(destination, same))
                    exits += currentBlock
                    currentBlock = nextField
                }
                emit(Instruction.Move(destination, rightMatches))
                exits += currentBlock

                currentBlock = differentClass
                emit(Instruction.Move(destination, same))
                exits += currentBlock
            }
            currentBlock = next
        }
        emit(Instruction.Move(destination, same))
        exits += currentBlock

        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    private fun compileWhile(loop: IrWhileLoop) {
        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = compileExpression(loop.condition)
        val body = createBlock()
        val branchBlock = currentBlock
        val branchIndex = blocks[branchBlock].instructions.size
        emit(Instruction.Branch(condition, blockId(body), blockId(header)))
        currentBlock = body
        val context = LoopContext(loop, continueTarget = header)
        withLoopContext(context) {
            loop.body?.let(::compileStatement)
        }
        if (!isTerminated()) jumpTo(header)
        val exit = createBlock()
        blocks[branchBlock].instructions[branchIndex] = Instruction.Branch(condition, blockId(body), blockId(exit))
        context.breakBlocks.forEach { block ->
            check(blocks[block].instructions.lastOrNull() is Instruction.Jump)
            blocks[block].instructions[blocks[block].instructions.lastIndex] = Instruction.Jump(blockId(exit))
        }
        currentBlock = exit
    }

    private fun compileForLoop(block: IrBlock) {
        val iterator = block.statements.firstOrNull() as? IrVariable
        val iteratorCall = iterator?.initializer as? IrCall
        if (iteratorCall?.targetFqName() == "kotlin.IntArray.iterator") {
            compileIntArrayForLoop(block)
        } else if (iteratorCall?.targetFqName() in
            setOf("kotlin.collections.Iterable.iterator", "kotlin.collections.List.iterator")
        ) {
            block.statements.forEach(::compileStatement)
        } else {
            compileIntForLoop(block)
        }
    }

    private fun compileIntArrayForLoop(block: IrBlock) {
        val plan = intArrayForLoopPlan(block) ?: throw UnsupportedKotlinIr(block, "unsupported canonical IntArray for-loop shape")
        val source = compileExpression(plan.array)
        val array = allocate(valueType(plan.array.type, plan.array))
        emit(Instruction.Move(array, source))
        val length = allocate(ValueType.I32)
        emit(Instruction.ArrayLength(length, array))
        val index = allocate(ValueType.I32)
        emit(Instruction.Move(index, emitI32Constant(0, block)))

        val initialHasNext = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, initialHasNext, index, length))
        val initialBranchBlock = currentBlock
        val initialBranchIndex = blocks[initialBranchBlock].instructions.size
        val body = createBlock(loopHeader = true)
        emit(Instruction.Branch(initialHasNext, blockId(body), blockId(body)))

        currentBlock = body
        val loopValue = allocate(ValueType.I32)
        values[plan.canonical.loopVariable.symbol] = loopValue
        emit(Instruction.ArrayLoad(loopValue, array, index))
        val context = LoopContext(plan.canonical.loop, continueTarget = null, placeholderTarget = body)
        withLoopContext(context) {
            compileStatement(plan.canonical.body)
        }

        val condition = createBlock()
        if (!isTerminated()) jumpTo(condition)
        context.continueBlocks.forEach { patchJumpTarget(it, condition) }
        currentBlock = condition
        val next = allocate(ValueType.I32)
        emit(Instruction.Add(next, index, emitI32Constant(1, block)))
        emit(Instruction.Move(index, next))
        val hasNext = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, hasNext, index, length))
        val repeatBranchBlock = currentBlock
        val repeatBranchIndex = blocks[repeatBranchBlock].instructions.size
        emit(Instruction.Branch(hasNext, blockId(body), blockId(body)))

        val exit = createBlock()
        blocks[initialBranchBlock].instructions[initialBranchIndex] =
            Instruction.Branch(initialHasNext, blockId(body), blockId(exit))
        blocks[repeatBranchBlock].instructions[repeatBranchIndex] =
            Instruction.Branch(hasNext, blockId(body), blockId(exit))
        context.breakBlocks.forEach { patchJumpTarget(it, exit) }
        currentBlock = exit
    }

    private fun compileIntForLoop(block: IrBlock) {
        val plan = intForLoopPlan(block) ?: throw UnsupportedKotlinIr(block, "unsupported canonical for-loop shape")
        val startValue = compileExpression(plan.start)
        val index = allocate(ValueType.I32)
        emit(Instruction.Move(index, startValue))
        val endValue = compileExpression(plan.end)
        val end = allocate(ValueType.I32)
        emit(Instruction.Move(end, endValue))
        val step = plan.step?.let(::compileExpression) ?: emitI32Constant(1, block)
        if (plan.step != null) emitPositiveStepPrecondition(step)
        val step64 = allocate(ValueType.I64)
        emit(Instruction.Convert(step64, step))
        val end64 = allocate(ValueType.I64)
        emit(Instruction.Convert(end64, end))

        val initialCondition = allocate(ValueType.Bool)
        emit(plan.condition(initialCondition, index, end, OrderedScalarValueType.I32))
        val initialBranchBlock = currentBlock
        val initialBranchIndex = blocks[initialBranchBlock].instructions.size
        val body = createBlock(loopHeader = true)
        emit(Instruction.Branch(initialCondition, blockId(body), blockId(body)))

        currentBlock = body
        val loopValue = allocate(ValueType.I32)
        values[plan.loopVariable.symbol] = loopValue
        emit(Instruction.Move(loopValue, index))
        val context = LoopContext(plan.loop, continueTarget = null, placeholderTarget = body)
        withLoopContext(context) {
            compileStatement(plan.body)
        }

        val condition = createBlock()
        if (!isTerminated()) jumpTo(condition)
        context.continueBlocks.forEach { patchJumpTarget(it, condition) }
        currentBlock = condition
        val wideIndex = allocate(ValueType.I64)
        emit(Instruction.Convert(wideIndex, index))
        val next = allocate(ValueType.I64)
        if (plan.descending) {
            emit(Instruction.Subtract(ScalarValueType.I64, next, wideIndex, step64))
        } else {
            emit(Instruction.Add(ScalarValueType.I64, next, wideIndex, step64))
        }
        val hasNext = allocate(ValueType.Bool)
        emit(plan.condition(hasNext, next, end64, OrderedScalarValueType.I64))
        val conditionBlock = currentBlock
        val conditionBranchIndex = blocks[conditionBlock].instructions.size
        val repeat = createBlock()
        emit(Instruction.Branch(hasNext, blockId(repeat), blockId(repeat)))
        currentBlock = repeat
        val narrowNext = allocate(ValueType.I32)
        emit(Instruction.Convert(narrowNext, next))
        emit(Instruction.Move(index, narrowNext))
        jumpTo(body)
        val exit = createBlock()
        blocks[initialBranchBlock].instructions[initialBranchIndex] =
            Instruction.Branch(initialCondition, blockId(body), blockId(exit))
        blocks[conditionBlock].instructions[conditionBranchIndex] =
            Instruction.Branch(hasNext, blockId(repeat), blockId(exit))
        context.breakBlocks.forEach { patchJumpTarget(it, exit) }
        currentBlock = exit
    }

    private fun emitPositiveStepPrecondition(step: RegisterId) {
        val positive = allocate(ValueType.Bool)
        emit(Instruction.Greater(OrderedScalarValueType.I32, positive, step, emitI32Constant(0, function)))
        val failure = createBlock()
        val success = createBlock()
        emit(Instruction.Branch(positive, blockId(success), blockId(failure)))
        currentBlock = failure
        prepareAllocationBlock()
        val exceptionType = TypeRef.Imported(ImportId.of(3u))
        val exception = allocate(ValueType.Ref(nullable = false, type = exceptionType))
        emit(Instruction.NewObject(exception, exceptionType))
        emit(Instruction.Throw(exception))
        currentBlock = success
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun intForLoopPlan(block: IrBlock): IntForLoopPlan? {
        if (block.statements.size != 2) return null
        val iterator = block.statements[0] as? IrVariable ?: return null
        if (iterator.origin.toString() != "FOR_LOOP_ITERATOR") return null
        val iteratorCall = iterator.initializer as? IrCall ?: return null
        if (iteratorCall.targetFqName() !in setOf("kotlin.ranges.IntRange.iterator", "kotlin.ranges.IntProgression.iterator")) return null
        val progression = iteratorCall.arguments.filterNotNull().singleOrNull() as? IrCall ?: return null
        val stepArguments =
            if (progression.targetFqName() == "kotlin.ranges.step") progression.arguments.filterNotNull() else emptyList()
        val step =
            if (stepArguments.isNotEmpty()) {
                if (stepArguments.size != 2 || stepArguments[1].type != intType) return null
                stepArguments[1]
            } else {
                null
            }
        val rangeCall = (stepArguments.firstOrNull() ?: progression) as? IrCall ?: return null
        val rangeFunction = rangeCall.targetFqName()
        val (inclusive, descending) =
            when (rangeFunction) {
                "kotlin.ranges.rangeTo" -> true to false
                "kotlin.ranges.rangeUntil", "kotlin.ranges.until" -> false to false
                "kotlin.ranges.downTo" -> true to true
                else -> return null
            }
        val bounds = rangeCall.arguments.filterNotNull()
        if (bounds.size != 2 || bounds.any { it.type != intType }) return null

        val canonical = canonicalIntForLoopBody(block, iterator) ?: return null
        return IntForLoopPlan(canonical.loop, canonical.loopVariable, bounds[0], bounds[1], inclusive, descending, step, canonical.body)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun intArrayForLoopPlan(block: IrBlock): IntArrayForLoopPlan? {
        if (block.statements.size != 2) return null
        val iterator = block.statements[0] as? IrVariable ?: return null
        if (iterator.origin.toString() != "FOR_LOOP_ITERATOR") return null
        val iteratorCall = iterator.initializer as? IrCall ?: return null
        if (iteratorCall.targetFqName() != "kotlin.IntArray.iterator") return null
        val array = iteratorCall.arguments.filterNotNull().singleOrNull() ?: return null
        if (!array.type.isExactClass(kotlinIntArrayClass)) return null
        val canonical = canonicalIntForLoopBody(block, iterator) ?: return null
        return IntArrayForLoopPlan(canonical, array)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun canonicalIntForLoopBody(
        block: IrBlock,
        iterator: IrVariable,
    ): CanonicalIntForLoopBody? {
        val loop = block.statements[1] as? IrWhileLoop ?: return null
        if (loop.origin?.toString() != "FOR_LOOP_INNER_WHILE") return null
        val hasNext = loop.condition as? IrCall ?: return null
        if (hasNext.targetFqName() != "kotlin.collections.IntIterator.hasNext") return null
        val iteratorRead = hasNext.arguments.filterNotNull().singleOrNull() as? IrGetValue ?: return null
        if (iteratorRead.symbol !== iterator.symbol) return null

        val loopBody = loop.body as? IrBlock ?: return null
        if (loopBody.statements.size != 2) return null
        val loopVariable = loopBody.statements[0] as? IrVariable ?: return null
        if (loopVariable.origin.toString() != "FOR_LOOP_VARIABLE" || loopVariable.type != intType) return null
        val nextCall = loopVariable.initializer as? IrCall ?: return null
        if (nextCall.targetFqName() != "kotlin.collections.IntIterator.next") return null
        val nextReceiver = nextCall.arguments.filterNotNull().singleOrNull() as? IrGetValue ?: return null
        if (nextReceiver.symbol !== iterator.symbol) return null
        val body = loopBody.statements[1] as? IrExpression ?: return null
        return CanonicalIntForLoopBody(loop, loopVariable, body)
    }

    private fun compileLoopJump(
        jump: IrBreakContinue,
        breakJump: Boolean,
    ) {
        val context = loopContexts.lastOrNull()
        if (context == null || jump.loop !== context.loop) {
            throw UnsupportedKotlinIr(jump, "outer loop jump is not supported")
        }
        if (breakJump) {
            context.breakBlocks += currentBlock
            jumpTo(context.placeholderTarget)
        } else {
            val target = context.continueTarget
            if (target == null) {
                context.continueBlocks += currentBlock
                jumpTo(context.placeholderTarget)
            } else {
                jumpTo(target)
            }
        }
    }

    private fun patchJumpTarget(
        block: Int,
        target: Int,
    ) {
        check(blocks[block].instructions.lastOrNull() is Instruction.Jump)
        blocks[block].instructions[blocks[block].instructions.lastIndex] = Instruction.Jump(blockId(target))
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrCall.targetFqName(): String? =
        symbol.owner
            .fqNameWhenAvailable
            ?.asString()

    private fun resolveFieldAccessor(
        symbol: IrSimpleFunctionSymbol,
        fields: Map<IrSimpleFunctionSymbol, GuestFieldLayout>,
    ): GuestFieldLayout? = fields[symbol] ?: symbol.owner.overriddenSymbols.firstNotNullOfOrNull { resolveFieldAccessor(it, fields) }

    private fun resolveClassInstance(type: IrType?): GuestClassInstance? {
        if (type == null) return null
        val resolved =
            currentClassInstance?.substitute(currentInstance?.substitute(type) ?: type)
                ?: currentInstance?.substitute(type)
                ?: type
        return resolved.classInstance(classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration })
    }

    private fun projectFunctionId(
        call: IrCall,
        target: IrSimpleFunction,
    ): FunctionId? {
        if (target.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
            val receiver = resolveClassInstance(call.dispatchReceiver?.type)

            fun overridden(function: IrSimpleFunction): FunctionId? =
                function.overriddenSymbols.firstNotNullOfOrNull { symbol ->
                    val base = symbol.owner
                    val owner = base.parent as? IrClass
                    val specialized =
                        if (owner != null && receiver != null && owner.typeParameters.size == receiver.arguments.size) {
                            genericMemberFunctionIds[base.symbol to GuestClassInstance(owner, receiver.arguments)]
                        } else {
                            null
                        }
                    specialized ?: functionIds[base.symbol] ?: overridden(base)
                }
            overridden(target)?.let { return it }
        }
        return if (target.typeParameters.isNotEmpty()) {
            val instance = projectFunctionInstance(call, target) ?: return null
            genericFunctionIds[instance]
                ?: throw UnsupportedKotlinIr(call, "generic function specialization is missing")
        } else if ((target.parent as? IrClass)?.typeParameters?.isNotEmpty() == true) {
            if (genericMemberFunctionIds.keys.none { it.first == target.symbol }) return null
            val owner =
                resolveClassInstance(call.dispatchReceiver?.type)
                    ?: throw UnsupportedKotlinIr(call, "generic method receiver has no concrete class instance")
            genericMemberFunctionIds[target.symbol to owner]
                ?: throw UnsupportedKotlinIr(call, "generic method specialization is missing")
        } else {
            functionIds[target.symbol]
                ?: target
                    .takeIf { it.origin == IrDeclarationOrigin.FAKE_OVERRIDE && it.correspondingPropertySymbol != null }
                    ?.overriddenSymbols
                    ?.firstNotNullOfOrNull { functionIds[it] }
        }
    }

    private fun projectFunctionInstance(
        call: IrCall,
        target: IrSimpleFunction,
    ): GuestFunctionInstance? {
        if (target.typeParameters.isEmpty() || genericFunctionIds.keys.none { it.declaration.symbol == target.symbol }) return null
        val arguments =
            call.typeArguments.map { argument ->
                val type = argument ?: throw UnsupportedKotlinIr(call, "generic call has an inferred type hole")
                currentInstance?.substitute(type) ?: type
            }
        return GuestFunctionInstance(target, arguments)
    }

    private inline fun withLoopContext(
        context: LoopContext,
        action: () -> Unit,
    ) {
        loopContexts.addLast(context)
        try {
            action()
        } finally {
            check(loopContexts.removeLast() === context)
        }
    }

    private fun compileWhenStatement(expression: IrWhen) {
        val exits = mutableListOf<Int>()
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall() && function.returnType == unitType) {
                    emit(Instruction.Return(Destination.Unit))
                } else {
                    compileStatement(branch.result)
                }
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
        val resultType = valueType(expression.type, expression)
        val destination = allocate(resultType)
        val exits = mutableListOf<Int>()
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall()) {
                    emitImpossibleWhenDefault(destination, resultType, branch.result)
                } else if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    val source = coerceLocalValue(compileExpression(branch.result, expression.type), expression.type, branch.result)
                    emit(Instruction.Move(destination, source))
                }
            } else {
                val condition = compileExpression(branch.condition)
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    val source = coerceLocalValue(compileExpression(branch.result, expression.type), expression.type, branch.result)
                    emit(Instruction.Move(destination, source))
                    exits += currentBlock
                }
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

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrExpression.isNoWhenBranchMatchedCall(): Boolean =
        (this as? IrCall)
            ?.symbol
            ?.owner
            ?.fqNameWhenAvailable
            ?.asString() ==
            "kotlin.internal.ir.noWhenBranchMatchedException"

    private fun emitImpossibleWhenDefault(
        destination: RegisterId,
        type: ValueType,
        element: IrElement,
    ) {
        val constant =
            when (type) {
                ValueType.I32 -> Constant.I32(0)
                ValueType.I64 -> Constant.I64(0)
                ValueType.F32 -> Constant.F32(0u)
                ValueType.Bool -> Constant.Bool(false)
                else -> throw UnsupportedKotlinIr(element, "exhaustive when fallback has an unsupported result type")
            }
        emit(Instruction.Const(destination, requireNotNull(constantIds[constant])))
    }

    private fun compileBlockValue(
        block: IrBlock,
        expectedType: IrType? = null,
    ): RegisterId {
        val result =
            block.statements.lastOrNull() as? IrExpression
                ?: throw UnsupportedKotlinIr(block, "value block has no result expression")
        block.statements.dropLast(1).forEach(::compileStatement)
        return compileExpression(result, expectedType ?: block.type)
    }

    private fun trustedIntrinsic(function: IrSimpleFunction): LoweredCapabilityOperation? = resolveTrustedIntrinsic(function, session)

    private fun resolvedType(type: IrType): IrType =
        currentClassInstance?.substitute(currentInstance?.substitute(type) ?: type)
            ?: currentInstance?.substitute(type)
            ?: type

    private fun isSupportedReferenceArray(type: IrType): Boolean {
        val resolved = resolvedType(type)
        return guestTypes.isStringArray(resolved) || guestTypes.referenceArrayType(resolved) != null
    }

    private fun valueType(
        type: IrType,
        element: IrElement,
    ): ValueType = valueTypeResolved(resolvedType(type), element)

    private fun valueTypeResolved(
        type: IrType,
        element: IrElement,
    ): ValueType =
        mapGuestValueType(
            type,
            pluginContext,
            guestTypes,
            stringType,
            charArrayType,
            stringArrayType,
            classTypeIds,
            externalClassTypes,
            inlineValueClasses,
            platformScalars,
            element,
            functionTypes,
            classInstanceTypeIds = classInstanceTypeIds,
            resolveUnderlyingType = ::resolvedType,
        )

    private fun destinationFor(
        type: IrType,
        element: IrElement,
    ): Destination =
        if (type == unitType || type.isNothing()) Destination.Unit else Destination.Register(allocate(valueType(type, element)))

    private fun scalarType(
        type: IrType,
        element: IrElement,
    ): ScalarValueType =
        when (valueType(type, element)) {
            ValueType.I32 -> ScalarValueType.I32
            ValueType.I64 -> ScalarValueType.I64
            ValueType.F32 -> ScalarValueType.F32
            ValueType.Bool -> ScalarValueType.BOOL
            ValueType.Char -> ScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported equality operand")
        }

    private fun orderedType(
        type: IrType,
        element: IrElement,
    ): OrderedScalarValueType =
        when (valueType(type, element)) {
            ValueType.I32 -> OrderedScalarValueType.I32
            ValueType.I64 -> OrderedScalarValueType.I64
            ValueType.F32 -> OrderedScalarValueType.F32
            ValueType.Char -> OrderedScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported ordered-comparison operand")
        }

    private fun allocate(type: ValueType): RegisterId =
        RegisterId
            .of((leadingParameterTypes.size + sourceParameters.size + localTypes.size).toUInt())
            .also { localTypes += type }

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
            this is Instruction.Unreachable ||
            this is Instruction.CallSuspend ||
            this is Instruction.CapabilityCallAsync

    private data class MutableBlock(
        var loopHeaderSafepoint: Boolean = false,
        val instructions: MutableList<Instruction> = mutableListOf(),
    )

    private data class LoopContext(
        val loop: IrLoop,
        val continueTarget: Int?,
        val placeholderTarget: Int = requireNotNull(continueTarget),
        val breakBlocks: MutableList<Int> = mutableListOf(),
        val continueBlocks: MutableList<Int> = mutableListOf(),
    )

    private data class IntForLoopPlan(
        val loop: IrWhileLoop,
        val loopVariable: IrVariable,
        val start: IrExpression,
        val end: IrExpression,
        val inclusive: Boolean,
        val descending: Boolean,
        val step: IrExpression?,
        val body: IrExpression,
    ) {
        fun condition(
            destination: RegisterId,
            value: RegisterId,
            bound: RegisterId,
            type: OrderedScalarValueType,
        ): Instruction =
            when {
                descending -> Instruction.GreaterOrEqual(type, destination, value, bound)
                inclusive -> Instruction.LessOrEqual(type, destination, value, bound)
                else -> Instruction.Less(type, destination, value, bound)
            }
    }

    private data class CanonicalIntForLoopBody(
        val loop: IrWhileLoop,
        val loopVariable: IrVariable,
        val body: IrExpression,
    )

    private data class IntArrayForLoopPlan(
        val canonical: CanonicalIntForLoopBody,
        val array: IrExpression,
    )
}

private fun IrType.isExactClass(symbol: IrClassSymbol): Boolean = (this as? IrSimpleType)?.classifier == symbol

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrType.isKotlinAny(): Boolean =
    ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString() == "kotlin.Any"

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrType.guestFunctionShape(): GuestFunctionShape? {
    val simple = this as? IrSimpleType ?: return null
    if (simple.isNullable()) return null
    val owner = (simple.classifier as? IrClassSymbol)?.owner ?: return null
    val name = owner.fqNameWhenAvailable?.asString() ?: return null
    val arity =
        when {
            name.startsWith("kotlin.Function") -> name.removePrefix("kotlin.Function").toIntOrNull()
            name.startsWith("kotlin.reflect.KFunction") -> name.removePrefix("kotlin.reflect.KFunction").toIntOrNull()
            else -> null
        } ?: return null
    val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return null }
    if (arguments.size != arity + 1) return null
    return GuestFunctionShape(arguments.dropLast(1), arguments.last())
}

private fun Map<GuestFunctionShape, TypeRef.Local>.forType(type: IrType): TypeRef.Local? = type.guestFunctionShape()?.let(::get)

private fun functionShapeName(
    index: Int,
    shape: GuestFunctionShape,
    unitBlockShape: GuestFunctionShape,
): String = if (shape == unitBlockShape) "kotlin.Function0<Unit>" else "app.<function-shape-$index>"

private fun IrExpression.boundReferenceReceiver(target: IrSimpleFunction): IrExpression? =
    when (this) {
        is IrRichFunctionReference -> {
            boundValues.singleOrNull()
        }

        is IrFunctionReference -> {
            val dispatchIndex = target.parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
            arguments.getOrNull(dispatchIndex)?.takeIf { arguments.count { it != null } == 1 }
        }

        else -> {
            null
        }
    }

private fun IrExpression.constructorReferenceTarget(): IrConstructorSymbol? =
    when (this) {
        is IrRichFunctionReference -> (reflectionTargetSymbol?.owner as? IrConstructor)?.symbol
        is IrFunctionReference -> ((reflectionTarget?.owner ?: symbol.owner) as? IrConstructor)?.symbol
        else -> null
    }

private fun IrSimpleFunction.isDirectFieldAccessor(): Boolean =
    origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR && modality == Modality.FINAL && overriddenSymbols.isEmpty()

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun collectGuestClosures(functions: List<IrElement>): List<GuestClosureSource> {
    val expressions = mutableListOf<IrExpression>()
    val collector =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                expressions += expression
                super.visitFunctionExpression(expression)
            }

            override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                val target = expression.reflectionTargetSymbol?.owner as? IrSimpleFunction
                if ((target?.parent is IrFile && expression.boundValues.isEmpty()) ||
                    target?.parent is IrClass ||
                    (expression.constructorReferenceTarget() != null && expression.boundValues.isEmpty())
                ) {
                    expressions += expression
                }
                super.visitRichFunctionReference(expression)
            }

            override fun visitFunctionReference(expression: IrFunctionReference) {
                val target = (expression.reflectionTarget?.owner ?: expression.symbol.owner) as? IrSimpleFunction
                if ((target?.parent is IrFile && expression.arguments.all { it == null }) ||
                    (
                        target?.parent is IrClass &&
                            (expression.boundReferenceReceiver(target) != null || expression.arguments.all { it == null })
                    ) ||
                    (expression.constructorReferenceTarget() != null && expression.arguments.all { it == null })
                ) {
                    expressions += expression
                }
                super.visitFunctionReference(expression)
            }
        }
    functions.forEach { it.accept(collector, null) }
    return expressions.mapIndexed { ordinal, expression ->
        val referenceTarget =
            when (expression) {
                is IrRichFunctionReference -> expression.reflectionTargetSymbol?.owner as? IrSimpleFunction
                is IrFunctionReference -> (expression.reflectionTarget?.owner ?: expression.symbol.owner) as? IrSimpleFunction
                else -> null
            }
        if (referenceTarget != null) {
            val shape = expression.type.guestFunctionShape()
            val boundReceiver = expression.boundReferenceReceiver(referenceTarget)
            val instanceMethod = referenceTarget.parent is IrClass
            val dispatchReceiver = referenceTarget.parameters.singleOrNull { it.kind == IrParameterKind.DispatchReceiver }
            val sourceParameters =
                listOfNotNull(dispatchReceiver?.type?.takeIf { instanceMethod && boundReceiver == null }) +
                    referenceTarget.parameters.filter { it.kind == IrParameterKind.Regular }.map { it.type }
            if (expression is IrRichFunctionReference &&
                (expression.hasUnitConversion || expression.hasSuspendConversion || expression.hasVarargConversion)
            ) {
                throw UnsupportedKotlinIr(expression, "adapted function references are not supported")
            }
            if (shape == null || referenceTarget.isSuspend ||
                referenceTarget.returnType != shape.result ||
                sourceParameters != shape.parameters ||
                (instanceMethod && dispatchReceiver == null) ||
                referenceTarget.parameters.any {
                    it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.Regular
                }
            ) {
                throw UnsupportedKotlinIr(expression, "function reference signature is not supported")
            }
            return@mapIndexed GuestClosureSource(expression, null, referenceTarget, null, boundReceiver, ordinal, emptyList())
        }
        expression.constructorReferenceTarget()?.let { constructorSymbol ->
            val constructor = constructorSymbol.owner
            val shape = expression.type.guestFunctionShape()
            val parameters = constructor.parameters.filter { it.kind == IrParameterKind.Regular }.map { it.type }
            if (shape == null || !constructor.isPrimary ||
                constructor.returnType != shape.result ||
                constructor.parameters.any { it.kind != IrParameterKind.Regular } ||
                (
                    expression is IrRichFunctionReference &&
                        (expression.hasUnitConversion || expression.hasSuspendConversion || expression.hasVarargConversion)
                )
            ) {
                throw UnsupportedKotlinIr(expression, "constructor reference signature is not supported")
            }
            if (parameters == shape.parameters) {
                return@mapIndexed GuestClosureSource(expression, null, null, constructorSymbol, null, ordinal, emptyList())
            }
            val adapter = (expression as? IrFunctionReference)?.symbol?.owner as? IrSimpleFunction
            val adapterCall =
                ((adapter?.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn)?.value as? IrConstructorCall
            val adaptedParameters = adapter?.parameters.orEmpty()
            val supplied = adapterCall?.arguments?.filterNotNull().orEmpty()
            if (adapter == null || adapter.isSuspend || adapter.typeParameters.isNotEmpty() ||
                adapter.returnType != shape.result ||
                adaptedParameters.any { it.kind != IrParameterKind.Regular } ||
                adaptedParameters.map { it.type } != shape.parameters ||
                adapterCall?.symbol != constructorSymbol ||
                adapterCall.arguments.size != constructor.parameters.size ||
                supplied.map { (it as? IrGetValue)?.symbol } != adaptedParameters.map { it.symbol } ||
                adapterCall.arguments.count { it == null } == 0 ||
                adapterCall.arguments.drop(supplied.size).any { it != null } ||
                constructor.parameters.indices.any { index ->
                    adapterCall.arguments[index] == null && constructor.parameters[index].defaultValue == null
                }
            ) {
                throw UnsupportedKotlinIr(expression, "constructor reference adaptation is not supported")
            }
            return@mapIndexed GuestClosureSource(expression, adapter, null, null, null, ordinal, emptyList())
        }
        val function =
            (expression as? IrFunctionExpression)?.function
                ?: throw UnsupportedKotlinIr(expression, "unsupported function reference")
        val shape = expression.type.guestFunctionShape()
        if (shape == null ||
            function.isSuspend ||
            function.returnType != shape.result ||
            function.parameters.map { it.type } != shape.parameters
        ) {
            throw UnsupportedKotlinIr(expression, "only non-suspending function values with supported signatures are admitted")
        }
        val owned: MutableSet<IrValueSymbol> = function.parameters.mapTo(mutableSetOf()) { it.symbol }
        function.body?.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitVariable(declaration: IrVariable) {
                    owned += declaration.symbol
                    super.visitVariable(declaration)
                }

                override fun visitFunctionExpression(expression: IrFunctionExpression) {
                    owned += expression.function.parameters.map { it.symbol }
                    super.visitFunctionExpression(expression)
                }
            },
            null,
        )
        val captures = linkedMapOf<IrValueSymbol, IrValueDeclaration>()
        function.body?.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitGetValue(expression: IrGetValue) {
                    if (expression.symbol !in owned) {
                        val declaration = expression.symbol.owner
                        captures.putIfAbsent(expression.symbol, declaration)
                    }
                    super.visitGetValue(expression)
                }

                override fun visitSetValue(expression: IrSetValue) {
                    if (expression.symbol !in owned) {
                        captures.putIfAbsent(expression.symbol, expression.symbol.owner)
                    }
                    super.visitSetValue(expression)
                }
            },
            null,
        )
        GuestClosureSource(expression, function, null, null, null, ordinal, captures.values.toList())
    }
}

private fun loweredParameters(
    function: IrSimpleFunction,
    session: CompilationSession,
) = if (
    (function.parent as? IrClass)?.fqNameWhenAvailable?.asString() in
    setOf("kotlin.collections.Iterable", "kotlin.collections.Iterator", "kotlin.collections.Collection", "kotlin.collections.List")
) {
    function.parameters
} else if (
    session.platformFunctions.any { link ->
        link.symbol == function.fqNameWhenAvailable?.asString() && link.signature == function.canonicalPlatformSignature()
    }
) {
    if ((function.parent as? IrClass)?.kind == ClassKind.OBJECT) {
        function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
    } else {
        function.parameters
    }
} else if (
    session.trustedPlatformModule(function.file.fileEntry.name) != null &&
    (function.parent as? IrClass)?.kind == ClassKind.OBJECT
) {
    function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
} else {
    function.parameters
}

private fun IrSimpleFunction.canonicalPlatformSignature(): String {
    val receiver =
        parameters
            .singleOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?.type
            ?.canonicalPlatformType()
            ?.plus(".")
            .orEmpty()
    val regularParameters =
        parameters
            .filter { it.kind == IrParameterKind.Regular }
            .joinToString(",") { it.type.canonicalPlatformType() }
    return "fun($receiver$regularParameters):${returnType.canonicalPlatformType()}"
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class LiteralCollector(
    private val unitType: IrType,
    private val longType: IrType,
    private val floatType: IrType,
) : IrVisitorVoid() {
    val values = mutableListOf<Any>()
    var usesLong: Boolean = false
        private set
    var usesFloat: Boolean = false
        private set
    val strings: List<String>
        get() = values.filterIsInstance<String>()

    override fun visitElement(element: IrElement) {
        if (element is IrExpression && element.type == longType) usesLong = true
        if (element is IrExpression && element.type == floatType) usesFloat = true
        element.acceptChildren(this, null)
    }

    override fun visitConst(expression: IrConst) {
        expression.value
            ?.takeIf { it is String || it is Int || it is Long || it is Float || it is Boolean || it is Char }
            ?.let(values::add)
        super.visitConst(expression)
    }

    override fun visitBlock(expression: IrBlock) {
        if (expression.origin?.toString() == "FOR_LOOP") values += 1
        super.visitBlock(expression)
    }

    override fun visitCall(expression: IrCall) {
        val fqName =
            expression.symbol.owner.fqNameWhenAvailable
                ?.asString()
        if (fqName == "kotlin.Boolean.not") {
            values += false
        }
        floatCompanionConstant(expression.symbol.owner)?.let(values::add)
        if (fqName == "kotlin.emptyArray" || fqName == "kotlin.collections.emptyList") {
            values += 0
        } else if (fqName == "kotlin.arrayOf" || fqName == "kotlin.intArrayOf" || fqName == "kotlin.collections.listOf") {
            val size = (expression.arguments.filterNotNull().singleOrNull() as? IrVararg)?.elements?.size
            if (size != null) {
                values.addAll(0..size)
            } else if (fqName == "kotlin.collections.listOf") {
                values += 0
            }
        } else if (fqName == "kotlin.collections.copyOfRange") {
            values.addAll(listOf(0, 1))
        }
        super.visitCall(expression)
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation) {
        if (expression.arguments.any { it.type == unitType }) values += "kotlin.Unit"
        super.visitStringConcatenation(expression)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun floatCompanionConstant(function: IrSimpleFunction): Float? {
    val owner = function.parent as? IrClass ?: return null
    if (owner.fqNameWhenAvailable?.asString() != "kotlin.Float.Companion") return null
    return when (function.name.asString()) {
        "<get-POSITIVE_INFINITY>" -> Float.POSITIVE_INFINITY
        "<get-NEGATIVE_INFINITY>" -> Float.NEGATIVE_INFINITY
        "<get-NaN>" -> Float.NaN
        else -> null
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

private sealed interface ReferenceArrayElement {
    data object Universal : ReferenceArrayElement

    data class GuestClass(
        val instance: GuestClassInstance,
    ) : ReferenceArrayElement
}

private class ReferenceArrayUsageCollector(
    private val guestTypes: GuestTypeRegistry,
    private val classes: Map<IrClassSymbol, IrClass>,
    private val instances: Set<GuestClassInstance>,
) {
    val arrays = linkedMapOf<String, ReferenceArrayElement>()

    fun consider(
        type: IrType,
        substitute: (IrType) -> IrType = { it },
    ) {
        val resolved = substitute(type)
        val element = guestTypes.arrayElement(resolved) ?: return
        if (element.isNullable()) return
        if (element.isKotlinAny()) {
            arrays[resolved.canonicalPlatformType()] = ReferenceArrayElement.Universal
            return
        }
        val instance = element.classInstance(classes) ?: return
        if (instance in instances) arrays[resolved.canonicalPlatformType()] = ReferenceArrayElement.GuestClass(instance)
    }

    fun scan(
        root: IrElement,
        substitute: (IrType) -> IrType = { it },
    ) {
        root.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    when (element) {
                        is IrExpression -> consider(element.type, substitute)
                        is IrValueDeclaration -> consider(element.type, substitute)
                    }
                    element.acceptChildren(this, null)
                }
            },
            null,
        )
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class IntrinsicCollector(
    private val resolve: (IrSimpleFunction) -> LoweredCapabilityOperation?,
) : IrVisitorVoid() {
    val capabilities = mutableListOf<LoweredCapabilityIdentity>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitCall(expression: IrCall) {
        resolve(expression.symbol.owner)?.capability?.let(capabilities::add)
        super.visitCall(expression)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun resolveTrustedIntrinsic(
    function: IrSimpleFunction,
    session: CompilationSession,
): LoweredCapabilityOperation? {
    var parent = function.parent
    while (parent !is IrFile) {
        parent = (parent as? IrDeclaration)?.parent ?: break
    }
    val platformModule = (parent as? IrFile)?.let { session.trustedPlatformModule(it.fileEntry.name) }
    if (parent is IrFile && platformModule == null) return null
    val fqName = function.fqNameWhenAvailable?.asString() ?: return null
    val signature = function.canonicalPlatformSignature()
    val handler =
        session.canonicalIntrinsicRegistry
            ?.handlers
            ?.entries
            ?.singleOrNull { (key, _) ->
                (platformModule?.let { key.module == it } ?: (key.module in session.selectedPlatformModules)) &&
                    key.callableId.asSingleFqName().asString() == fqName &&
                    key.signature.value == signature
            }?.value as? CapabilityOperationHandler ?: return null
    val capability = handler.requiredCapability
    return LoweredCapabilityOperation(
        LoweredCapabilityIdentity(
            capability.namespace,
            capability.name,
            capability.abiMajor.toUShort(),
            capabilityShape(capability, session).abiMinor.toUShort(),
            capabilityShape(capability, session).operationCount,
        ),
        handler.operation,
        handler.blocking,
        handler.terminal,
    )
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrType.canonicalPlatformType(): String {
    val simple = this as? IrSimpleType ?: return toString()
    val classifier = simple.classifier
    val name =
        when (classifier) {
            is IrClassSymbol -> classifier.owner.relativeClassName()
            is IrTypeParameterSymbol -> classifier.owner.name.asString()
            else -> classifier.toString()
        }
    val arguments =
        simple.arguments
            .mapNotNull { (it as? IrTypeProjection)?.type?.canonicalPlatformType() }
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = "<", postfix = ">", separator = ",")
            .orEmpty()
    return name + arguments + if (simple.isNullable()) "?" else ""
}

private fun IrClass.relativeClassName(): String =
    generateSequence(this) { declaration -> declaration.parent as? IrClass }
        .map { declaration -> declaration.name.asString() }
        .toList()
        .asReversed()
        .joinToString(".")

private fun capabilityShape(
    capability: PlatformCapabilityId,
    session: CompilationSession,
): PlatformCapabilityShape =
    session.capabilityShapes[capability]
        ?: PlatformCapabilityShape(
            0,
            when (capability.namespace to capability.name) {
                "compukter" to "terminal" -> 14u
                "compukter" to "stdio" -> 3u
                "compukter" to "process" -> 3u
                "compukter" to "filesystem" -> 7u
                "compukter" to "compiler" -> 2u
                "compukter" to "redstone" -> 8u
                "compukter" to "sound" -> 1u
                "compukters" to "display" -> 4u
                "compukter" to "timer" -> 1u
                else -> error("unknown Compukters capability ${capability.namespace}:${capability.name}")
            },
        )

private fun Any.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (this) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(this)]))
        is Int -> Constant.I32(this)
        is Long -> Constant.I64(this)
        is Float -> Constant.F32(toBits().toUInt())
        is Boolean -> Constant.Bool(this)
        is Char -> Constant.Char(code.toUShort())
        else -> error("unsupported literal $this")
    }

private fun IrConst.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (val literal = value) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(literal)]))
        is Int -> Constant.I32(literal)
        is Long -> Constant.I64(literal)
        is Float -> Constant.F32(literal.toBits().toUInt())
        is Boolean -> Constant.Bool(literal)
        is Char -> Constant.Char(literal.code.toUShort())
        else -> throw UnsupportedKotlinIr(this, "unsupported constant")
    }

private fun IrExpression.isTrueConstant(): Boolean = this is IrConst && value == true
