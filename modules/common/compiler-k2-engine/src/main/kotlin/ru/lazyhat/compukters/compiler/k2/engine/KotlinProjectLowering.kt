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
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
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
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.compiler.artifact.analysis.ExecutionStorage
import ru.lazyhat.compukters.compiler.artifact.analysis.hasHeterogeneousReferenceComparison
import ru.lazyhat.compukters.compiler.artifact.analysis.mayThrow
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryArguments
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
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
import ru.lazyhat.compukters.compiler.artifact.model.HashValueType
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
import ru.lazyhat.compukters.compiler.artifact.model.hasStructuredHostResponse
import ru.lazyhat.compukters.compiler.artifact.model.usesUnsignedSemantics
import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CapabilityOperationHandler
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.IntrinsicBlockingMode
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.PlatformCapabilityId
import ru.lazyhat.compukters.platform.bundle.PlatformDeclarationIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
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
    val singletonFieldId: FieldId? = null,
) {
    val declaration: IrClass get() = instance.declaration
}

private fun IrClass.isManagedPlatformObject(): Boolean =
    kind == ClassKind.OBJECT &&
        superTypes.any { type ->
            ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString() != "kotlin.Any"
        }

private val hashCollectionFactories =
    mapOf(
        "kotlin.collections.mapOf" to "kotlin.collections.HashMap",
        "kotlin.collections.mutableMapOf" to "kotlin.collections.HashMap",
        "kotlin.collections.setOf" to "kotlin.collections.HashSet",
        "kotlin.collections.mutableSetOf" to "kotlin.collections.HashSet",
    )

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
            return if (arguments.isEmpty()) base else "$base<${arguments.joinToString(",") { it.specializationTypeIdentity() }}>"
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
    anyType: IrType,
): List<GuestClassInstance> {
    val bySymbol = classes.associateBy { it.symbol }
    val byName = classes.associateBy { it.fqNameWhenAvailable?.asString() }
    val instances = linkedSetOf<GuestClassInstance>()
    val pending = ArrayDeque<GuestClassInstance>()

    fun add(instance: GuestClassInstance) {
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
        val local = resolved.classInstance(bySymbol)
        if (local != null) {
            add(local)
        } else {
            val simple = resolved as? IrSimpleType ?: return
            val declaration = (simple.classifier as? IrClassSymbol)?.owner ?: return
            val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return }
            if (arguments.size != declaration.typeParameters.size) return
            val imported = GuestClassInstance(declaration, arguments)
            declaration.superTypes.forEach { parent -> consider(imported.substitute(parent)) }
        }
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
                        if (elementType != null) {
                            byName["kotlin.collections.ArrayList"]?.let {
                                add(GuestClassInstance(it, listOf(elementType)))
                            }
                        }
                    }
                    hashCollectionFactories[
                        expression.symbol.owner.fqNameWhenAvailable
                            ?.asString(),
                    ]?.let { owner ->
                        val arguments = expression.typeArguments.map { it?.let(substitution) ?: return@let }
                        byName[owner]?.let { add(GuestClassInstance(it, arguments)) }
                    }
                    if (expression.symbol.owner.fqNameWhenAvailable
                            ?.asString() == "kotlin.collections.mutableListStorage"
                    ) {
                        val elementType = expression.typeArguments.singleOrNull()?.let(substitution)
                        if (elementType != null && (elementType.isNullable() || GuestPrimitive.scalar(elementType) == null)) {
                            byName["kotlin.collections.ReferenceMutableListStorage"]?.let {
                                add(GuestClassInstance(it, listOf(elementType)))
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
        // Concrete collection ownership must not depend on which read views a consumer happens to use.
        CollectionReadBridges.interfaces(instance.declaration, instance.arguments, anyType).forEach { (name, argument) ->
            byName[name]?.let { add(GuestClassInstance(it, listOf(argument))) }
        }
        instance.declaration.superTypes.forEach { consider(it, instance::substitute) }
        // Nested classes own independent type parameters and are scanned through their own instances.
        instance.declaration.declarations.filterNot { it is IrClass }.forEach { declaration ->
            scan(declaration, instance::substitute)
        }
    }
    return instances.toList()
}

private data class GuestConstructorTarget(
    val instance: GuestClassInstance,
    val ownerType: TypeRef,
    val functionId: FunctionId,
    val layout: GuestClassLayout? = null,
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
    val properties: List<IrProperty>,
    val underlyingTypes: List<IrType>,
    val intRange: InlineIntRange?,
) {
    val getter: IrSimpleFunctionSymbol get() = requireNotNull(properties.first().getter).symbol
    val underlyingType: IrType get() = underlyingTypes.first()
    val getters: List<IrSimpleFunctionSymbol> get() = properties.map { requireNotNull(it.getter).symbol }
}

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
                        !declaration.isManagedPlatformObject() &&
                        layoutsByClass[(declaration.parent as? IrClass)?.symbol]?.let { layout ->
                            layout.properties.size == 1 && layout.underlyingType in
                                listOf(
                                    pluginContext.irBuiltIns.intType,
                                    pluginContext.irBuiltIns.booleanType,
                                    pluginContext.irBuiltIns.charType,
                                )
                        } == true
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
                byGetter = layouts.flatMap { layout -> layout.getters.map { it to layout } }.toMap(),
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
            val constructor =
                declaration.constructors.singleOrNull { it.isPrimary }
                    ?: throw UnsupportedKotlinIr(declaration, "value class must have one primary constructor")
            if (declaration.constructors.any { !it.isPrimary }) {
                throw UnsupportedKotlinIr(declaration, "value class secondary constructors are not supported")
            }
            val parameters = constructor.parameters.filter { it.kind == IrParameterKind.Regular }
            if (parameters.isEmpty()) throw UnsupportedKotlinIr(declaration, "value class requires underlying properties")
            val properties =
                parameters.map { parameter ->
                    val property =
                        declaration.declarations.filterIsInstance<IrProperty>().singleOrNull {
                            it.backingField != null && it.name == parameter.name
                        } ?: throw UnsupportedKotlinIr(parameter, "value class constructor parameter requires a property")
                    if (property.isVar || property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                        throw UnsupportedKotlinIr(property, "value class underlying properties must be immutable")
                    }
                    property
                }
            val initializers = declaration.declarations.filterIsInstance<IrAnonymousInitializer>()
            val intRange =
                if (parameters.size == 1 && parameters.single().type == pluginContext.irBuiltIns.intType) {
                    when (initializers.size) {
                        0 -> null
                        1 -> parseIntRange(initializers.single(), requireNotNull(properties.single().getter).symbol)
                        else -> throw UnsupportedKotlinIr(declaration, "scalar value class supports at most one precondition")
                    }
                } else {
                    null
                }
            val unsupportedParent =
                declaration.superTypes
                    .mapNotNull { (it as? IrSimpleType)?.classifier as? IrClassSymbol }
                    .firstOrNull { it.owner.fqNameWhenAvailable?.asString() != "kotlin.Any" && it.owner.kind != ClassKind.INTERFACE }
            if (unsupportedParent != null) {
                throw UnsupportedKotlinIr(declaration, "value class custom class supertypes are not supported")
            }
            return InlineValueClassLayout(
                declaration = declaration,
                constructor = constructor.symbol,
                properties = properties,
                underlyingTypes = parameters.map { it.type },
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

private fun scalarStringForm(type: ValueType): StringValueType =
    when (type) {
        ValueType.I32 -> StringValueType.I32
        ValueType.I64 -> StringValueType.I64
        ValueType.F32 -> StringValueType.F32
        ValueType.F64 -> StringValueType.F64
        ValueType.Bool -> StringValueType.BOOL
        ValueType.Char -> StringValueType.CHAR
        else -> error("unsupported scalar string conversion: $type")
    }

private fun scalarHashForm(type: ValueType): HashValueType =
    when (type) {
        ValueType.I32 -> HashValueType.I32
        ValueType.I64 -> HashValueType.I64
        ValueType.F32 -> HashValueType.F32
        ValueType.F64 -> HashValueType.F64
        ValueType.Bool -> HashValueType.BOOL
        ValueType.Char -> HashValueType.CHAR
        else -> error("unsupported scalar hash: $type")
    }

private fun IrSimpleFunction.isGeneratedDataValueMethod(): Boolean =
    name.asString() in setOf("hashCode", "equals") && (parent as? IrClass)?.isData == true &&
        origin != IrDeclarationOrigin.DEFINED && origin != IrDeclarationOrigin.FAKE_OVERRIDE

private const val ANY_RUNTIME_TYPE = 5u
private const val INT_BOX_RUNTIME_TYPE = 6u
private const val INT_BOX_VALUE_IMPORT = 39u
private const val THROWABLE_MESSAGE_IMPORT = 40u
private const val THROWABLE_CAUSE_IMPORT = 41u
private const val INT_ARRAY_RUNTIME_TYPE = 4u
private const val DOUBLE_ARRAY_RUNTIME_TYPE = 23u
private const val DOUBLE_BOX_RUNTIME_TYPE = 20u
private const val UNIT_RUNTIME_TYPE = 22u
private const val UNIT_INSTANCE_IMPORT = 47u
private const val ANY_TO_STRING_IMPORT = 48u
private const val ANY_HASH_CODE_IMPORT = 49u
private const val ANY_EQUALS_IMPORT = 50u
private const val RUNTIME_IMPORT_COUNT = 57
private const val UNIT_INSTANCE_NAME = "kotlin.Unit.INSTANCE"
private const val ANY_TO_STRING_NAME = "kotlin.Any.toString"
private const val ANY_HASH_CODE_NAME = "kotlin.Any.hashCode"
private const val ANY_EQUALS_NAME = "kotlin.Any.equals"

private data class ScalarBox(
    val primitive: GuestPrimitive,
) {
    val type: UInt get() = primitive.boxType
    val field: UInt get() = primitive.boxFieldImport
    val fieldIndex: UInt get() = primitive.boxField
    val valueType: ValueType get() = primitive.scalar
    val name: String get() = primitive.boxFieldName
}

private val scalarBoxes = GuestPrimitive.entries.map(::ScalarBox)
private val runtimeMemberNames =
    listOf(INT_BOX_VALUE_NAME, THROWABLE_MESSAGE_NAME, THROWABLE_CAUSE_NAME) +
        scalarBoxes.drop(1).take(5).map { it.name } + UNIT_INSTANCE_NAME + ANY_TO_STRING_NAME + ANY_HASH_CODE_NAME + ANY_EQUALS_NAME +
        scalarBoxes.drop(6).map { it.name }
private const val INT_BOX_VALUE_NAME = "kotlin.Int.<boxed-value>"
private const val THROWABLE_MESSAGE_NAME = "kotlin.Throwable.message"
private const val THROWABLE_CAUSE_NAME = "kotlin.Throwable.cause"

private fun IrClass.runtimeExceptionType(): UInt? =
    when (fqNameWhenAvailable?.asString()) {
        "kotlin.Throwable" -> 2u
        "kotlin.IllegalArgumentException" -> 3u
        "kotlin.Exception" -> 7u
        "kotlin.RuntimeException" -> 8u
        "kotlin.IllegalStateException" -> 9u
        "kotlin.NoWhenBranchMatchedException" -> 10u
        "kotlin.ArithmeticException" -> 11u
        "kotlin.IndexOutOfBoundsException" -> 12u
        "kotlin.NegativeArraySizeException" -> 13u
        "kotlin.NullPointerException" -> 14u
        "kotlin.ClassCastException" -> 15u
        "compukter.io.IOException" -> 16u
        else -> null
    }

private fun throwablePropertyImport(function: IrSimpleFunction): UInt? {
    val property = function.correspondingPropertySymbol?.owner
    if ((property?.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Throwable") {
        return when (property.name.asString()) {
            "message" -> THROWABLE_MESSAGE_IMPORT
            "cause" -> THROWABLE_CAUSE_IMPORT
            else -> null
        }
    }
    return function.overriddenSymbols.firstNotNullOfOrNull { throwablePropertyImport(it.owner) }
}

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
    if (type.isNullable() && (type as? IrSimpleType)?.classifier == pluginContext.irBuiltIns.nothingClass) {
        return ValueType.Ref(true, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))
    }
    GuestPrimitive.scalar(type)?.let { primitive ->
        return if (type.isNullable()) {
            ValueType.Ref(true, TypeRef.Imported(ImportId.of(primitive.boxType)))
        } else {
            primitive.scalar
        }
    }
    GuestPrimitive.array(type)?.let { primitive ->
        return ValueType.Ref(type.isNullable(), TypeRef.Imported(ImportId.of(primitive.arrayType)))
    }
    ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.runtimeExceptionType()?.let {
        return ValueType.Ref(nullable = type.isNullable(), type = TypeRef.Imported(ImportId.of(it)))
    }
    if (type.isNullable()) {
        val stringClass = (pluginContext.irBuiltIns.stringType as IrSimpleType).classifier
        val guestClass = (type as? IrSimpleType)?.classifier as? IrClassSymbol
        val guestInstance = type.classInstance(classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration })
        if (guestTypes.valueClassBox(type) == null && !type.isKotlinAny() && guestClass != stringClass &&
            guestClass !in classTypeIds && guestClass !in externalClassTypes && guestInstance !in classInstanceTypeIds
        ) {
            throw UnsupportedKotlinIr(
                element,
                "nullable type ${type.specializationTypeIdentity()} is outside the supported reference subset",
            )
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

        pluginContext.irBuiltIns.doubleType -> {
            ValueType.F64
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
            } else if (type.isExactClass(pluginContext.irBuiltIns.doubleArray)) {
                ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(DOUBLE_ARRAY_RUNTIME_TYPE)))
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
                    if (type.isNullable()) return ValueType.Ref(true, requireNotNull(guestTypes.valueClassBox(type)).type)
                    when (platformScalar) {
                        PlatformScalarRepresentation.INT -> ValueType.I32
                        PlatformScalarRepresentation.BOOLEAN -> ValueType.Bool
                        PlatformScalarRepresentation.CHAR -> ValueType.Char
                    }
                } else if (inline != null) {
                    if (type.isNullable()) return ValueType.Ref(true, requireNotNull(guestTypes.valueClassBox(type)).type)
                    guestTypes.valueClassBox(type)?.takeIf { it.inlineType != null }?.let { return requireNotNull(it.inlineType) }
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
                    throw UnsupportedKotlinIr(element, "unsupported value type: ${type.specializationTypeIdentity()}")
                }
            } else {
                throw UnsupportedKotlinIr(element, "unsupported value type: ${type.specializationTypeIdentity()}")
            }
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object KotlinProjectLowering {
    private const val MAXIMUM_TASKS = 64u
    private const val APPLICATION_STATE = "app.<state>"
    private const val CHAR_ARRAY_RUNTIME_TYPE = 0u
    private const val STRING_RUNTIME_TYPE = 1u
    private val runtimeTypeNames =
        listOf(
            "kotlin.CharArray",
            "kotlin.String",
            "kotlin.Throwable",
            "kotlin.IllegalArgumentException",
            "kotlin.IntArray",
            "kotlin.Any",
            "kotlin.Int",
            "kotlin.Exception",
            "kotlin.RuntimeException",
            "kotlin.IllegalStateException",
            "kotlin.NoWhenBranchMatchedException",
            "kotlin.ArithmeticException",
            "kotlin.IndexOutOfBoundsException",
            "kotlin.NegativeArraySizeException",
            "kotlin.NullPointerException",
            "kotlin.ClassCastException",
            "compukter.io.IOException",
            "kotlin.Boolean",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.Char",
            "kotlin.Unit",
            "kotlin.DoubleArray",
        ) + scalarBoxes.drop(6).map { it.primitive.qualifiedName } +
            GuestPrimitive.entries
                .filter { it.arrayType >= 30u }
                .sortedBy { it.arrayType }
                .map { it.arrayName }

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

        fun isCollectionSource(declaration: IrDeclaration): Boolean {
            var parent = declaration.parent
            while (parent is IrDeclaration) parent = parent.parent
            val file = parent as? IrFile ?: return false
            return file.packageFqName == FqName("kotlin.collections") &&
                session.virtualSourcePath(file.fileEntry.name) in session.sourcePlatformPaths
        }
        val scannedCollectionHelpers = mutableSetOf<IrSimpleFunctionSymbol>()
        val collectionUsage =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    if (!usesListFactory) element.acceptChildren(this, null)
                }

                override fun visitConstructorCall(expression: IrConstructorCall) {
                    if (isCollectionSource(expression.symbol.owner.parentAsClass)) {
                        usesListFactory = true
                    }
                    super.visitConstructorCall(expression)
                }

                override fun visitCall(expression: IrCall) {
                    val target = expression.symbol.owner
                    if (target.fqNameWhenAvailable?.asString() in hashCollectionFactories ||
                        target.fqNameWhenAvailable?.asString() in
                        setOf("kotlin.collections.listOf", "kotlin.collections.emptyList", "kotlin.collections.mutableListStorage")
                    ) {
                        usesListFactory = true
                    } else if (target.body != null && isCollectionSource(target) && scannedCollectionHelpers.add(target.symbol)) {
                        target.body?.accept(this, null)
                    }
                    super.visitCall(expression)
                }
            }
        (functions + properties).filterNot(::isCollectionSource).forEach { it.accept(collectionUsage, null) }
        val collectionInterfaceClasses =
            specializedCollectionInterfaces.mapNotNull { name ->
                val classId =
                    when (name) {
                        "kotlin.collections.Map.Entry" -> {
                            ClassId
                                .topLevel(FqName("kotlin.collections.Map"))
                                .createNestedClassId(Name.identifier("Entry"))
                        }

                        "kotlin.collections.MutableMap.MutableEntry" -> {
                            ClassId
                                .topLevel(FqName("kotlin.collections.MutableMap"))
                                .createNestedClassId(Name.identifier("MutableEntry"))
                        }

                        else -> {
                            ClassId.topLevel(FqName(name))
                        }
                    }
                pluginContext.referenceClass(classId)?.owner
            }
        val sourceClasses =
            (classes + collectionInterfaceClasses)
                .distinctBy { it.symbol }
                .filterNot { it.runtimeExceptionType() != null }
                .filterNot { includeTrustedPlatformBodies && it.kind == ClassKind.OBJECT && !it.isManagedPlatformObject() }
                .filterNot { declaration ->
                    !includeTrustedPlatformBodies && !usesListFactory &&
                        declaration !in collectionInterfaceClasses &&
                        isCollectionSource(declaration)
                }.filterNot {
                    !includeTrustedPlatformBodies &&
                        it.fqNameWhenAvailable?.asString() !in specializedCollectionInterfaces &&
                        it.fqNameWhenAvailable?.asString() !in session.sourcePlatformSymbols &&
                        session.trustedPlatformModule(it.file.fileEntry.name) != null
                }.filterNot { declaration ->
                    !includeTrustedPlatformBodies && session.platformTypes.any { it.symbol == declaration.fqNameWhenAvailable?.asString() }
                }.filterNot { declaration ->
                    !includeTrustedPlatformBodies && declaration !in collectionInterfaceClasses &&
                        session.virtualSourcePath(declaration.file.fileEntry.name) in session.sourcePlatformPaths &&
                        declaration.fqNameWhenAvailable?.asString() !in session.sourcePlatformSymbols
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
                    sourceClasses.flatMap {
                        it.declarations.filterIsInstance<IrSimpleFunction>().filter { function ->
                            function.isGeneratedDataValueMethod()
                        }
                    } +
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
                .filterNot { inlineValueClasses.getter(it.symbol) != null }
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
                }.filter { function ->
                    function.body != null || function.modality == Modality.ABSTRACT ||
                        function.isGeneratedDataValueMethod()
                }.filterNot { function ->
                    !includeTrustedPlatformBodies &&
                        (function.parent as? IrClass)?.fqNameWhenAvailable?.asString() !in specializedCollectionInterfaces &&
                        function.fqNameWhenAvailable?.asString() !in session.sourcePlatformSymbols &&
                        function.correspondingPropertySymbol
                            ?.owner
                            ?.fqNameWhenAvailable
                            ?.asString() !in session.sourcePlatformSymbols &&
                        session.trustedPlatformModule(function.file.fileEntry.name) != null
                }.filterNot { function ->
                    !includeTrustedPlatformBodies &&
                        session.platformFunctions.any { link ->
                            link.symbol == function.fqNameWhenAvailable?.asString() &&
                                link.signature == function.canonicalPlatformSignature()
                        }
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
        val writtenValues = collectWrittenGuestValues(closureRoots)
        val captureCellDeclarations =
            closureSources
                .flatMap { it.captures }
                .filterIsInstance<IrVariable>()
                .filter { it.isVar && it.symbol in writtenValues }
                .associateByTo(linkedMapOf()) { it.symbol }
                .values
                .toList()
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
                declaration.kind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
                    declaration.constructors.any { it.isPrimary }
            }
        var functionInstances = collectGuestFunctionInstances(userFunctions, constructorClasses)
        val specializationClasses = (userClasses + sourceClasses.filter { inlineValueClasses.contains(it.symbol) }).distinctBy { it.symbol }
        var allClassInstances =
            collectGuestClassInstances(specializationClasses, functionInstances, topLevelProperties, pluginContext.irBuiltIns.anyType)
        var classInstances = allClassInstances.filterNot { inlineValueClasses.contains(it.declaration.symbol) }
        // Generic member bodies can call helpers whose signatures/body introduce further class instances.
        // Discover functions and classes together until their dependencies stop adding specializations.
        while (true) {
            functionInstances =
                collectGuestFunctionInstances(
                    userFunctions,
                    constructorClasses,
                    allClassInstances.flatMap { classInstance ->
                        if (classInstance.arguments.isEmpty()) {
                            emptyList()
                        } else {
                            userFunctions.filter { it.parent == classInstance.declaration }.map { function ->
                                GuestFunctionInstance(function, emptyList(), classInstance)
                            }
                        }
                    },
                )
            val discoveredClasses =
                collectGuestClassInstances(specializationClasses, functionInstances, topLevelProperties, pluginContext.irBuiltIns.anyType)
            if (discoveredClasses.toSet() == allClassInstances.toSet()) break
            allClassInstances = discoveredClasses
            classInstances = allClassInstances.filterNot { inlineValueClasses.contains(it.declaration.symbol) }
        }
        session.recordPlatformSpecializations(
            classInstances
                .filter { instance ->
                    (instance.arguments.isNotEmpty() || instance.declaration.hasParameterizedSupertype()) &&
                        (
                            instance.declaration.fqNameWhenAvailable?.asString() in specializedCollectionInterfaces ||
                                session.trustedPlatformModule(instance.declaration.file.fileEntry.name) != null ||
                                session.virtualSourcePath(instance.declaration.file.fileEntry.name) in session.sourcePlatformPaths
                        )
                }.map(GuestClassInstance::name),
        )
        val functionShapes =
            buildList {
                fun include(shape: GuestFunctionShape) {
                    if (shape in this) return
                    add(shape)
                    (shape.parameters + shape.result).mapNotNull(IrType::guestFunctionShape).forEach(::include)
                }
                closureSources.mapNotNull { it.expression.type.guestFunctionShape() }.forEach(::include)
                functionInstances.forEach { instance ->
                    instance.substitute(instance.declaration.returnType).guestFunctionShape()?.let(::include)
                    loweredParameters(instance.declaration, session)
                        .mapNotNull { parameter -> instance.substitute(parameter.type).guestFunctionShape() }
                        .forEach(::include)
                }
            }
        val unitBlockShape = GuestFunctionShape(emptyList(), pluginContext.irBuiltIns.unitType)
        session.recordPlatformSpecializations(functionShapes.filter { it != unitBlockShape }.map { functionShapeName(it, unitBlockShape) })
        val usesFunction0Unit = unitBlockShape in functionShapes
        val constructorInstances =
            classInstances.filter { instance ->
                instance.declaration.kind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
                    instance.declaration.constructors.any { it.isPrimary }
            } +
                classes.distinctBy { it.symbol }.mapNotNull { declaration ->
                    val name = declaration.fqNameWhenAvailable?.asString()
                    if (declaration.runtimeExceptionType() == null || name == "kotlin.Throwable") return@mapNotNull null
                    if (session.platformFunctions.any { it.symbol == "$name.<init>" }) return@mapNotNull null
                    GuestClassInstance(declaration, emptyList())
                }
        val initializerClasses =
            userClasses.filter { declaration ->
                declaration.kind == ClassKind.OBJECT ||
                    (declaration.kind == ClassKind.ENUM_CLASS && declaration.declarations.any { it is IrEnumEntry })
            }
        val constructorDeclarations = constructorInstances.map { it.declaration }
        val externalFunctions = linkedPlatformFunctions(userFunctions + constructorDeclarations, session)
        val linkedSymbols =
            linkedPlatformSymbols(
                userFunctions + constructorDeclarations + sourceClasses.filter { inlineValueClasses.contains(it.symbol) },
                session,
            )

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

        fun discoverValueClass(type: IrType) {
            val simple = type as? IrSimpleType ?: return
            simple.arguments.filterIsInstance<IrTypeProjection>().forEach { discoverValueClass(it.type) }
            val symbol = simple.classifier as? IrClassSymbol ?: return
            val inline = inlineValueClasses[symbol]
            val scalar = platformScalars.representation(type)
            val underlying =
                when {
                    inline != null -> {
                        when (inline.underlyingType) {
                            pluginContext.irBuiltIns.intType -> ValueType.I32
                            pluginContext.irBuiltIns.booleanType -> ValueType.Bool
                            pluginContext.irBuiltIns.charType -> ValueType.Char
                            else -> ValueType.Unit
                        }.takeIf { inline.underlyingTypes.size == 1 } ?: ValueType.Unit
                    }

                    scalar != null -> {
                        when (scalar) {
                            PlatformScalarRepresentation.INT -> ValueType.I32
                            PlatformScalarRepresentation.BOOLEAN -> ValueType.Bool
                            PlatformScalarRepresentation.CHAR -> ValueType.Char
                        }
                    }

                    else -> {
                        return
                    }
                }
            val name = type.specializationTypeIdentity().removeSuffix("?")
            if (name in guestTypes.valueClassBoxes) return
            guestTypes.valueClassBoxes.getOrPut(name) {
                GuestValueClassBox(
                    name,
                    symbol,
                    symbol.owner.name.asString(),
                    inline
                        ?.getter
                        ?.owner
                        ?.correspondingPropertySymbol
                        ?.owner
                        ?.name
                        ?.asString()
                        ?: symbol.owner.declarations
                            .filterIsInstance<IrProperty>()
                            .firstOrNull()
                            ?.name
                            ?.asString()
                        ?: "value",
                    underlying,
                ).also { box ->
                    box.sourceType = type
                    if (inline != null) {
                        val arguments = simple.arguments.filterIsInstance<IrTypeProjection>().map { it.type }
                        val instance = GuestClassInstance(symbol.owner, arguments)
                        box.propertyTypes = inline.underlyingTypes.map(instance::substitute)
                        box.propertyNames = inline.properties.map { it.name.asString() }
                    } else {
                        box.propertyTypes = listOf(type)
                    }
                }
            }
            guestTypes
                .valueClassBox(type)
                ?.propertyTypes
                ?.filter { it != type }
                ?.forEach(::discoverValueClass)
        }
        functionInstances.forEach { instance ->
            instance.declaration.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        when (element) {
                            is IrExpression -> discoverValueClass(instance.substitute(element.type))
                            is IrValueDeclaration -> discoverValueClass(instance.substitute(element.type))
                        }
                        if (element is IrTypeOperatorCall) discoverValueClass(instance.substitute(element.typeOperand))
                        element.acceptChildren(this, null)
                    }
                },
                null,
            )
            discoverValueClass(instance.substitute(instance.declaration.returnType))
            loweredParameters(instance.declaration, session).forEach { discoverValueClass(instance.substitute(it.type)) }
        }
        classInstances.forEach { instance ->
            instance.declaration.declarations.filterIsInstance<IrProperty>().forEach {
                it.backingField
                    ?.type
                    ?.let(instance::substitute)
                    ?.let(::discoverValueClass)
            }
        }
        if (includeTrustedPlatformBodies) {
            classes
                .filter {
                    it.isValue && it.typeParameters.isEmpty()
                }.forEach { discoverValueClass(it.defaultType) }
        }
        topLevelProperties.forEach {
            it.declaration.backingField
                ?.type
                ?.let(::discoverValueClass)
        }
        val referenceArrayUsage =
            ReferenceArrayUsageCollector(
                guestTypes,
                userClasses.associateBy { it.symbol },
                classInstances.toSet(),
                linkedSymbols.types.keys,
            )
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
                val bridgeName = CollectionReadBridges.methodName(function)
                if (bridgeName != null) {
                    bridgeName
                } else if (instance.ownerClass != null) {
                    function.name.asString()
                } else if (instance.arguments.isEmpty()) {
                    artifactFunctionName(function, pluginContext, inlineValueClasses, session)
                } else {
                    val arguments = instance.arguments.joinToString(",") { it.specializationTypeIdentity() }
                    "${function.fqNameWhenAvailable?.asString() ?: function.name.asString()}<$arguments>"
                }
            }
        val platformFunctionExports =
            if (includeTrustedPlatformBodies) {
                functionInstances
                    .filter { instance ->
                        instance.arguments.isEmpty() && instance.ownerClass?.arguments.isNullOrEmpty()
                    }.associateWith { instance ->
                        ru.lazyhat.compukters.compiler.k2.engine.library.platformFunctionExportName(
                            requireNotNull(instance.declaration.fqNameWhenAvailable).asString(),
                            instance.declaration.canonicalPlatformSignature(),
                        )
                    }
            } else {
                emptyMap()
            }
        val discoveringLeaves = mutableSetOf<String>()

        fun componentSources(box: GuestValueClassBox): List<IrType> {
            if (box.componentSourceTypes.isNotEmpty()) return box.componentSourceTypes
            if (!discoveringLeaves.add(box.name)) throw UnsupportedKotlinIr(box.symbol.owner, "recursive value-class layout")
            box.componentSourceTypes =
                box.propertyTypes.flatMap { source ->
                    val nested = guestTypes.valueClassBox(source)
                    if (nested != null && nested != box && !source.isNullable()) componentSources(nested) else listOf(source)
                }
            discoveringLeaves.remove(box.name)
            return box.componentSourceTypes
        }
        guestTypes.valueClassBoxes.values.forEach(::componentSources)
        val metadataValues =
            (
                listOf("app") +
                    runtimeTypeNames +
                    runtimeMemberNames +
                    listOfNotNull("kotlin.Array".takeIf { usesStringArray }) +
                    referenceArrays.keys +
                    guestTypes.valueClassBoxes.keys +
                    guestTypes.valueClassBoxes.values.flatMap { box -> box.componentSourceTypes.indices.map(box::payloadExportName) } +
                    guestTypes.valueClassBoxes.keys.map { "$it.<inline-value>" } +
                    listOf("<boxed-value>", "toString", "hashCode", "equals") +
                    guestTypes.valueClassBoxes.values.flatMap { box ->
                        valueClassInterfaceMethods(box.symbol).map { method ->
                            artifactFunctionName(method.overriddenSymbols.first().owner, pluginContext, inlineValueClasses, session)
                        }
                    } +
                    capabilityIdentities.flatMap { listOf(it.namespace, it.name) } +
                    externalFunctions.values.map(ExternalFunctionTarget::exportName) +
                    linkedSymbols.types.values.map(ExternalTypeTarget::exportName) +
                    linkedSymbols.fieldsByGetter.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.enumEntries.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.singletons.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.defaultEnumEntries.values.map(ExternalFieldTarget::exportName) +
                    functionInstances.map { requireNotNull(functionArtifactNames[it]) } +
                    platformFunctionExports.values +
                    functionShapes.indices.map { index -> functionShapeName(functionShapes[index], unitBlockShape) } +
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
                                declaration.declarations.filterIsInstance<IrEnumEntry>().map { it.name.asString() } +
                                listOfNotNull("<instance>".takeIf { declaration.kind == ClassKind.OBJECT })
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
            LiteralCollector(pluginContext.irBuiltIns.unitType).also { collector ->
                userFunctions.forEach { function -> function.accept(collector, null) }
                constructorClasses.forEach { declaration -> declaration.accept(collector, null) }
                constructorInstances.forEach { it.declaration.accept(collector, null) }
                topLevelProperties.forEach { property ->
                    property.declaration.backingField
                        ?.initializer
                        ?.expression
                        ?.accept(collector, null)
                }
            }
        val literals =
            (literalCollector.strings + guestTypes.valueClassBoxes.values.flatMap { it.stringParts })
                .distinct()
                .map {
                    Utf16Literal.fromString(it)
                }.sorted()
        val literalIds = literals.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        val constantPool = ConstantPoolBuilder()
        (
            (
                literalCollector.values + guestTypes.valueClassBoxes.values.flatMap { it.stringParts } +
                    topLevelProperties.mapNotNull { property ->
                        when (val initializer = property.initializer) {
                            TopLevelInitializer.Null -> null
                            is TopLevelInitializer.Scalar -> initializer.value
                        }
                    } +
                    inlineValueClasses.constantValues() +
                    platformScalars.constantValues() +
                    linkedSymbols.defaultIntValues
            ).map { value -> value.toArtifactConstant(literalIds) } +
                listOf(-1, 0, 1, 31).map(Constant::I32) +
                GuestPrimitive.entries.mapNotNull { it.narrowBits }.distinct().flatMap { bits ->
                    listOf(Constant.I32(32 - bits), Constant.I32((1 shl bits) - 1))
                } +
                listOf(-1L, 0L, 1L).map(Constant::I64) +
                listOf(-1.0f, 0.0f, 1.0f).map { Constant.F32(it.toBits().toUInt()) } +
                listOf(-1.0, 0.0, 1.0).map { Constant.F64(it.toBits().toULong()) } +
                Constant.Bool(false) + Constant.Bool(true)
        ).forEach(constantPool::intern)
        val constants = constantPool.freeze().records
        val constantIds = constants.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }

        val library = kotlinLibrary()
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val charArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(CHAR_ARRAY_RUNTIME_TYPE)))
        val stringType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(STRING_RUNTIME_TYPE)))
        val intArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(INT_ARRAY_RUNTIME_TYPE)))
        val doubleArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(DOUBLE_ARRAY_RUNTIME_TYPE)))
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
                    symbol to target.copy(importId = ImportId.of((RUNTIME_IMPORT_COUNT + index).toUInt()))
                }.toMap()
        val externalBoxTypeImports =
            guestTypes.valueClassBoxes.values
                .filter { it.symbol !in externalTypeImports }
                .mapNotNull { box ->
                    val link = session.platformTypes.singleOrNull { it.symbol == box.name } ?: return@mapNotNull null
                    box to ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
                }.sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (box, target) ->
                    box to target.copy(importId = ImportId.of((RUNTIME_IMPORT_COUNT + externalTypeImports.size + index).toUInt()))
                }.toMap()
        val externalInlineImports =
            guestTypes.valueClassBoxes.values
                .mapNotNull { box ->
                    val link = session.platformTypes.singleOrNull { it.symbol == "${box.name}.<inline-value>" } ?: return@mapNotNull null
                    box to ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
                }.sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (box, target) ->
                    box to
                        target.copy(
                            importId =
                                ImportId.of(
                                    (
                                        RUNTIME_IMPORT_COUNT + externalTypeImports.size + externalBoxTypeImports.size +
                                            index
                                    ).toUInt(),
                                ),
                        )
                }.toMap()
        val externalTypeImportCount = externalTypeImports.size + externalBoxTypeImports.size + externalInlineImports.size
        val externalClassTypes = externalTypeImports.mapValues { (_, target) -> TypeRef.Imported(target.importId) }
        val externalBoxTypes = externalBoxTypeImports.mapValues { (_, target) -> TypeRef.Imported(target.importId) }
        val externalBoxFields =
            guestTypes.valueClassBoxes.values.filter { it.symbol in externalClassTypes || it in externalBoxTypes }.flatMap { box ->
                box.componentSourceTypes.indices.map { index ->
                    val name = box.payloadExportName(index)
                    val link =
                        session.platformFields.singleOrNull { it.symbol == name }
                            ?: throw UnsupportedKotlinIr(box.symbol.owner, "precompiled value class payload field $name is missing")
                    ExternalFieldTarget(link.exportName, box.symbol, link.moduleHash.copyOf(), false)
                }
            }
        val externalFieldImports =
            (
                linkedSymbols.fieldsByGetter.values + linkedSymbols.enumEntries.values + linkedSymbols.singletons.values +
                    linkedSymbols.defaultEnumEntries.values +
                    externalBoxFields
            ).distinctBy(ExternalFieldTarget::sortKey)
                .sortedBy(ExternalFieldTarget::sortKey)
                .mapIndexed { index, target ->
                    target.copy(importId = ImportId.of((RUNTIME_IMPORT_COUNT + externalTypeImportCount + index).toUInt()))
                }
        val externalFieldsBySortKey = externalFieldImports.associateBy(ExternalFieldTarget::sortKey)
        val externalGetterFieldImports =
            linkedSymbols.fieldsByGetter.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalEnumFieldImports =
            linkedSymbols.enumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalSingletonFieldImports =
            linkedSymbols.singletons.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
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
                                    (RUNTIME_IMPORT_COUNT + externalTypeImportCount + externalFieldImportCount + index).toUInt(),
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
        var localBoxTypeCount = 0
        guestTypes.valueClassBoxes.toSortedMap().values.forEach { box ->
            val imported = externalBoxTypes[box] ?: externalClassTypes[box.symbol]
            if (imported == null) {
                box.type =
                    TypeRef.Local(TypeId.of((externalFunctionTypeBase + externalFunctionImports.size + 3 + localBoxTypeCount).toUInt()))
                localBoxTypeCount += 4 + valueClassInterfaceMethods(box.symbol).size
            } else {
                box.type = imported
                box.payloadFields =
                    box.componentSourceTypes.indices.map { index ->
                        val target =
                            externalBoxFields.single {
                                it.ownerSymbol == box.symbol &&
                                    it.exportName == box.payloadExportName(index)
                            }
                        FieldRef.Imported(requireNotNull(externalFieldsBySortKey[target.sortKey]).importId)
                    }
                box.field = box.payloadFields.first()
            }
        }
        var nextInlineType = externalFunctionTypeBase + externalFunctionImports.size + 3 + localBoxTypeCount
        guestTypes.valueClassBoxes.toSortedMap().values.filter { it.scalar == ValueType.Unit }.forEach { box ->
            box.inlineType =
                ValueType.Inline(
                    externalInlineImports[box]?.let { TypeRef.Imported(it.importId) }
                        ?: TypeRef.Local(TypeId.of((nextInlineType++).toUInt())),
                )
            box.scalar = requireNotNull(box.inlineType)
        }
        val resolvingInline = mutableSetOf<String>()

        fun resolveInlineComponents(box: GuestValueClassBox): List<ValueType> {
            if (box.componentTypes.isNotEmpty()) return box.componentTypes
            if (!resolvingInline.add(box.name)) throw UnsupportedKotlinIr(box.symbol.owner, "recursive value-class layout")
            box.componentTypes =
                box.propertyTypes.flatMap { source ->
                    val nested = guestTypes.valueClassBox(source)
                    if (nested != null && !source.isNullable()) {
                        resolveInlineComponents(nested)
                    } else {
                        listOf(
                            valueType(
                                source,
                                pluginContext,
                                guestTypes,
                                stringType,
                                charArrayType,
                                stringArrayType,
                                classTypeIds,
                                externalClassTypes,
                                inlineValueClasses,
                                platformScalars,
                                box.symbol.owner,
                                shapeInterfaceTypes,
                                classInstanceTypeIds = classInstanceTypeIds,
                            ),
                        )
                    }
                }
            resolvingInline.remove(box.name)
            return box.componentTypes
        }
        guestTypes.valueClassBoxes.values.forEach { box ->
            if (box.inlineType == null) box.componentTypes = listOf(box.scalar) else resolveInlineComponents(box)
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
            constructorInstances.filter { it.arguments.isEmpty() }.associate { instance ->
                val layout = classLayoutsByInstance[instance]
                val ownerType =
                    instance.declaration.runtimeExceptionType()?.let { TypeRef.Imported(ImportId.of(it)) }
                        ?: TypeRef.Local(requireNotNull(layout).typeId)
                requireNotNull(instance.declaration.constructors.singleOrNull { it.isPrimary }).symbol to
                    GuestConstructorTarget(instance, ownerType, requireNotNull(constructorFunctionIds[instance]), layout)
            }
        val genericConstructorTargets =
            classLayouts.filter { it.instance.arguments.isNotEmpty() && it.declaration.kind == ClassKind.CLASS }.associate { layout ->
                layout.instance to
                    GuestConstructorTarget(
                        layout.instance,
                        TypeRef.Local(layout.typeId),
                        requireNotNull(constructorFunctionIds[layout.instance]),
                        layout,
                    )
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
        var nextClosureField =
            classLayouts.sumOf { layout -> layout.fields.size + layout.enumEntries.size + if (layout.singletonFieldId == null) 0 else 1 }
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
                            classInstanceTypeIds = classInstanceTypeIds,
                        ).let { guestTypes.heapType(declaration.type, it) },
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
                                        classInstanceTypeIds = classInstanceTypeIds,
                                    ).let { guestTypes.heapType(declaration.type, it) },
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
                                            ).let { guestTypes.heapType(receiver.type, it) },
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
        val debug = mutableListOf<DebugEntry>()
        val exceptions = mutableListOf<ExceptionEntry>()
        val localValueClassBoxes =
            guestTypes.valueClassBoxes
                .toSortedMap()
                .values
                .filter { it.type is TypeRef.Local }
        var nextBoxField = firstTopLevelField + topLevelFields.size
        localValueClassBoxes.forEach { box ->
            box.field = FieldRef.Local(FieldId.of(nextBoxField.toUInt()))
            nextBoxField += box.componentTypes.size
        }
        val loweredFunctions = mutableListOf<Function>()

        functionInstances.forEach { instance ->
            val function = instance.declaration
            val functionId = requireNotNull(instanceFunctionIds[instance])
            val firstBlock = blocks.size
            val compiled =
                if (function.body == null && !function.isGeneratedDataValueMethod()) {
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
                            doubleArrayType = doubleArrayType,
                            stringArrayType = stringArrayType,
                            guestTypes = guestTypes,
                            unitType = pluginContext.irBuiltIns.unitType,
                            kotlinStringType = pluginContext.irBuiltIns.stringType,
                            kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                            kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                            kotlinDoubleArrayClass = pluginContext.irBuiltIns.doubleArray,
                            intType = pluginContext.irBuiltIns.intType,
                            longType = pluginContext.irBuiltIns.longType,
                            floatType = pluginContext.irBuiltIns.floatType,
                            doubleType = pluginContext.irBuiltIns.doubleType,
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
                            singletonLayouts = classLayouts.filter { it.singletonFieldId != null }.associateBy { it.declaration.symbol },
                            externalSingletons = externalSingletonFieldImports,
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
            debug += compiled.debug
            exceptions += compiled.exceptions
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
                instance.ownerClass?.takeUnless { inlineValueClasses.contains(it.declaration.symbol) }?.let { owner ->
                    TypeRef.Local(requireNotNull(classInstanceTypeIds[owner]))
                }
                    ?: ownerClass
                        ?.takeIf { it.symbol in classLayoutsBySymbol && !inlineValueClasses.contains(it.symbol) }
                        ?.let { TypeRef.Local(requireNotNull(classTypeIds[it.symbol])) }
            val flags =
                setOfNotNull(
                    FunctionFlag.STATIC.takeIf { memberOwner == null },
                    FunctionFlag.SUSPENDING.takeIf { function.isSuspend },
                    FunctionFlag.ABSTRACT.takeIf { function.body == null && !function.isGeneratedDataValueMethod() },
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
                    values = (parameterTypes + compiled.localTypes).map(guestTypes::functionValue),
                    parameterCount = parameterTypes.size.toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = (exceptions.size - compiled.exceptions.size).toUInt(),
                    exceptionCount = compiled.exceptions.size.toUInt(),
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
                            .map(guestTypes::functionValue),
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
                if (layout.referenceTarget != null ||
                    (layout.constructorTarget != null && inlineValueClasses.constructor(layout.constructorTarget) == null)
                ) {
                    val targetFunction =
                        layout.referenceTarget?.let { method ->
                            val owner = method.parent as? IrClass
                            val receiver =
                                layout.captures
                                    .singleOrNull { it.initialValue != null }
                                    ?.initialValue
                                    ?.type
                                    ?: layout.shape.parameters.firstOrNull()
                            val instance =
                                (receiver as? IrSimpleType)?.takeIf { it.classifier == owner?.symbol }?.let {
                                    GuestClassInstance(
                                        requireNotNull(owner),
                                        it.arguments.filterIsInstance<IrTypeProjection>().map { argument ->
                                            argument.type
                                        },
                                    )
                                }
                            (instance?.let { genericMemberFunctionIds[method.symbol to it] } ?: functionIds[method.symbol])?.let(
                                FunctionRef::Local,
                            )
                                ?: externalFunctionImports[method.symbol]?.let { FunctionRef.Imported(it.importId) }
                        } ?: layout.constructorTarget?.let { constructor ->
                            constructorFunctionIds[
                                GuestClassInstance(
                                    constructor.owner.parentAsClass,
                                    emptyList(),
                                ),
                            ]?.let(FunctionRef::Local)
                        } ?: throw UnsupportedKotlinIr(layout.expression, "function reference target is not in the Guest project")
                    val localTypes = mutableListOf<ValueType>()
                    val prologue = mutableListOf<Instruction>()

                    fun local(type: ValueType): RegisterId =
                        RegisterId.of((layout.shape.arity + 1 + localTypes.size).toUInt()).also {
                            localTypes +=
                                type
                        }
                    val boundCapture = layout.captures.singleOrNull { it.initialValue != null }
                    val boundRegister =
                        boundCapture?.let { capture ->
                            val stored = local(capture.type)
                            prologue += Instruction.FieldGet(stored, RegisterId.of(0u), FieldRef.Local(capture.fieldId))
                            val box = guestTypes.valueClassBox(requireNotNull(capture.initialValue).type)
                            if (box?.inlineType != null) {
                                val components =
                                    box.componentTypes.mapIndexed { index, type ->
                                        local(type).also { prologue += Instruction.FieldGet(it, stored, box.fieldAt(index)) }
                                    }
                                local(box.scalar).also { prologue += Instruction.InlineConstruct(it, components) }
                            } else {
                                stored
                            }
                        }
                    val resultType = shapeValueType(layout.shape.result)
                    val destination =
                        if (resultType ==
                            ValueType.Unit
                        ) {
                            Destination.Unit
                        } else {
                            Destination.Register(local(resultType))
                        }
                    val arguments = listOfNotNull(boundRegister) + (1..layout.shape.arity).map { RegisterId.of(it.toUInt()) }
                    val owner = layout.referenceTarget?.parent as? IrClass
                    val constructorType =
                        layout.constructorTarget?.let { constructor ->
                            (
                                constructorTargets[constructor]
                                    ?: throw UnsupportedKotlinIr(
                                        layout.expression,
                                        "constructor reference target is outside the supported Guest project subset",
                                    )
                            ).ownerType
                        }
                    val call =
                        when {
                            constructorType != null -> {
                                Instruction.Call(
                                    Destination.Unit,
                                    targetFunction,
                                    listOf((destination as Destination.Register).id) + arguments,
                                )
                            }

                            owner?.kind == ClassKind.INTERFACE -> {
                                Instruction.CallInterface(destination, targetFunction, arguments)
                            }

                            owner != null && !inlineValueClasses.contains(owner.symbol) &&
                                layout.referenceTarget.requiresVirtualDispatch() -> {
                                Instruction.CallVirtual(destination, targetFunction, arguments)
                            }

                            else -> {
                                Instruction.Call(destination, targetFunction, arguments)
                            }
                        }
                    CompiledFunction(
                        localTypes = localTypes,
                        blocks =
                            listOf(
                                Block(
                                    layout.invokeFunctionId,
                                    false,
                                    prologue +
                                        listOfNotNull(
                                            constructorType?.let { Instruction.NewObject((destination as Destination.Register).id, it) },
                                        ) +
                                        listOf(call, Instruction.Return(destination)),
                                ),
                            ),
                    )
                } else {
                    FunctionCompiler(
                        pluginContext = pluginContext,
                        function = layout.function ?: requireNotNull(layout.constructorTarget).owner,
                        functionId = layout.invokeFunctionId,
                        blockBase = firstBlock,
                        stringType = stringType,
                        charArrayType = charArrayType,
                        intArrayType = intArrayType,
                        doubleArrayType = doubleArrayType,
                        stringArrayType = stringArrayType,
                        guestTypes = guestTypes,
                        unitType = pluginContext.irBuiltIns.unitType,
                        kotlinStringType = pluginContext.irBuiltIns.stringType,
                        kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                        kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                        kotlinDoubleArrayClass = pluginContext.irBuiltIns.doubleArray,
                        intType = pluginContext.irBuiltIns.intType,
                        longType = pluginContext.irBuiltIns.longType,
                        floatType = pluginContext.irBuiltIns.floatType,
                        doubleType = pluginContext.irBuiltIns.doubleType,
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
                        singletonLayouts = classLayouts.filter { it.singletonFieldId != null }.associateBy { it.declaration.symbol },
                        externalSingletons = externalSingletonFieldImports,
                        externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                        externalFunctions = externalFunctionImports,
                        functionTypes = shapeInterfaceTypes,
                        invokeFunctionIds = shapeInvokeFunctionIds,
                        taskLaunchTrampolineFunctionId = taskLaunchTrampolineFunctionId,
                        closureLayouts = closureLayoutsByExpression,
                        captureCells = captureCellLayoutsBySymbol,
                        currentClassInstance =
                            layout.constructorTarget?.let { symbol ->
                                val result = layout.shape.result as IrSimpleType
                                GuestClassInstance(
                                    symbol.owner.parentAsClass,
                                    result.arguments.filterIsInstance<IrTypeProjection>().map { it.type },
                                )
                            },
                        valueClassConstructorReference = layout.constructorTarget?.let(inlineValueClasses::constructor),
                        leadingParameterTypes = listOf(receiverType),
                        captureFields = layout.captures.mapNotNull { capture -> capture.symbol?.let { it to capture } }.toMap(),
                        closureReceiver = RegisterId.of(0u),
                    ).compile()
                }
            blocks += compiled.blocks
            debug += compiled.debug
            exceptions += compiled.exceptions
            loweredFunctions +=
                Function(
                    owner = closureType,
                    name = requireNotNull(metadataIds["invoke"]),
                    signature = TypeRef.Local(layout.invokeSignatureTypeId),
                    flags = setOf(FunctionFlag.VIRTUAL),
                    values =
                        (listOf(receiverType) + layout.shape.parameters.map(shapeValueType) + compiled.localTypes)
                            .map(guestTypes::functionValue),
                    parameterCount = (layout.shape.arity + 1).toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = (exceptions.size - compiled.exceptions.size).toUInt(),
                    exceptionCount = compiled.exceptions.size.toUInt(),
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
            val layout = classLayoutsByInstance[classInstance]
            val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
            val functionId = requireNotNull(constructorFunctionIds[classInstance])
            val receiverType =
                ValueType.Ref(
                    nullable = false,
                    type =
                        declaration.runtimeExceptionType()?.let { TypeRef.Imported(ImportId.of(it)) }
                            ?: TypeRef.Local(requireNotNull(layout).typeId),
                )
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
                    doubleArrayType = doubleArrayType,
                    stringArrayType = stringArrayType,
                    guestTypes = guestTypes,
                    unitType = pluginContext.irBuiltIns.unitType,
                    kotlinStringType = pluginContext.irBuiltIns.stringType,
                    kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                    kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                    kotlinDoubleArrayClass = pluginContext.irBuiltIns.doubleArray,
                    intType = pluginContext.irBuiltIns.intType,
                    longType = pluginContext.irBuiltIns.longType,
                    floatType = pluginContext.irBuiltIns.floatType,
                    doubleType = pluginContext.irBuiltIns.doubleType,
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
                    singletonLayouts = classLayouts.filter { it.singletonFieldId != null }.associateBy { it.declaration.symbol },
                    externalSingletons = externalSingletonFieldImports,
                    externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                    externalFunctions = externalFunctionImports,
                    functionTypes = shapeInterfaceTypes,
                    invokeFunctionIds = shapeInvokeFunctionIds,
                    taskLaunchTrampolineFunctionId = taskLaunchTrampolineFunctionId,
                    closureLayouts = closureLayoutsByExpression,
                    captureCells = captureCellLayoutsBySymbol,
                    leadingParameterTypes = listOf(receiverType),
                    constructorOwner = layout,
                    constructorDeclaration = declaration,
                ).compile()
            blocks += compiled.blocks
            debug += compiled.debug
            exceptions += compiled.exceptions
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds[constructorName(classInstance)]),
                    signature = TypeRef.Local(requireNotNull(constructorTypeIds[classInstance])),
                    flags = setOf(FunctionFlag.STATIC),
                    values = (listOf(receiverType) + parameterTypes + compiled.localTypes).map(guestTypes::functionValue),
                    parameterCount = (parameterTypes.size + 1).toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = (exceptions.size - compiled.exceptions.size).toUInt(),
                    exceptionCount = compiled.exceptions.size.toUInt(),
                )
        }

        initializerClasses.forEach { declaration ->
            val layout = requireNotNull(classLayoutsBySymbol[declaration.symbol])
            val functionId = requireNotNull(initializerFunctionIds[declaration.symbol])
            val firstBlock = blocks.size
            if (layout.singletonFieldId != null) {
                val constructor = requireNotNull(constructorFunctionIds[layout.instance])
                blocks +=
                    Block(
                        functionId,
                        false,
                        listOf(
                            Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(layout.typeId)),
                            Instruction.Call(Destination.Unit, FunctionRef.Local(constructor), listOf(RegisterId.of(0u))),
                            Instruction.StaticSet(FieldRef.Local(layout.singletonFieldId), RegisterId.of(0u)),
                            Instruction.Return(Destination.Unit),
                        ),
                    )
                loweredFunctions +=
                    Function(
                        owner = TypeRef.Local(layout.typeId),
                        name = requireNotNull(metadataIds["<clinit>"]),
                        signature = TypeRef.Local(requireNotNull(initializerTypeIds[declaration.symbol])),
                        flags = setOf(FunctionFlag.STATIC),
                        values = listOf(FunctionValue.scalar(ValueType.Ref(false, TypeRef.Local(layout.typeId)))),
                        parameterCount = 0u,
                        firstBlock = BlockId.of(firstBlock.toUInt()),
                        blockCount = 1u,
                        firstException = 0u,
                        exceptionCount = 0u,
                    )
                return@forEach
            }
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
                val layout = classLayoutsByInstance[classInstance]
                val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
                NominalType.Function(
                    name = requireNotNull(metadataIds[constructorName(classInstance)]),
                    suspending = false,
                    result = ValueType.Unit,
                    parameters =
                        listOf(
                            ValueType.Ref(
                                nullable = false,
                                type =
                                    declaration.runtimeExceptionType()?.let { TypeRef.Imported(ImportId.of(it)) }
                                        ?: TypeRef.Local(requireNotNull(layout).typeId),
                            ),
                        ) +
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
                        } ?: ((resolved as? IrSimpleType)?.classifier as? IrClassSymbol)?.let { symbol ->
                            externalClassTypes[symbol]?.let { symbol to it }
                        }
                    }
                val bridgeInterfaces =
                    CollectionReadBridges
                        .interfaces(declaration, layout.instance.arguments, pluginContext.irBuiltIns.anyType)
                        .mapNotNull { (root, argument) ->
                            val name = "$root<${argument.specializationTypeIdentity()}>"
                            classInstanceTypeIds.entries
                                .singleOrNull { it.key.name == name }
                                ?.value
                                ?.let(TypeRef::Local)
                        }
                val interfaces =
                    (
                        sourceParents.filter { (symbol, _) -> symbol.owner.kind == ClassKind.INTERFACE }.map { it.second } +
                            bridgeInterfaces
                    ).distinct()
                        .sortedWith(
                            compareBy<TypeRef>({ if (it is TypeRef.Local) 0 else 1 }, {
                                when (it) {
                                    is TypeRef.Local -> it.id.value
                                    is TypeRef.Imported -> it.id.value
                                }
                            }),
                        )
                val superType =
                    sourceParents.firstOrNull { (symbol, _) -> symbol.owner.kind != ClassKind.INTERFACE }?.second
                        ?: declaration.superTypes.firstNotNullOfOrNull { type ->
                            val parent = (type as? IrSimpleType)?.classifier as? IrClassSymbol
                            parent?.takeIf { it.owner.kind != ClassKind.INTERFACE }?.let { externalClassTypes[it] }
                        }
                        ?: declaration.superTypes
                            .mapNotNull { ((it as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.runtimeExceptionType() }
                            .singleOrNull()
                            ?.let { TypeRef.Imported(ImportId.of(it)) }
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
                        fieldCount =
                            (
                                layout.fields.size + layout.enumEntries.size +
                                    if (layout.singletonFieldId ==
                                        null
                                    ) {
                                        0
                                    } else {
                                        1
                                    }
                            ).toUInt(),
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
            functionShapes.map { shape ->
                NominalType.Interface(
                    name = requireNotNull(metadataIds[functionShapeName(shape, unitBlockShape)]),
                    methodStart = requireNotNull(shapeInvokeFunctionIds[shape]).value,
                    methodCount = 1u,
                )
            } +
                closureLayouts.map { layout ->
                    NominalType.Class(
                        name = requireNotNull(metadataIds[layout.name]),
                        final = true,
                        superType = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)),
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
                    } +
                    listOfNotNull(
                        layout.singletonFieldId?.let {
                            Field(
                                owner,
                                requireNotNull(metadataIds["<instance>"]),
                                ValueType.Ref(false, owner),
                                mutable = true,
                                static = true,
                            )
                        },
                    )
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
        localValueClassBoxes.forEach { box ->
            val source = box.sourceType as IrSimpleType
            val ownerInstance = GuestClassInstance(box.symbol.owner, source.arguments.filterIsInstance<IrTypeProjection>().map { it.type })
            box.toStringTarget =
                box.symbol.owner.declarations
                    .filterIsInstance<IrSimpleFunction>()
                    .singleOrNull {
                        it.name.asString() == "toString" && it.origin == IrDeclarationOrigin.DEFINED &&
                            it.parameters.none { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
                    }?.let { method ->
                        val source = box.sourceType as IrSimpleType
                        val instance =
                            GuestClassInstance(box.symbol.owner, source.arguments.filterIsInstance<IrTypeProjection>().map { it.type })
                        genericMemberFunctionIds[method.symbol to instance] ?: functionIds[method.symbol]
                    }?.let(FunctionRef::Local)
            box.interfaces =
                box.symbol.owner.superTypes
                    .map(ownerInstance::substitute)
                    .mapNotNull { parent ->
                        val symbol = (parent as? IrSimpleType)?.classifier as? IrClassSymbol ?: return@mapNotNull null
                        if (symbol.owner.kind != ClassKind.INTERFACE) return@mapNotNull null
                        val instance = parent.classInstance(classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration })
                        instance?.let(classInstanceTypeIds::get)?.let(TypeRef::Local) ?: externalClassTypes[symbol]
                            ?: throw UnsupportedKotlinIr(box.symbol.owner, "value class interface parent is outside the supported subset")
                    }.distinct()
                    .sortedWith(
                        compareBy<TypeRef>({ if (it is TypeRef.Local) 0 else 1 }, {
                            when (it) {
                                is TypeRef.Local -> it.id.value
                                is TypeRef.Imported -> it.id.value
                            }
                        }),
                    )
            box.bridges =
                valueClassInterfaceMethods(box.symbol).map { method ->
                    fun mapped(type: IrType) =
                        valueType(
                            ownerInstance.substitute(type),
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            method,
                            shapeInterfaceTypes,
                            classInstanceTypeIds = classInstanceTypeIds,
                        )
                    val target =
                        (genericMemberFunctionIds[method.symbol to ownerInstance] ?: functionIds[method.symbol])?.let(FunctionRef::Local)
                            ?: externalFunctionImports[method.symbol]?.let { FunctionRef.Imported(it.importId) }
                    if (target == null && inlineValueClasses.getter(method.symbol) == null) {
                        throw UnsupportedKotlinIr(method, "value class interface implementation is not available")
                    }
                    GuestValueClassBridge(
                        method.overriddenSymbols.first().owner.let { declaration ->
                            val parent = declaration.parent as? IrClass
                            if (parent != null && (parent.typeParameters.isNotEmpty() || parent.hasParameterizedSupertype())) {
                                declaration.name.asString()
                            } else {
                                artifactFunctionName(declaration, pluginContext, inlineValueClasses, session)
                            }
                        },
                        target,
                        method.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.map { mapped(it.type) },
                        mapped(method.returnType),
                        method.isSuspend,
                        inlineValueClasses
                            .getter(method.symbol)
                            ?.getters
                            ?.indexOf(method.symbol)
                            ?.takeIf { it >= 0 },
                    )
                }
        }
        val valueClassBoxArtifacts =
            lowerValueClassBoxes(
                localValueClassBoxes,
                loweredFunctions.size.toUInt(),
                blocks.size.toUInt(),
                metadataIds,
                guestTypes.valueClassBoxes.values
                    .flatMap { it.stringParts }
                    .associateWith { requireNotNull(constantIds[it.toArtifactConstant(literalIds)]) },
                TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)),
                stringType,
                guestTypes,
                constantIds,
            )
        loweredFunctions += valueClassBoxArtifacts.functions
        blocks += valueClassBoxArtifacts.blocks
        val externalFunctionTypes =
            externalFunctionImports.entries
                .sortedBy { (_, target) -> target.sortKey }
                .map { (symbol, target) ->
                    val function = symbol.owner
                    NominalType.Function(
                        name = requireNotNull(metadataIds[target.exportName]),
                        suspending = (function as? IrSimpleFunction)?.isSuspend == true,
                        result =
                            if (function is IrConstructor) {
                                ValueType.Unit
                            } else {
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
                                    classInstanceTypeIds = classInstanceTypeIds,
                                )
                            },
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
                                    classInstanceTypeIds = classInstanceTypeIds,
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
                                    superType = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)),
                                ),
                            )
                        } else {
                            emptyList()
                        } +
                        referenceArrays.map { (name, element) ->
                            NominalType.Array(
                                name = requireNotNull(metadataIds[name]),
                                superType = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)),
                                element =
                                    ValueType.Ref(
                                        nullable = element.nullable,
                                        type =
                                            when (element) {
                                                is ReferenceArrayElement.ValueClass -> {
                                                    element.box.type
                                                }

                                                is ReferenceArrayElement.Runtime -> {
                                                    TypeRef.Imported(ImportId.of(element.runtimeType))
                                                }

                                                is ReferenceArrayElement.PlatformClass -> {
                                                    requireNotNull(externalClassTypes[element.symbol])
                                                }

                                                is ReferenceArrayElement.GuestClass -> {
                                                    TypeRef.Local(requireNotNull(classInstanceTypeIds[element.instance]))
                                                }
                                            },
                                    ),
                            )
                        } + initializerTypes + topLevelInitializerTypes + externalFunctionTypes +
                        NominalType.Function(
                            name = requireNotNull(metadataIds[ANY_TO_STRING_NAME]),
                            suspending = false,
                            result = ValueType.Ref(false, TypeRef.Imported(ImportId.of(STRING_RUNTIME_TYPE))),
                            parameters = listOf(ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))),
                        ) +
                        NominalType.Function(
                            name = requireNotNull(metadataIds[ANY_HASH_CODE_NAME]),
                            suspending = false,
                            result = ValueType.I32,
                            parameters = listOf(ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))),
                        ) +
                        NominalType.Function(
                            name = requireNotNull(metadataIds[ANY_EQUALS_NAME]),
                            suspending = false,
                            result = ValueType.Bool,
                            parameters =
                                listOf(
                                    ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                                    ValueType.Ref(true, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                                ),
                        ) + valueClassBoxArtifacts.types +
                        guestTypes.valueClassBoxes.values
                            .filter { it.inlineType?.type is TypeRef.Local }
                            .sortedBy {
                                ((requireNotNull(it.inlineType).type as TypeRef.Local).id.value)
                            }.map { NominalType.InlineValue(requireNotNull(metadataIds["${it.name}.<inline-value>"]), it.componentTypes) },
                constants = constants,
                fields = artifactFields + valueClassBoxArtifacts.fields,
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
                        listOf(THROWABLE_MESSAGE_NAME, THROWABLE_CAUSE_NAME).map { name ->
                            Import(
                                kind = SymbolKind.FIELD,
                                targetModule = ModuleId.of(1u),
                                targetName = requireNotNull(metadataIds[name]),
                                expectedSignature = TypeRef.Imported(ImportId.of(2u)),
                                targetModuleHash = libraryHash,
                            )
                        } +
                        scalarBoxes.drop(1).take(5).map { box ->
                            Import(
                                SymbolKind.FIELD,
                                ModuleId.of(1u),
                                requireNotNull(metadataIds[box.name]),
                                TypeRef.Imported(ImportId.of(box.type)),
                                libraryHash,
                            )
                        } +
                        Import(
                            SymbolKind.FIELD,
                            ModuleId.of(1u),
                            requireNotNull(metadataIds[UNIT_INSTANCE_NAME]),
                            TypeRef.Imported(ImportId.of(UNIT_RUNTIME_TYPE)),
                            libraryHash,
                        ) +
                        Import(
                            SymbolKind.FUNCTION,
                            ModuleId.of(1u),
                            requireNotNull(metadataIds[ANY_TO_STRING_NAME]),
                            TypeRef.Local(TypeId.of((externalFunctionTypeBase + externalFunctionImports.size).toUInt())),
                            libraryHash,
                        ) +
                        Import(
                            SymbolKind.FUNCTION,
                            ModuleId.of(1u),
                            requireNotNull(metadataIds[ANY_HASH_CODE_NAME]),
                            TypeRef.Local(TypeId.of((externalFunctionTypeBase + externalFunctionImports.size + 1).toUInt())),
                            libraryHash,
                        ) +
                        Import(
                            SymbolKind.FUNCTION,
                            ModuleId.of(1u),
                            requireNotNull(metadataIds[ANY_EQUALS_NAME]),
                            TypeRef.Local(TypeId.of((externalFunctionTypeBase + externalFunctionImports.size + 2).toUInt())),
                            libraryHash,
                        ) +
                        scalarBoxes.drop(6).map { box ->
                            Import(
                                SymbolKind.FIELD,
                                ModuleId.of(1u),
                                requireNotNull(metadataIds[box.name]),
                                TypeRef.Imported(ImportId.of(box.type)),
                                libraryHash,
                            )
                        } +
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
                        externalBoxTypeImports.entries.sortedBy { (_, target) -> target.sortKey }.mapIndexed { index, (_, target) ->
                            Import(
                                SymbolKind.TYPE,
                                ModuleId.of((2 + externalTypeImports.size + index).toUInt()),
                                requireNotNull(metadataIds[target.exportName]),
                                TypeRef.Imported(target.importId),
                                target.moduleHash,
                            )
                        } +
                        externalInlineImports.entries.sortedBy { (_, target) -> target.sortKey }.mapIndexed { index, (_, target) ->
                            Import(
                                SymbolKind.TYPE,
                                ModuleId.of((2 + externalTypeImports.size + externalBoxTypeImports.size + index).toUInt()),
                                requireNotNull(metadataIds[target.exportName]),
                                TypeRef.Imported(target.importId),
                                target.moduleHash,
                            )
                        } +
                        externalFieldImports.mapIndexed { index, target ->
                            Import(
                                kind = SymbolKind.FIELD,
                                targetModule = ModuleId.of((2 + externalTypeImportCount + index).toUInt()),
                                targetName = requireNotNull(metadataIds[target.exportName]),
                                expectedSignature =
                                    requireNotNull(
                                        externalBoxFields.firstOrNull { it.sortKey == target.sortKey }?.let { field ->
                                            guestTypes.valueClassBoxes.values
                                                .singleOrNull { box ->
                                                    box.symbol == field.ownerSymbol &&
                                                        box.componentSourceTypes.indices.any {
                                                            box.payloadExportName(
                                                                it,
                                                            ) == field.exportName
                                                        }
                                                }?.type
                                        } ?: externalClassTypes[target.ownerSymbol],
                                    ) {
                                        "platform field ${target.exportName} has no imported owner ${target.ownerSymbol.owner.fqNameWhenAvailable}"
                                    },
                                targetModuleHash = target.moduleHash,
                            )
                        } +
                        externalFunctionImports.entries
                            .sortedBy { (_, target) -> target.sortKey }
                            .mapIndexed { index, (_, target) ->
                                Import(
                                    kind = SymbolKind.FUNCTION,
                                    targetModule = ModuleId.of((2 + externalTypeImportCount + externalFieldImportCount + index).toUInt()),
                                    targetName = requireNotNull(metadataIds[target.exportName]),
                                    expectedSignature = TypeRef.Local(TypeId.of((externalFunctionTypeBase + index).toUInt())),
                                    targetModuleHash = target.moduleHash,
                                )
                            },
                functions = loweredFunctions,
                blocks = blocks,
                exceptions = exceptions,
                debug = debug.sortedWith(compareBy({ it.function.value }, { it.block.value }, { it.instruction })),
                exports =
                    (
                        if (includeTrustedPlatformBodies) {
                            localValueClassBoxes.flatMap { box ->
                                listOf(
                                    Export(
                                        SymbolKind.TYPE,
                                        ExportVisibility.PUBLIC_LIBRARY,
                                        requireNotNull(metadataIds[box.name]),
                                        (box.type as TypeRef.Local).id.value,
                                        box.type,
                                    ),
                                ) +
                                    box.componentTypes.indices.map { index ->
                                        Export(
                                            SymbolKind.FIELD,
                                            ExportVisibility.PUBLIC_LIBRARY,
                                            requireNotNull(metadataIds[box.payloadExportName(index)]),
                                            (box.fieldAt(index) as FieldRef.Local).id.value,
                                            box.type,
                                        )
                                    } +
                                    listOfNotNull(
                                        box.inlineType?.let { inline ->
                                            Export(
                                                SymbolKind.TYPE,
                                                ExportVisibility.PUBLIC_LIBRARY,
                                                requireNotNull(metadataIds["${box.name}.<inline-value>"]),
                                                (inline.type as TypeRef.Local).id.value,
                                                inline.type,
                                            )
                                        },
                                    )
                            }
                        } else {
                            emptyList()
                        }
                    ) +
                        platformFunctionExports.map { (instance, name) ->
                            Export(
                                SymbolKind.FUNCTION,
                                ExportVisibility.PUBLIC_LIBRARY,
                                requireNotNull(metadataIds[name]),
                                requireNotNull(instanceFunctionIds[instance]).value,
                                TypeRef.Local(requireNotNull(instanceTypeIds[instance])),
                            )
                        },
            )
        val modules = listOf(app, library)
        val maximumCallDepth = 16u
        val usesTasks = blocks.any { block -> block.instructions.any { it is Instruction.TaskSpawn || it is Instruction.TaskJoin } }
        val usesI64StringConversion =
            blocks.any { block ->
                block.instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.I64 }
            }
        val usesF32StringConversion =
            blocks.any { block ->
                block.instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.F32 }
            }
        val usesArrayCopy = blocks.any { block -> block.instructions.any { it is Instruction.ArrayCopy } }
        val maximumCoroutines = if (usesTasks) MAXIMUM_TASKS else 1u
        val singleTaskStackBytes = ExecutionStorage.requiredStackBytes(modules, maximumCallDepth)
        require(singleTaskStackBytes <= UInt.MAX_VALUE / maximumCoroutines) {
            "required task frame storage exceeds u32"
        }
        val requiredStackBytes = singleTaskStackBytes * maximumCoroutines
        return Artifact(
            minimumRuntimeAbi =
                when {
                    modules.any { module -> module.types.any { it is NominalType.InlineValue } } -> AbiVersion(1u, 15u)

                    modules.any { module ->
                        module.types.any {
                            it is NominalType.Array &&
                                it.storage != ru.lazyhat.compukters.compiler.artifact.model.ArrayStorage.NATURAL
                        } ||
                            module.blocks.any { block -> block.instructions.any { it.usesUnsignedSemantics() } }
                    } -> AbiVersion(1u, 14u)

                    modules.any { it.hasStructuredHostResponse() } -> AbiVersion(1u, 13u)

                    modules.any { module ->
                        module.blocks.any { block ->
                            block.instructions.any {
                                (it is Instruction.ValueHash && it.type == HashValueType.F64) ||
                                    (it is Instruction.StringValueOf && it.type == StringValueType.F64)
                            }
                        }
                    } -> AbiVersion(1u, 12u)

                    modules.any { module ->
                        module.blocks.any { block ->
                            block.instructions.any { it is Instruction.ValueHash }
                        }
                    } -> AbiVersion(1u, 11u)

                    modules.any { module ->
                        module.blocks.any { block ->
                            block.instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.REFERENCE }
                        }
                    } -> AbiVersion(1u, 10u)

                    modules.any { module ->
                        module.types.any { it is NominalType.Class && it.runtimeExceptionKind != null }
                    } -> AbiVersion(1u, 9u)

                    modules.any { module -> module.types.any { it is NominalType.Class && it.throwableRoot } } -> AbiVersion(1u, 8u)

                    modules.any { module -> module.types.any { it is NominalType.Array && it.superType != null } } -> AbiVersion(1u, 7u)

                    modules.any { it.hasHeterogeneousReferenceComparison() } -> AbiVersion(1u, 6u)

                    usesArrayCopy -> AbiVersion(1u, 5u)

                    usesF32StringConversion -> AbiVersion(1u, 4u)

                    usesI64StringConversion -> AbiVersion(1u, 3u)

                    usesTasks -> AbiVersion(1u, 1u)

                    else -> AbiVersion(1u, 0u)
                },
            semanticFeatures =
                setOfNotNull(
                    SemanticFeature.COROUTINES.takeIf { userFunctions.any { it.isSuspend } },
                    SemanticFeature.CAPABILITIES.takeIf { capabilityIdentities.isNotEmpty() },
                    SemanticFeature.ARRAY_COPY.takeIf { usesArrayCopy },
                    SemanticFeature.EXCEPTIONS.takeIf {
                        exceptions.isNotEmpty() || blocks.any { block -> block.instructions.any { it is Instruction.Throw } }
                    },
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
                    maximumChannels = 0u,
                    maximumChannelValues = 0u,
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
            if (expression is IrConst && expression.value == null) {
                TopLevelInitializer.Null
            } else {
                val value = (expression as? IrConst)?.primitiveLiteralValue()
                if (value !is Byte && value !is Short && value !is Int && value !is Long && value !is Float && value !is Double &&
                    value !is Boolean &&
                    value !is Char &&
                    value !is String
                ) {
                    throw UnsupportedKotlinIr(
                        expression,
                        "top-level val initializer must be a scalar literal",
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
        // Managed overrides must keep the declaration's dispatch name even when a generic result
        // specializes to a value class. Static scalar implementations still need nominal mangling.
        if (!inlineValueClasses.contains((function.parent as? IrClass)?.symbol)) {
            function.overriddenSymbols.firstOrNull()?.owner?.let { overridden ->
                return artifactFunctionName(overridden, pluginContext, inlineValueClasses, session)
            }
        }
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

            pluginContext.irBuiltIns.doubleType -> {
                "kotlin.Double"
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
                pluginContext.irBuiltIns.doubleType,
                pluginContext.irBuiltIns.booleanType,
                pluginContext.irBuiltIns.charType,
                pluginContext.irBuiltIns.anyType,
            )

        fun isSupported(sourceType: IrType): Boolean {
            val type = instance.substitute(sourceType)
            if (GuestPrimitive.scalar(type) != null || GuestPrimitive.array(type) != null) return true
            if (((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.runtimeExceptionType() != null) return true
            val stringClass = (pluginContext.irBuiltIns.stringType as IrSimpleType).classifier
            if (type.isNullable()) {
                return guestTypes.valueClassBox(type) != null || type.isNullableScalar() || type.isKotlinAny() ||
                    (type as? IrSimpleType)?.classifier == stringClass ||
                    classTypeIds.containsKey((type as? IrSimpleType)?.classifier) ||
                    externalClassTypes.containsKey((type as? IrSimpleType)?.classifier) ||
                    type.classInstance(
                        classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration },
                    ) in classInstanceTypeIds
            }
            return type in supported ||
                type.isNothing() ||
                type.isExactClass(pluginContext.irBuiltIns.charArray) ||
                type.isExactClass(pluginContext.irBuiltIns.intArray) ||
                type.isExactClass(pluginContext.irBuiltIns.doubleArray) ||
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
            throw UnsupportedKotlinIr(
                function,
                "unsupported function signature ${function.fqNameWhenAvailable}: ${function.canonicalPlatformSignature()}",
            )
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
            if (declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.ENUM_CLASS, ClassKind.OBJECT) ||
                (
                    declaration.typeParameters.isNotEmpty() &&
                        (
                            declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE) ||
                                declaration.isData ||
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
                    GuestFieldLayout(
                        property,
                        FieldId.of(nextField++),
                        guestTypes.heapType(instance.substitute(requireNotNull(property.backingField).type), fieldType),
                    )
                }
            val owner = TypeRef.Local(typeId)
            val entries =
                declaration.declarations.filterIsInstance<IrEnumEntry>().map { enumEntry ->
                    GuestEnumEntryLayout(enumEntry, FieldId.of(nextField++), owner)
                }
            val singletonFieldId = if (declaration.kind == ClassKind.OBJECT) FieldId.of(nextField++) else null
            GuestClassLayout(instance, typeId, firstField, fields, entries, singletonFieldId)
        }
    }

    private fun kotlinLibrary(): Module {
        val names = (runtimeTypeNames + runtimeMemberNames + listOf("toString", "hashCode", "equals", "<unit-init>")).sorted()
        val ids = names.withIndex().associate { (index, name) -> name to StringId.of(index.toUInt()) }
        val anyType = TypeRef.Local(TypeId.of(ANY_RUNTIME_TYPE))
        val string = ValueType.Ref(false, TypeRef.Local(TypeId.of(STRING_RUNTIME_TYPE)))
        val unit = ValueType.Ref(false, TypeRef.Local(TypeId.of(UNIT_RUNTIME_TYPE)))
        val owners = listOf(ANY_RUNTIME_TYPE, STRING_RUNTIME_TYPE) + scalarBoxes.map { it.type } + UNIT_RUNTIME_TYPE
        val methods = owners.flatMap { owner -> listOf(owner to "toString", owner to "hashCode", owner to "equals") }
        val blockCounts = methods.map { (owner, name) -> if (owner == DOUBLE_BOX_RUNTIME_TYPE && name == "equals") 6u else 3u }
        val blockStarts = blockCounts.runningFold(0u, UInt::plus)
        val initializerId = methods.size.toUInt()
        val signatures =
            methods.map { (owner, name) ->
                NominalType.Function(
                    requireNotNull(ids[name]),
                    false,
                    when (name) {
                        "toString" -> string
                        "equals" -> ValueType.Bool
                        else -> ValueType.I32
                    },
                    listOf(ValueType.Ref(false, TypeRef.Local(TypeId.of(owner)))) +
                        if (name == "equals") listOf(ValueType.Ref(true, anyType)) else emptyList(),
                )
            } + NominalType.Function(requireNotNull(ids["<unit-init>"]), false, ValueType.Unit, emptyList())
        val blocks =
            methods.flatMapIndexed { index, (owner, name) ->
                val receiver = RegisterId.of(0u)
                val destination = RegisterId.of(1u)
                if (name == "equals") {
                    val other = RegisterId.of(1u)
                    val result = RegisterId.of(2u)
                    val cast = RegisterId.of(3u)
                    val ownerType = TypeRef.Local(TypeId.of(owner))
                    val start = blockStarts[index]
                    val box = scalarBoxes.singleOrNull { it.type == owner }
                    if (box?.valueType == ValueType.F64) {
                        val left = RegisterId.of(4u)
                        val right = RegisterId.of(5u)
                        val leftHash = RegisterId.of(6u)
                        val rightHash = RegisterId.of(7u)
                        val field = FieldRef.Local(FieldId.of(box.fieldIndex))

                        fun block(instructions: List<Instruction>) = Block(FunctionId.of(index.toUInt()), false, instructions)

                        fun branch(
                            yes: UInt,
                            no: UInt,
                        ) = Instruction.Branch(result, BlockId.of(start + yes), BlockId.of(start + no))
                        // Hash agreement distinguishes zero signs; IEEE equality and both-NaN checks
                        // prevent folded Double hash collisions from becoming equality.
                        return@flatMapIndexed listOf(
                            block(listOf(Instruction.IsType(result, other, ownerType), branch(1u, 5u))),
                            block(
                                listOf(
                                    Instruction.CheckedCast(cast, other, ownerType),
                                    Instruction.FieldGet(left, receiver, field),
                                    Instruction.FieldGet(right, cast, field),
                                    Instruction.ValueHash(HashValueType.F64, leftHash, left),
                                    Instruction.ValueHash(HashValueType.F64, rightHash, right),
                                    Instruction.Equal(ScalarValueType.I32, result, leftHash, rightHash),
                                    branch(2u, 5u),
                                ),
                            ),
                            block(listOf(Instruction.Equal(ScalarValueType.F64, result, left, right), branch(5u, 3u))),
                            block(
                                listOf(
                                    Instruction.Equal(ScalarValueType.F64, result, left, left),
                                    Instruction.Const(RegisterId.of(8u), ConstantId.of(0u)),
                                    Instruction.Equal(ScalarValueType.BOOL, result, result, RegisterId.of(8u)),
                                    branch(4u, 5u),
                                ),
                            ),
                            block(
                                listOf(
                                    Instruction.Equal(ScalarValueType.F64, result, right, right),
                                    Instruction.Equal(ScalarValueType.BOOL, result, result, RegisterId.of(8u)),
                                    Instruction.Jump(
                                        BlockId.of(start + 5u),
                                    ),
                                ),
                            ),
                            block(listOf(Instruction.Return(Destination.Register(result)))),
                        )
                    }
                    val compare =
                        when {
                            owner == STRING_RUNTIME_TYPE -> {
                                listOf(Instruction.CheckedCast(cast, other, ownerType), Instruction.StringEquals(result, receiver, cast))
                            }

                            box != null -> {
                                val left = RegisterId.of(4u)
                                val right = RegisterId.of(5u)
                                val field = FieldRef.Local(FieldId.of(box.fieldIndex))
                                listOf(
                                    Instruction.CheckedCast(cast, other, ownerType),
                                    Instruction.FieldGet(left, receiver, field),
                                    Instruction.FieldGet(right, cast, field),
                                ) +
                                    if (box.valueType == ValueType.F32) {
                                        listOf(
                                            // Float hashes are canonical IEEE bits, so equality here is lossless.
                                            Instruction.ValueHash(HashValueType.F32, RegisterId.of(6u), left),
                                            Instruction.ValueHash(HashValueType.F32, RegisterId.of(7u), right),
                                            Instruction.Equal(ScalarValueType.I32, result, RegisterId.of(6u), RegisterId.of(7u)),
                                        )
                                    } else {
                                        listOf(
                                            Instruction.Equal(
                                                when (box.valueType) {
                                                    ValueType.I32 -> ScalarValueType.I32
                                                    ValueType.I64 -> ScalarValueType.I64
                                                    ValueType.Bool -> ScalarValueType.BOOL
                                                    ValueType.Char -> ScalarValueType.CHAR
                                                    else -> error("unsupported box equality")
                                                },
                                                result,
                                                left,
                                                right,
                                            ),
                                        )
                                    }
                            }

                            else -> {
                                listOf(Instruction.RefEqual(result, receiver, other))
                            }
                        }
                    val first =
                        if (box != null || owner == STRING_RUNTIME_TYPE) {
                            listOf(
                                Instruction.IsType(result, other, ownerType),
                                Instruction.Branch(
                                    result,
                                    BlockId.of(start + 1u),
                                    BlockId.of(start + 2u),
                                ),
                            )
                        } else {
                            listOf(Instruction.Jump(BlockId.of(start + 1u)))
                        }
                    return@flatMapIndexed listOf(
                        Block(FunctionId.of(index.toUInt()), false, first),
                        Block(FunctionId.of(index.toUInt()), false, compare + Instruction.Jump(BlockId.of(start + 2u))),
                        Block(FunctionId.of(index.toUInt()), false, listOf(Instruction.Return(Destination.Register(result)))),
                    )
                }
                val instructions =
                    if (name == "hashCode") {
                        when (owner) {
                            STRING_RUNTIME_TYPE -> {
                                listOf(Instruction.StringHash(destination, receiver), Instruction.Return(Destination.Register(destination)))
                            }

                            ANY_RUNTIME_TYPE, UNIT_RUNTIME_TYPE -> {
                                listOf(
                                    Instruction.ValueHash(HashValueType.REFERENCE, destination, receiver),
                                    Instruction.Return(Destination.Register(destination)),
                                )
                            }

                            else -> {
                                val box = scalarBoxes.single { it.type == owner }
                                val field = box.fieldIndex
                                val scalar = RegisterId.of(2u)
                                listOf(
                                    Instruction.FieldGet(scalar, receiver, FieldRef.Local(FieldId.of(field))),
                                    Instruction.ValueHash(scalarHashForm(box.valueType), destination, scalar),
                                    Instruction.Return(Destination.Register(destination)),
                                )
                            }
                        }
                    } else {
                        when (owner) {
                            STRING_RUNTIME_TYPE -> {
                                listOf(Instruction.Return(Destination.Register(receiver)))
                            }

                            UNIT_RUNTIME_TYPE -> {
                                listOf(
                                    Instruction.Const(destination, ConstantId.of(1u)),
                                    Instruction.Return(Destination.Register(destination)),
                                )
                            }

                            ANY_RUNTIME_TYPE -> {
                                listOf(
                                    Instruction.StringValueOf(StringValueType.REFERENCE, destination, receiver),
                                    Instruction.Return(Destination.Register(destination)),
                                )
                            }

                            else -> {
                                val box = scalarBoxes.single { it.type == owner }
                                val scalar = RegisterId.of(2u)
                                val field = box.fieldIndex
                                listOf(
                                    Instruction.FieldGet(scalar, receiver, FieldRef.Local(FieldId.of(field))),
                                    Instruction.StringValueOf(
                                        box.primitive.stringForm,
                                        destination,
                                        scalar,
                                    ),
                                    Instruction.Return(Destination.Register(destination)),
                                )
                            }
                        }
                    }
                val fieldRead = instructions.takeWhile { it is Instruction.FieldGet }
                listOf(
                    Block(FunctionId.of(index.toUInt()), false, fieldRead + Instruction.Jump(BlockId.of(blockStarts[index] + 1u))),
                    Block(
                        FunctionId.of(index.toUInt()),
                        false,
                        instructions.drop(fieldRead.size).dropLast(1) + Instruction.Jump(BlockId.of(blockStarts[index] + 2u)),
                    ),
                    Block(FunctionId.of(index.toUInt()), false, listOf(instructions.last())),
                )
            } +
                Block(
                    FunctionId.of(initializerId),
                    false,
                    listOf(
                        Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(TypeId.of(UNIT_RUNTIME_TYPE))),
                        Instruction.StaticSet(FieldRef.Local(FieldId.of(8u)), RegisterId.of(0u)),
                        Instruction.Return(Destination.Unit),
                    ),
                )
        val functions =
            methods.mapIndexed { index, (owner, name) ->
                val receiverType = ValueType.Ref(false, TypeRef.Local(TypeId.of(owner)))
                val box = scalarBoxes.singleOrNull { it.type == owner }
                val values =
                    if (name == "equals") {
                        listOf(receiverType, ValueType.Ref(true, anyType), ValueType.Bool) +
                            if (box != null || owner == STRING_RUNTIME_TYPE) {
                                listOf(receiverType) +
                                    (
                                        box?.let {
                                            listOf(it.valueType, it.valueType) +
                                                if (it.valueType ==
                                                    ValueType.F64
                                                ) {
                                                    listOf(ValueType.I32, ValueType.I32, ValueType.Bool)
                                                } else if (it.valueType ==
                                                    ValueType.F32
                                                ) {
                                                    listOf(ValueType.I32, ValueType.I32)
                                                } else {
                                                    emptyList()
                                                }
                                        }
                                            ?: emptyList()
                                    )
                            } else {
                                emptyList()
                            }
                    } else {
                        listOf(receiverType, if (name == "toString") string else ValueType.I32) +
                            scalarBoxes.filter { it.type == owner }.map { it.valueType }
                    }
                Function(
                    TypeRef.Local(TypeId.of(owner)),
                    requireNotNull(ids[name]),
                    TypeRef.Local(TypeId.of((runtimeTypeNames.size + index).toUInt())),
                    setOf(FunctionFlag.VIRTUAL),
                    values.map(FunctionValue::scalar),
                    if (name == "equals") 2u else 1u,
                    BlockId.of(blockStarts[index]),
                    blockCounts[index],
                    0u,
                    0u,
                )
            } +
                Function(
                    TypeRef.Local(TypeId.of(UNIT_RUNTIME_TYPE)),
                    requireNotNull(ids["<unit-init>"]),
                    TypeRef.Local(TypeId.of(runtimeTypeNames.size.toUInt() + initializerId)),
                    setOf(FunctionFlag.STATIC),
                    listOf(FunctionValue.scalar(unit)),
                    0u,
                    BlockId.of(blockStarts.last()),
                    1u,
                    0u,
                    0u,
                )
        return Module(
            name = requireNotNull(ids["kotlin.Any"]),
            utf16Literals = listOf(Utf16Literal.of(*"kotlin.Unit".map { it.code }.toIntArray())),
            constants = listOf(Constant.Bool(false), Constant.StringLiteral(Utf16LiteralId.of(0u))),
            functions = functions,
            blocks = blocks,
            kind = ModuleKind.LIBRARY,
            strings = names.map(MetadataText::of),
            types =
                listOf(
                    NominalType.Array(name = requireNotNull(ids["kotlin.CharArray"]), element = ValueType.Char, superType = anyType),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.String"]),
                        final = true,
                        superType = anyType,
                        methodStart = 3u,
                        methodCount = 3u,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.Throwable"]),
                        superType = anyType,
                        throwableRoot = true,
                        fieldStart = 1u,
                        fieldCount = 2u,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.IllegalArgumentException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.ILLEGAL_ARGUMENT,
                    ),
                    NominalType.Array(name = requireNotNull(ids["kotlin.IntArray"]), element = ValueType.I32, superType = anyType),
                    NominalType.Class(name = requireNotNull(ids["kotlin.Any"]), methodStart = 0u, methodCount = 3u),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.Int"]),
                        final = true,
                        superType = anyType,
                        fieldStart = 0u,
                        fieldCount = 1u,
                        methodStart = 6u,
                        methodCount = 3u,
                    ),
                    NominalType.Class(name = requireNotNull(ids["kotlin.Exception"]), superType = TypeRef.Local(TypeId.of(2u))),
                    NominalType.Class(name = requireNotNull(ids["kotlin.RuntimeException"]), superType = TypeRef.Local(TypeId.of(7u))),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.IllegalStateException"]),
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.ILLEGAL_STATE,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.NoWhenBranchMatchedException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.ArithmeticException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.ARITHMETIC,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.IndexOutOfBoundsException"]),
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.INDEX_OUT_OF_BOUNDS,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.NegativeArraySizeException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.NEGATIVE_ARRAY_SIZE,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.NullPointerException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.NULL_POINTER,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["kotlin.ClassCastException"]),
                        final = true,
                        superType = TypeRef.Local(TypeId.of(8u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.CLASS_CAST,
                    ),
                    NominalType.Class(
                        name = requireNotNull(ids["compukter.io.IOException"]),
                        superType = TypeRef.Local(TypeId.of(7u)),
                        runtimeExceptionKind = ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind.IO,
                    ),
                ) +
                    scalarBoxes.drop(1).take(5).mapIndexed { index, box ->
                        NominalType.Class(
                            requireNotNull(ids[runtimeTypeNames[box.type.toInt()]]),
                            final = true,
                            superType = anyType,
                            fieldStart = (index + 3).toUInt(),
                            fieldCount = 1u,
                            methodStart = ((index + 3) * 3).toUInt(),
                            methodCount = 3u,
                        )
                    } +
                    NominalType.Class(
                        requireNotNull(ids["kotlin.Unit"]),
                        final = true,
                        superType = anyType,
                        fieldStart = 8u,
                        fieldCount = 1u,
                        methodStart = ((owners.size - 1) * 3).toUInt(),
                        methodCount = 4u,
                        initializer = FunctionId.of(initializerId),
                    ) +
                    NominalType.Array(
                        name = requireNotNull(ids["kotlin.DoubleArray"]),
                        element = ValueType.F64,
                        superType = anyType,
                    ) +
                    scalarBoxes.drop(6).mapIndexed { index, box ->
                        NominalType.Class(
                            requireNotNull(ids[box.primitive.qualifiedName]),
                            final = true,
                            superType = anyType,
                            fieldStart = box.fieldIndex,
                            fieldCount = 1u,
                            methodStart = ((index + 8) * 3).toUInt(),
                            methodCount = 3u,
                        )
                    } +
                    GuestPrimitive.entries.filter { it.arrayType >= 30u }.sortedBy { it.arrayType }.map { primitive ->
                        NominalType.Array(
                            name = requireNotNull(ids[primitive.arrayName]),
                            element = primitive.scalar,
                            superType = anyType,
                            storage = primitive.arrayStorage,
                        )
                    } + signatures,
            fields =
                listOf(
                    Field(
                        owner = TypeRef.Local(TypeId.of(INT_BOX_RUNTIME_TYPE)),
                        name = requireNotNull(ids[INT_BOX_VALUE_NAME]),
                        type = ValueType.I32,
                        mutable = true,
                        static = false,
                    ),
                    Field(
                        owner = TypeRef.Local(TypeId.of(2u)),
                        name = requireNotNull(ids[THROWABLE_MESSAGE_NAME]),
                        type = ValueType.Ref(true, TypeRef.Local(TypeId.of(1u))),
                        mutable = true,
                        static = false,
                    ),
                    Field(
                        owner = TypeRef.Local(TypeId.of(2u)),
                        name = requireNotNull(ids[THROWABLE_CAUSE_NAME]),
                        type = ValueType.Ref(true, TypeRef.Local(TypeId.of(2u))),
                        mutable = true,
                        static = false,
                    ),
                ) +
                    scalarBoxes.drop(1).take(5).map { box ->
                        Field(
                            TypeRef.Local(TypeId.of(box.type)),
                            requireNotNull(ids[box.name]),
                            box.valueType,
                            mutable = true,
                            static = false,
                        )
                    } +
                    Field(
                        TypeRef.Local(TypeId.of(UNIT_RUNTIME_TYPE)),
                        requireNotNull(ids[UNIT_INSTANCE_NAME]),
                        unit,
                        mutable = true,
                        static = true,
                    ) +
                    scalarBoxes.drop(6).map { box ->
                        Field(
                            TypeRef.Local(TypeId.of(box.type)),
                            requireNotNull(ids[box.name]),
                            box.valueType,
                            mutable = true,
                            static = false,
                        )
                    },
            exports =
                (
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
                        ) +
                        listOf(THROWABLE_MESSAGE_NAME, THROWABLE_CAUSE_NAME).mapIndexed { index, name ->
                            Export(
                                kind = SymbolKind.FIELD,
                                visibility = ExportVisibility.PUBLIC_LIBRARY,
                                name = requireNotNull(ids[name]),
                                localSymbol = (index + 1).toUInt(),
                                signature = TypeRef.Local(TypeId.of(2u)),
                            )
                        } +
                        scalarBoxes.drop(1).map { box ->
                            Export(
                                SymbolKind.FIELD,
                                ExportVisibility.PUBLIC_LIBRARY,
                                requireNotNull(ids[box.name]),
                                box.fieldIndex,
                                TypeRef.Local(TypeId.of(box.type)),
                            )
                        } +
                        Export(
                            SymbolKind.FIELD,
                            ExportVisibility.PUBLIC_LIBRARY,
                            requireNotNull(ids[UNIT_INSTANCE_NAME]),
                            8u,
                            TypeRef.Local(TypeId.of(UNIT_RUNTIME_TYPE)),
                        ) +
                        Export(
                            SymbolKind.FUNCTION,
                            ExportVisibility.PUBLIC_LIBRARY,
                            requireNotNull(ids[ANY_TO_STRING_NAME]),
                            0u,
                            TypeRef.Local(TypeId.of(runtimeTypeNames.size.toUInt() + 0u)),
                        ) +
                        Export(
                            SymbolKind.FUNCTION,
                            ExportVisibility.PUBLIC_LIBRARY,
                            requireNotNull(ids[ANY_HASH_CODE_NAME]),
                            1u,
                            TypeRef.Local(TypeId.of(runtimeTypeNames.size.toUInt() + 1u)),
                        ) +
                        Export(
                            SymbolKind.FUNCTION,
                            ExportVisibility.PUBLIC_LIBRARY,
                            requireNotNull(ids[ANY_EQUALS_NAME]),
                            2u,
                            TypeRef.Local(TypeId.of(runtimeTypeNames.size.toUInt() + 2u)),
                        )
                ).sortedWith(compareBy({ it.kind.ordinal }, { names[it.name.value.toInt()] })),
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

private fun IrConstructor.platformConstructorSignature(): String =
    "constructor(${parameters.filter { it.kind == IrParameterKind.Regular }.joinToString(",") { it.type.canonicalPlatformType() }})"

private fun closureName(ordinal: Int): String = "app.<lambda-$ordinal>"

private fun closureCaptureName(ordinal: Int): String = "<capture-$ordinal>"

private fun captureCellName(ordinal: Int): String = "app.<capture-cell-$ordinal>"

private data class CompiledFunction(
    val localTypes: List<ValueType>,
    val blocks: List<Block>,
    val debug: List<DebugEntry> = emptyList(),
    val exceptions: List<ExceptionEntry> = emptyList(),
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
    val singletons: Map<IrClassSymbol, ExternalFieldTarget>,
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
    val singletons = linkedMapOf<IrClassSymbol, ExternalFieldTarget>()
    val classSymbols = linkedMapOf<String, IrClassSymbol>()
    val neededDefaultArguments = mutableListOf<PlatformDefaultArgument>()

    fun considerTypeSymbol(symbol: IrClassSymbol) {
        val fqName = symbol.owner.fqNameWhenAvailable?.asString() ?: return
        classSymbols[fqName] = symbol
        if (GuestPrimitive.entries.any { fqName == it.arrayName || fqName == it.qualifiedName }) return
        val link = typeLinks[fqName] ?: return
        types[symbol] = ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
    }

    fun considerType(type: IrType) {
        val simple = type as? IrSimpleType ?: return
        simple.arguments.filterIsInstance<IrTypeProjection>().forEach { considerType(it.type) }
        val symbol = simple.classifier as? IrClassSymbol ?: return
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
                if (element is IrClass) element.superTypes.forEach(::considerType)
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                considerType(expression.type)
                val target = expression.symbol.owner
                considerType(target.returnType)
                target.parameters.forEach { considerType(it.type) }
                val targetSymbol = target.fqNameWhenAvailable?.asString()
                val targetSignature = target.canonicalPlatformSignature()
                (
                    session.platformDefaults[
                        PlatformDeclarationIdentity(
                            targetSymbol.orEmpty(),
                            targetSignature,
                        ),
                    ] ?: session.platformFunctions
                        .singleOrNull { link -> link.symbol == targetSymbol && link.signature == targetSignature }
                        ?.defaultArguments
                )?.filterNotNull()
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

            override fun visitGetObjectValue(expression: IrGetObjectValue) {
                val owner = expression.symbol.owner
                owner.fqNameWhenAvailable?.asString()?.let { name ->
                    fieldTarget("$name.<instance>", expression.symbol)?.let { field ->
                        if (!field.static) throw UnsupportedKotlinIr(expression, "platform singleton resolves to an instance field")
                        singletons[expression.symbol] = field
                    }
                }
                super.visitGetObjectValue(expression)
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
    return LinkedPlatformSymbols(types, fieldsByGetter, enumEntries, singletons, defaultEnumEntries, defaultIntValues)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun linkedPlatformFunctions(
    elements: List<IrElement>,
    session: CompilationSession,
): Map<IrFunctionSymbol, ExternalFunctionTarget> {
    val result = linkedMapOf<IrFunctionSymbol, ExternalFunctionTarget>()
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

            private fun constructor(target: IrConstructor) {
                val symbol = "${target.parentAsClass.fqNameWhenAvailable}.<init>"
                session.platformFunctions
                    .singleOrNull { it.symbol == symbol && it.signature == target.platformConstructorSignature() }
                    ?.let { link ->
                        result[target.symbol] = ExternalFunctionTarget(link.exportName, link.moduleHash.copyOf())
                    }
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                constructor(expression.symbol.owner)
                super.visitConstructorCall(expression)
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
                constructor(expression.symbol.owner)
                super.visitDelegatingConstructorCall(expression)
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
    private val doubleArrayType: ValueType,
    private val stringArrayType: ValueType,
    private val guestTypes: GuestTypeRegistry,
    private val unitType: IrType,
    private val kotlinStringType: IrType,
    private val kotlinCharArrayClass: IrClassSymbol,
    private val kotlinIntArrayClass: IrClassSymbol,
    private val kotlinDoubleArrayClass: IrClassSymbol,
    private val intType: IrType,
    private val longType: IrType,
    private val floatType: IrType,
    private val doubleType: IrType,
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
    private val singletonLayouts: Map<IrClassSymbol, GuestClassLayout>,
    private val externalSingletons: Map<IrClassSymbol, ExternalFieldTarget>,
    private val externalFieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    private val externalEnumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    private val externalDefaultEnumEntries: Map<String, ExternalFieldTarget>,
    private val externalFunctions: Map<IrFunctionSymbol, ExternalFunctionTarget>,
    private val functionTypes: Map<GuestFunctionShape, TypeRef.Local>,
    private val invokeFunctionIds: Map<GuestFunctionShape, FunctionId>,
    private val taskLaunchTrampolineFunctionId: FunctionId?,
    private val closureLayouts: Map<IrExpression, GuestClosureLayout>,
    private val captureCells: Map<IrValueSymbol, GuestCaptureCellLayout>,
    private val leadingParameterTypes: List<ValueType> = emptyList(),
    private val captureFields: Map<IrValueSymbol, GuestClosureCapture> = emptyMap(),
    private val closureReceiver: RegisterId? = null,
    private val constructorOwner: GuestClassLayout? = null,
    private val constructorDeclaration: IrClass? = constructorOwner?.declaration,
    private val valueClassConstructorReference: InlineValueClassLayout? = null,
) {
    private val localTypes = mutableListOf<ValueType>()
    private val values = mutableMapOf<IrValueSymbol, RegisterId>()
    private val blocks = mutableListOf(MutableBlock())
    private val debug = mutableListOf<DebugEntry>()
    private val exceptions = mutableListOf<ExceptionEntry>()
    private var activeSource: IrElement = function
    private val loopContexts = ArrayDeque<LoopContext>()
    private val returnableContexts = mutableMapOf<IrReturnTargetSymbol, ReturnableContext>()
    private val finallyContexts = ArrayDeque<FinallyContext>()
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
        constructorDeclaration?.thisReceiver?.let { receiver ->
            values[receiver.symbol] = RegisterId.of(0u)
        }
        if (valueClassConstructorReference != null) {
            val layout = valueClassConstructorReference
            val box = requireNotNull(guestTypes.valueClassBox(resolvedType(function.returnType)))
            val arguments = sourceParameters.map { requireNotNull(values[it.symbol]) }
            val result =
                if (box.inlineType == null) {
                    arguments.single()
                } else {
                    allocate(box.scalar).also {
                        emit(Instruction.InlineConstruct(it, arguments.flatMap(::inlineComponents)))
                    }
                }
            layout.intRange?.let { emitIntRangePrecondition(result, it, function) }
            compileValueClassInitializers(layout, requireNotNull(currentClassInstance), result, arguments)
            emit(Instruction.Return(Destination.Register(result)))
        } else if ((function as? IrSimpleFunction)?.isGeneratedDataValueMethod() == true) {
            if (function.name.asString() == "hashCode") compileDataHash() else compileDataEquals()
        } else {
            val body = function.body as? IrBlockBody ?: throw UnsupportedKotlinIr(function, "function body is not a block")
            body.statements.forEach(::compileStatement)
        }
        if (blocks[currentBlock].instructions.lastOrNull()?.isTerminator() != true) emit(Instruction.Return(Destination.Unit))
        return CompiledFunction(
            localTypes.toList(),
            blocks.map { Block(functionId, it.loopHeaderSafepoint, it.instructions.toList()) },
            debug.toList(),
            exceptions.toList(),
        )
    }

    private fun compileStatement(statement: IrElement) {
        withSource(statement) { compileStatementBody(statement) }
    }

    private inline fun <T> withSource(
        element: IrElement,
        action: () -> T,
    ): T {
        val previous = activeSource
        if (element.startOffset >= 0 && element.endOffset >= element.startOffset) activeSource = element
        return try {
            action()
        } finally {
            activeSource = previous
        }
    }

    private fun compileStatementBody(statement: IrElement) {
        if (isTerminated()) return
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
                    emit(
                        Instruction.FieldSet(
                            destination,
                            FieldRef.Local(cell.fieldId),
                            storedValue(source, statement.type, cell.valueType),
                        ),
                    )
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
                            storedValue(source, statement.value.type, cell.valueType),
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
                emit(Instruction.FieldSet(receiver, FieldRef.Local(field.id), storedValue(value, statement.value.type, field.type)))
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
                compileReturn(statement)
            }

            is IrReturnableBlock -> {
                compileReturnableBlock(statement)
            }

            is IrWhen -> {
                compileWhenStatement(statement)
            }

            is IrTry -> {
                compileTry(statement, asValue = false)
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

            is IrGetObjectValue -> {
                if (statement.type != unitType) compileExpression(statement)
            }

            is IrExpression -> {
                compileExpression(statement)
            }

            else -> {
                throw UnsupportedKotlinIr(statement, "unsupported statement ${statement::class.simpleName}")
            }
        }
    }

    private fun compileExceptionArguments(
        target: IrConstructor,
        arguments: List<IrExpression?>,
        element: IrElement,
    ): Pair<RegisterId, RegisterId?> {
        val parameters = target.parameters.filter { it.kind == IrParameterKind.Regular }
        if (parameters.isEmpty()) {
            val message = allocate(ValueType.Ref(true, TypeRef.Imported(ImportId.of(1u))))
            emit(Instruction.Null(message))
            return message to null
        }
        if (parameters.size !in 1..2) throw UnsupportedKotlinIr(element, "unsupported exception constructor signature")
        val compiled =
            target.parameters.mapIndexedNotNull { index, parameter ->
                if (parameter.kind != IrParameterKind.Regular) return@mapIndexedNotNull null
                val argument = arguments.getOrNull(index) ?: throw UnsupportedKotlinIr(element, "missing exception constructor argument")
                compileExpression(argument, parameter.type)
            }
        return compiled.first() to compiled.getOrNull(1)
    }

    private fun compileTry(
        expression: IrTry,
        asValue: Boolean,
    ): RegisterId? {
        val cleanup = expression.finallyExpression?.let { FinallyContext(it) }
        val destination =
            if (asValue && expression.type != unitType && !expression.type.isNothing()) {
                allocate(valueType(expression.type, expression))
            } else {
                null
            }
        val exits = mutableListOf<Int>()

        fun compileBranch(branch: IrExpression) {
            if (destination == null || branch.type.isNothing()) {
                compileStatement(branch)
            } else {
                val value = compileExpression(branch, expression.type)
                if (!isTerminated()) emit(Instruction.Move(destination, value))
            }
            if (!isTerminated()) {
                exits += currentBlock
                jumpTo(0) // Patched after the protected body and all handlers have been emitted.
            }
        }
        val protectedStart = createBlock()
        jumpTo(protectedStart)
        currentBlock = protectedStart
        cleanup?.let(finallyContexts::addLast)
        compileBranch(expression.tryResult)
        val protectedCount = blocks.size - protectedStart
        val bodyMayThrow = blocks.subList(protectedStart, blocks.size).any { block -> block.instructions.any { it.mayThrow() } }
        for (handler in if (bodyMayThrow) expression.catches else emptyList()) {
            val type =
                valueType(handler.catchParameter.type, handler) as? ValueType.Ref
                    ?: throw UnsupportedKotlinIr(handler, "catch parameter must be a Throwable reference")
            val exception = allocate(type)
            val handlerBlock = createBlock()
            currentBlock = handlerBlock
            values[handler.catchParameter.symbol] = exception
            compileBranch(handler.result)
            values.remove(handler.catchParameter.symbol)
            exceptions +=
                ExceptionEntry(functionId, blockId(protectedStart), protectedCount.toUInt(), type.type, blockId(handlerBlock), exception)
        }
        val cleanupProtectedEnd = blocks.size
        if (cleanup != null) {
            check(finallyContexts.removeLast() === cleanup)
            if (exits.isNotEmpty()) {
                val normalCleanup = createBlock()
                exits.forEach { patchJumpTarget(it, normalCleanup) }
                exits.clear()
                currentBlock = normalCleanup
                compileStatement(cleanup.expression)
                if (!isTerminated()) {
                    exits += currentBlock
                    jumpTo(0)
                }
            }
            for (exit in cleanup.exits) {
                val exitCleanup = createBlock()
                patchJumpTarget(exit.block, exitCleanup)
                currentBlock = exitCleanup
                compileStatement(cleanup.expression)
                if (!isTerminated()) emitNonlocalExit(exit.targetDepth, exit.source, exit.action)
            }
            if (blocks.subList(protectedStart, cleanupProtectedEnd).any { block -> block.instructions.any { it.mayThrow() } }) {
                val exception = allocate(ValueType.Ref(false, TypeRef.Imported(ImportId.of(2u))))
                val handlerBlock = createBlock()
                currentBlock = handlerBlock
                compileStatement(cleanup.expression)
                if (!isTerminated()) emit(Instruction.Throw(exception))
                exceptions +=
                    ExceptionEntry(
                        functionId,
                        blockId(protectedStart),
                        (cleanupProtectedEnd - protectedStart).toUInt(),
                        null,
                        blockId(handlerBlock),
                        exception,
                    )
            }
        }
        if (exits.isNotEmpty()) {
            val continuation = createBlock()
            exits.forEach { patchJumpTarget(it, continuation) }
            currentBlock = continuation
        }
        return destination
    }

    private fun emitNonlocalExit(
        targetDepth: Int,
        source: IrElement,
        action: () -> Unit,
    ) {
        withSource(source) {
            if (finallyContexts.size > targetDepth) {
                finallyContexts.last().exits += FinallyExit(currentBlock, targetDepth, source, action)
                jumpTo(0)
            } else {
                action()
            }
        }
    }

    private fun compileDelegatingConstructorCall(call: IrDelegatingConstructorCall) {
        if (constructorDeclaration == null) throw UnsupportedKotlinIr(call, "delegating constructor call is outside a constructor")
        val target = call.symbol.owner
        if (target.parentAsClass.fqNameWhenAvailable?.asString() == "kotlin.Any") return
        if (target.parentAsClass.fqNameWhenAvailable?.asString() == "kotlin.Throwable") {
            val arguments = compileExceptionArguments(target, call.arguments, call)
            emit(Instruction.FieldSet(RegisterId.of(0u), FieldRef.Imported(ImportId.of(THROWABLE_MESSAGE_IMPORT)), arguments.first))
            arguments.second?.let {
                emit(Instruction.FieldSet(RegisterId.of(0u), FieldRef.Imported(ImportId.of(THROWABLE_CAUSE_IMPORT)), it))
            }
            return
        }
        externalFunctions[target.symbol]?.let { external ->
            val arguments = compileConstructorArguments(target, call.arguments, GuestClassInstance(target.parentAsClass, emptyList()), call)
            emit(Instruction.Call(Destination.Unit, FunctionRef.Imported(external.importId), listOf(RegisterId.of(0u)) + arguments))
            return
        }
        val targetConstructor =
            constructorLayouts[call.symbol]
                ?: constructorDeclaration.superTypes
                    .firstOrNull { (it as? IrSimpleType)?.classifier == target.parentAsClass.symbol }
                    ?.let { constructorOwner?.instance?.substitute(it) ?: it }
                    ?.let(::resolveClassInstance)
                    ?.let(genericConstructorLayouts::get)
                ?: throw UnsupportedKotlinIr(call, "super constructor is outside the Guest class subset")
        val compiled = compileConstructorArguments(target, call.arguments, targetConstructor.instance, call)
        emit(
            Instruction.Call(
                Destination.Unit,
                FunctionRef.Local(targetConstructor.functionId),
                listOf(RegisterId.of(0u)) + compiled,
            ),
        )
    }

    private fun compileInstanceInitializer(call: IrInstanceInitializerCall) {
        val owner = constructorDeclaration ?: throw UnsupportedKotlinIr(call, "class initializer is outside a constructor")
        owner.declarations.forEach { declaration ->
            when (declaration) {
                is IrProperty -> {
                    val field = constructorOwner?.fields?.firstOrNull { it.property === declaration } ?: return@forEach
                    val initializer =
                        declaration.backingField?.initializer?.expression
                            ?: throw UnsupportedKotlinIr(declaration, "class field initializer is missing")
                    val value = compileExpression(initializer, field.property.backingField?.type)
                    emit(
                        Instruction.FieldSet(
                            RegisterId.of(0u),
                            FieldRef.Local(field.id),
                            storedValue(value, requireNotNull(field.property.backingField).type, field.type),
                        ),
                    )
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
    ): RegisterId = withSource(expression) { compileExpressionBody(expression, expectedType) }

    private fun compileExpressionBody(
        expression: IrExpression,
        expectedType: IrType?,
    ): RegisterId {
        if (expectedType != null) validateHashCollectionView(expression, expectedType)
        val source =
            if (resolvedType(expression.type) == unitType && expectedType?.let { resolvedType(it).isKotlinAny() } == true) {
                if (expression !is IrGetObjectValue) compileStatement(expression)
                loadUnitReference()
            } else {
                compileRawExpression(expression, expectedType)
            }
        val actual = registerValueType(source)
        val targetType = expectedType ?: expression.type
        val target = valueType(targetType, expression)
        if (actual is ValueType.Ref && target !is ValueType.Ref) {
            guestTypes.valueClassBox(resolvedType(targetType))?.let { return unboxValueClass(source, it) }
            GuestPrimitive.scalar(resolvedType(targetType))?.let { return unboxScalar(source, target, it) }
        }
        if (actual !is ValueType.Ref && target is ValueType.Ref && guestTypes.valueClassBox(resolvedType(expression.type)) != null) {
            return boxValue(source, expression.type, target)
        }
        if (expectedType != null && actual is ValueType.Ref) {
            val target = valueType(expectedType, expression)
            if (target is ValueType.Ref && actual != target) {
                return allocate(target).also { destination -> emit(Instruction.CheckedCast(destination, source, target.type)) }
            }
        }
        if (expectedType == null || (
                !resolvedType(expectedType).isKotlinAny() && !resolvedType(expectedType).isNullableScalar() &&
                    !(resolvedType(expectedType).isNullable() && guestTypes.valueClassBox(resolvedType(expectedType)) != null)
            )
        ) {
            return source
        }
        val referenceTarget = valueType(expectedType, expression) as ValueType.Ref
        if (expression is IrConst && expression.value == null) return source
        return if (actual is ValueType.Ref) {
            if (actual == referenceTarget) {
                source
            } else {
                allocate(referenceTarget).also { destination ->
                    emit(Instruction.CheckedCast(destination, source, referenceTarget.type))
                }
            }
        } else {
            boxValue(source, expression.type, referenceTarget)
        }
    }

    private fun validateHashCollectionView(
        expression: IrExpression,
        expectedType: IrType,
    ) {
        val sourceName =
            ((resolvedType(expression.type) as? IrSimpleType)?.classifier as? IrClassSymbol)
                ?.owner
                ?.fqNameWhenAvailable
                ?.asString()
                .orEmpty()
        val targetName =
            ((resolvedType(expectedType) as? IrSimpleType)?.classifier as? IrClassSymbol)
                ?.owner
                ?.fqNameWhenAvailable
                ?.asString()
                .orEmpty()
        if (!sourceName.startsWith("kotlin.collections.Hash") && sourceName !in hashCollectionInterfaces) return
        if (targetName !in specializedCollectionInterfaces) return
        val source = resolveClassInstance(expression.type) ?: return
        val target = resolveClassInstance(expectedType) ?: return
        val inherited = resolveMemberOwner(source, target.declaration) ?: return
        if (inherited.arguments != target.arguments) {
            throw UnsupportedKotlinIr(
                expression,
                "hash collection type argument widening is not supported; retain the original key and element types",
            )
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
                    val stored =
                        allocate(instanceField.type).also { destination ->
                            emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(instanceField.id)))
                        }
                    loadedValue(stored, expression.type, expression)
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

            is IrGetObjectValue -> {
                val singleton = singletonLayouts[expression.symbol]
                if (singleton != null) {
                    allocate(ValueType.Ref(false, TypeRef.Local(singleton.typeId))).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Local(requireNotNull(singleton.singletonFieldId))))
                    }
                } else {
                    val external =
                        externalSingletons[expression.symbol]
                            ?: throw UnsupportedKotlinIr(expression, "object value has no managed singleton instance")
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

            is IrTry -> {
                compileTry(expression, asValue = true)
                    ?: throw UnsupportedKotlinIr(expression, "Unit or Nothing try used as a value")
            }

            is IrReturnableBlock -> {
                compileReturnableBlock(expression)
                    ?: throw UnsupportedKotlinIr(expression, "Unit or Nothing inline block used as a value")
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
            val stored =
                allocate(cell.valueType).also { destination ->
                    emit(Instruction.FieldGet(destination, loadCellReference(symbol, element), FieldRef.Local(cell.fieldId)))
                }
            return loadedValue(stored, symbol.owner.type, element)
        }
        values[symbol]?.let { return it }
        val capture = captureFields[symbol] ?: throw UnsupportedKotlinIr(element, "unknown local value")
        val receiver = closureReceiver ?: throw UnsupportedKotlinIr(element, "closure capture has no environment receiver")
        val stored =
            allocate(capture.type).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(capture.fieldId)))
            }
        return loadedValue(stored, symbol.owner.type, element)
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
            emit(
                Instruction.FieldSet(
                    destination,
                    FieldRef.Local(capture.fieldId),
                    storedValue(
                        value,
                        capture.symbol?.owner?.type ?: requireNotNull(capture.initialValue).type,
                        capture.type,
                    ),
                ),
            )
        }
        return destination
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileConstructor(call: IrConstructorCall): RegisterId {
        val target = call.symbol.owner
        val arguments = call.arguments.filterNotNull()
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
            val box = requireNotNull(guestTypes.valueClassBox(resolvedType(call.type)))
            val simple = resolvedType(call.type) as IrSimpleType
            val instance = GuestClassInstance(layout.declaration, simple.arguments.filterIsInstance<IrTypeProjection>().map { it.type })
            val argumentValues = compileConstructorArguments(target, call.arguments, instance, call)
            if (box.inlineType == null) {
                val value = argumentValues.single()
                layout.intRange?.let { emitIntRangePrecondition(value, it, call) }
                return value
            }
            val components = argumentValues.flatMap(::inlineComponents)
            return allocate(box.scalar).also {
                emit(Instruction.InlineConstruct(it, components))
                compileValueClassInitializers(layout, instance, it, argumentValues)
            }
        }
        val exceptionImport =
            target.parentAsClass
                .runtimeExceptionType()
                ?.takeIf { it == 2u }
                ?.let(ImportId::of)
        if (exceptionImport != null) {
            val (message, cause) = compileExceptionArguments(target, call.arguments, call)
            prepareAllocationBlock()
            val type = TypeRef.Imported(exceptionImport)
            return allocate(ValueType.Ref(nullable = false, type = type)).also { destination ->
                emit(Instruction.NewObject(destination, type))
                emit(Instruction.FieldSet(destination, FieldRef.Imported(ImportId.of(THROWABLE_MESSAGE_IMPORT)), message))
                cause?.let { emit(Instruction.FieldSet(destination, FieldRef.Imported(ImportId.of(THROWABLE_CAUSE_IMPORT)), it)) }
            }
        }
        externalFunctions[target.symbol]?.let { external ->
            val arguments = compileConstructorArguments(target, call.arguments, GuestClassInstance(target.parentAsClass, emptyList()), call)
            val type = (valueType(call.type, call) as ValueType.Ref).type
            prepareAllocationBlock()
            return allocate(ValueType.Ref(nullable = false, type = type)).also { destination ->
                emit(Instruction.NewObject(destination, type))
                emit(Instruction.Call(Destination.Unit, FunctionRef.Imported(external.importId), listOf(destination) + arguments))
            }
        }
        GuestPrimitive.array(resolvedType(call.type))?.let { primitive ->
            return compilePrimitiveArrayConstructor(call, primitive)
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
        val compiledArguments = compileConstructorArguments(target, call.arguments, targetConstructor.instance, call)
        val ownerType = targetConstructor.ownerType
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

    private fun compilePrimitiveArrayConstructor(
        call: IrConstructorCall,
        primitive: GuestPrimitive,
    ): RegisterId {
        val arguments = call.arguments.filterNotNull()
        if (arguments.size !in 1..2 || valueType(arguments[0].type, call) != ValueType.I32) {
            throw UnsupportedKotlinIr(call, "primitive array constructor requires an Int size and optional initializer")
        }
        val length = compileExpression(arguments[0])
        val initializer = arguments.getOrNull(1)?.let(::compileExpression)
        val shape = arguments.getOrNull(1)?.let { resolvedType(it.type).guestFunctionShape() }
        prepareAllocationBlock()
        val arrayType = valueType(call.type, call) as ValueType.Ref
        val array = allocate(arrayType)
        emit(Instruction.NewArray(array, arrayType.type, length))
        if (initializer == null) return array
        val invoke =
            shape?.let(invokeFunctionIds::get)
                ?: throw UnsupportedKotlinIr(call, "primitive array initializer has an unsupported function signature")
        val index = allocate(ValueType.I32).also { emit(Instruction.Move(it, emitI32Constant(0, call))) }
        val condition = createBlock(loopHeader = true)
        val body = createBlock()
        val exit = createBlock()
        jumpTo(condition)
        currentBlock = condition
        val hasNext = allocate(ValueType.Bool).also { emit(Instruction.Less(OrderedScalarValueType.I32, it, index, length)) }
        emit(Instruction.Branch(hasNext, blockId(body), blockId(exit)))
        currentBlock = body
        val element = allocate(primitive.scalar)
        emit(Instruction.CallInterface(Destination.Register(element), FunctionRef.Local(invoke), listOf(initializer, index)))
        emit(Instruction.ArrayStore(array, index, element))
        emit(Instruction.Add(index, index, emitI32Constant(1, call)))
        jumpTo(condition)
        currentBlock = exit
        return array
    }

    private fun compileConstructorArguments(
        target: IrConstructor,
        arguments: List<IrExpression?>,
        instance: GuestClassInstance,
        source: IrElement,
    ): List<RegisterId> {
        val parameters = target.parameters.withIndex().filter { it.value.kind == IrParameterKind.Regular }
        val defaults =
            session.platformDefaults[
                PlatformDeclarationIdentity(
                    "${target.parentAsClass.fqNameWhenAvailable}.<init>",
                    target.platformConstructorSignature(),
                ),
            ]
                ?: session.platformFunctions
                    .singleOrNull {
                        it.symbol == "${target.parentAsClass.fqNameWhenAvailable}.<init>" &&
                            it.signature == target.platformConstructorSignature()
                    }?.defaultArguments
                    .orEmpty()
        val previousBindings = parameters.associate { it.value.symbol to values[it.value.symbol] }
        return try {
            val explicit =
                parameters
                    .mapNotNull { (index, parameter) ->
                        arguments.getOrNull(index)?.let { expression -> Triple(index, parameter, expression) }
                    }.sortedWith(compareBy({ it.third.startOffset.takeIf { offset -> offset >= 0 } ?: Int.MAX_VALUE }, { it.first }))
            explicit.forEach { (_, parameter, expression) ->
                rejectFunctionVariance(expression.type, instance.substitute(parameter.type), expression)
                values[parameter.symbol] = compileExpression(expression, instance.substitute(parameter.type))
            }
            parameters.forEachIndexed { regularIndex, (index, parameter) ->
                if (arguments.getOrNull(index) == null) {
                    defaults.getOrNull(regularIndex)?.let { value ->
                        values[parameter.symbol] =
                            compileCallArgument(ResolvedCallArgument.PlatformDefault(value), instance.substitute(parameter.type))
                        return@forEachIndexed
                    }
                    val default =
                        parameter.defaultValue?.expression
                            ?: throw UnsupportedKotlinIr(source, "constructor argument ${parameter.name} is missing")
                    rejectFunctionVariance(default.type, instance.substitute(parameter.type), default)
                    values[parameter.symbol] = compileExpression(default, parameter.type)
                }
            }
            parameters.map { (_, parameter) ->
                values[parameter.symbol] ?: throw UnsupportedKotlinIr(source, "constructor argument ${parameter.name} is missing")
            }
        } finally {
            previousBindings.forEach { (symbol, previous) ->
                if (previous == null) values.remove(symbol) else values[symbol] = previous
            }
        }
    }

    private fun compileTypeOperator(expression: IrTypeOperatorCall): RegisterId {
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST) {
            rejectFunctionVariance(expression.argument.type, expression.typeOperand, expression)
        }
        val source =
            if (expression.operator == IrTypeOperator.INSTANCEOF &&
                (expression.argument as? IrConst)?.let { it.value == null } == true
            ) {
                allocate(ValueType.Ref(nullable = true, type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))).also {
                    emit(Instruction.Null(it))
                }
            } else if (expression.operator == IrTypeOperator.IMPLICIT_CAST ||
                (
                    expression.operator == IrTypeOperator.CAST &&
                        (
                            expression.typeOperand.isNullableScalar() || expression.typeOperand.isKotlinAny() ||
                                (
                                    valueType(expression.typeOperand, expression) is ValueType.Ref &&
                                        guestTypes.valueClassBox(resolvedType(expression.argument.type)) != null
                                ) ||
                                (
                                    resolvedType(expression.typeOperand).isNullable() &&
                                        guestTypes.valueClassBox(resolvedType(expression.typeOperand)) != null
                                )
                        )
                )
            ) {
                compileExpression(expression.argument, expression.typeOperand)
            } else {
                compileExpression(expression.argument)
            }
        val target = valueType(expression.typeOperand, expression)
        val valueBox = guestTypes.valueClassBox(resolvedType(expression.typeOperand))
        return when (expression.operator) {
            IrTypeOperator.INSTANCEOF -> {
                val reference =
                    if (target == ValueType.Unit) {
                        TypeRef.Imported(ImportId.of(UNIT_RUNTIME_TYPE))
                    } else {
                        valueBox?.type
                            ?: GuestPrimitive
                                .scalar(
                                    resolvedType(expression.typeOperand),
                                )?.let { TypeRef.Imported(ImportId.of(it.boxType)) }
                            ?: run {
                                (target as? ValueType.Ref)?.type
                                    ?: throw UnsupportedKotlinIr(expression, "type test target is not a reference")
                            }
                    }
                val referenceSource =
                    if (registerValueType(source) !is ValueType.Ref) {
                        boxValue(
                            source,
                            expression.argument.type,
                            ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                        )
                    } else {
                        source
                    }
                val matches = allocate(ValueType.Bool)
                emit(Instruction.IsType(matches, referenceSource, reference))
                if (!expression.typeOperand.isNullable()) return matches
                val sourceType =
                    registerValueType(referenceSource) as? ValueType.Ref
                        ?: throw UnsupportedKotlinIr(expression, "nullable type test requires a reference operand")
                val destination = allocate(ValueType.Bool)
                val present = createBlock()
                val absent = createBlock()
                val join = createBlock()
                emit(Instruction.Branch(matches, blockId(present), blockId(absent)))
                currentBlock = present
                emit(Instruction.Move(destination, matches))
                jumpTo(join)
                currentBlock = absent
                val nullValue = allocate(sourceType.copy(nullable = true))
                emit(Instruction.Null(nullValue))
                emit(Instruction.RefEqual(destination, referenceSource, nullValue))
                jumpTo(join)
                currentBlock = join
                destination
            }

            IrTypeOperator.SAFE_CAST -> {
                val resultType =
                    valueType(expression.type, expression) as? ValueType.Ref
                        ?: throw UnsupportedKotlinIr(expression, "safe cast result requires a nullable reference representation")
                val reference =
                    if (registerValueType(source) is ValueType.Ref) {
                        source
                    } else {
                        boxValue(source, expression.argument.type, ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
                    }
                val destination = allocate(resultType)
                val matches = allocate(ValueType.Bool).also { emit(Instruction.IsType(it, reference, resultType.type)) }
                val present = createBlock()
                val absent = createBlock()
                val join = createBlock()
                emit(Instruction.Branch(matches, blockId(present), blockId(absent)))
                currentBlock = present
                emit(Instruction.CheckedCast(destination, reference, resultType.type))
                jumpTo(join)
                currentBlock = absent
                emit(Instruction.Null(destination))
                jumpTo(join)
                currentBlock = join
                destination
            }

            IrTypeOperator.CAST -> {
                if (target == ValueType.Unit) {
                    val unit = TypeRef.Imported(ImportId.of(UNIT_RUNTIME_TYPE))
                    return allocate(ValueType.Ref(false, unit)).also { emit(Instruction.CheckedCast(it, source, unit)) }
                }
                if (expression.typeOperand.isNullableScalar()) {
                    return allocate(target).also { destination ->
                        emit(Instruction.CheckedCast(destination, source, (target as ValueType.Ref).type))
                    }
                }
                if (target is ValueType.Ref) {
                    return allocate(target).also { destination ->
                        emit(Instruction.CheckedCast(destination, source, target.type))
                    }
                }
                if (valueBox != null) {
                    if (registerValueType(source) !is ValueType.Ref &&
                        guestTypes.valueClassBox(resolvedType(expression.argument.type)) == valueBox
                    ) {
                        return source
                    }
                    val reference =
                        if (registerValueType(source) is ValueType.Ref) {
                            source
                        } else {
                            boxValue(
                                source,
                                expression.argument.type,
                                ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                            )
                        }
                    return unboxValueClass(reference, valueBox)
                }
                if (scalarBoxes.none { it.valueType == target } || registerValueType(source) !is ValueType.Ref) {
                    throw UnsupportedKotlinIr(expression, "scalar cast requires a supported box and reference operand")
                }
                unboxScalar(source, target, requireNotNull(GuestPrimitive.scalar(resolvedType(expression.typeOperand))))
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

    private fun storedValue(
        value: RegisterId,
        source: IrType,
        storage: ValueType,
    ): RegisterId =
        if (registerValueType(value) is ValueType.Inline && storage is ValueType.Ref) boxValue(value, source, storage) else value

    private fun loadedValue(
        value: RegisterId,
        source: IrType,
        element: IrElement,
    ): RegisterId =
        if (registerValueType(value) is ValueType.Ref && valueType(source, element) is ValueType.Inline) {
            unboxValueClass(value, requireNotNull(guestTypes.valueClassBox(resolvedType(source))))
        } else {
            value
        }

    private var valueClassInitializerInstance: GuestClassInstance? = null

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileValueClassInitializers(
        layout: InlineValueClassLayout,
        instance: GuestClassInstance,
        receiver: RegisterId,
        arguments: List<RegisterId>,
    ) {
        val declarations = layout.declaration.declarations.filterIsInstance<IrAnonymousInitializer>()
        if (declarations.isEmpty()) return
        val bindings =
            layout.constructor.owner.parameters
                .filter { it.kind == IrParameterKind.Regular }
                .map { it.symbol } +
                requireNotNull(layout.declaration.thisReceiver).symbol
        val previous = bindings.associateWith { values[it] }
        val previousInstance = valueClassInitializerInstance
        try {
            bindings.zip(arguments + receiver).forEach { (symbol, value) -> values[symbol] = value }
            valueClassInitializerInstance = instance
            declarations.forEach { it.body.statements.forEach(::compileStatement) }
        } finally {
            valueClassInitializerInstance = previousInstance
            previous.forEach { (symbol, value) -> if (value == null) values.remove(symbol) else values[symbol] = value }
        }
    }

    private fun inlineComponents(source: RegisterId): List<RegisterId> {
        val type = registerValueType(source)
        if (type !is ValueType.Inline) return listOf(source)
        return guestTypes.inlineComponents(type).mapIndexed { index, component ->
            allocate(component).also { emit(Instruction.InlineComponent(it, source, index.toUShort())) }
        }
    }

    private fun boxValue(
        source: RegisterId,
        sourceType: IrType,
        target: ValueType.Ref,
    ): RegisterId {
        val box =
            guestTypes.valueClassBox(resolvedType(sourceType))
                ?: return boxScalar(source, target, requireNotNull(GuestPrimitive.scalar(resolvedType(sourceType))))
        prepareAllocationBlock()
        val reference = allocate(ValueType.Ref(false, box.type))
        emit(Instruction.NewObject(reference, box.type))
        inlineComponents(source).forEachIndexed { index, component ->
            emit(Instruction.FieldSet(reference, box.fieldAt(index), component))
        }
        return allocate(target).also { emit(Instruction.CheckedCast(it, reference, target.type)) }
    }

    private fun unboxValueClass(
        source: RegisterId,
        box: GuestValueClassBox,
    ): RegisterId {
        val reference = allocate(ValueType.Ref(false, box.type))
        emit(Instruction.CheckedCast(reference, source, box.type))
        val components =
            box.componentTypes.mapIndexed { index, type ->
                allocate(type).also { emit(Instruction.FieldGet(it, reference, box.fieldAt(index))) }
            }
        return if (box.inlineType == null) {
            components.single()
        } else {
            allocate(box.scalar).also {
                emit(Instruction.InlineConstruct(it, components))
            }
        }
    }

    private fun compileReferenceArrayElement(
        expression: IrExpression,
        elementType: IrType?,
    ): RegisterId {
        val value = compileExpression(expression, elementType)
        val box = elementType?.let(::resolvedType)?.let(guestTypes::valueClassBox) ?: return value
        if (registerValueType(value) is ValueType.Ref) return value
        return boxValue(value, expression.type, ValueType.Ref(false, box.type))
    }

    private fun boxScalar(
        source: RegisterId,
        target: ValueType.Ref,
        primitive: GuestPrimitive,
    ): RegisterId {
        val layout = ScalarBox(primitive)
        val boxType = TypeRef.Imported(ImportId.of(layout.type))
        prepareAllocationBlock()
        val box = allocate(ValueType.Ref(nullable = false, type = boxType))
        emit(Instruction.NewObject(box, boxType))
        emit(Instruction.FieldSet(box, FieldRef.Imported(ImportId.of(layout.field)), source))
        return allocate(target).also { destination ->
            emit(Instruction.CheckedCast(destination, box, target.type))
        }
    }

    private fun unboxScalar(
        source: RegisterId,
        type: ValueType,
        primitive: GuestPrimitive,
    ): RegisterId {
        val layout = ScalarBox(primitive)
        val boxType = TypeRef.Imported(ImportId.of(layout.type))
        val box = allocate(ValueType.Ref(nullable = false, type = boxType))
        emit(Instruction.CheckedCast(box, source, boxType))
        return allocate(type).also { destination ->
            emit(Instruction.FieldGet(destination, box, FieldRef.Imported(ImportId.of(layout.field))))
        }
    }

    private fun loadUnitReference(): RegisterId =
        allocate(ValueType.Ref(false, TypeRef.Imported(ImportId.of(UNIT_RUNTIME_TYPE)))).also {
            emit(Instruction.StaticGet(it, FieldRef.Imported(ImportId.of(UNIT_INSTANCE_IMPORT))))
        }

    private fun registerValueType(register: RegisterId): ValueType =
        (leadingParameterTypes + sourceParameters.map { valueType(it.type, it) } + localTypes)[register.value.toInt()]

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
        val actual = resolvedType(actualType).guestFunctionShape()
        val expected = resolvedType(expectedType).guestFunctionShape()
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
        if (expression.type == unitType) {
            if (expression !is IrGetObjectValue) compileStatement(expression)
            return convertToString(loadUnitReference())
        }
        return convertToString(compileStringValue(expression), primitive = GuestPrimitive.scalar(resolvedType(expression.type)))
    }

    private fun compileStringValue(expression: IrExpression): RegisterId {
        val source = compileExpression(expression)
        if (registerValueType(source) is ValueType.Ref || guestTypes.valueClassBox(resolvedType(expression.type)) == null) return source
        return boxValue(source, expression.type, ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
    }

    private fun hashValue(
        source: RegisterId,
        direct: Boolean = false,
    ): RegisterId {
        val type = registerValueType(source)
        val destination = allocate(ValueType.I32)
        if (isTerminated()) return destination
        if (type !is ValueType.Ref) {
            emit(Instruction.ValueHash(scalarHashForm(type), destination, source))
            return destination
        }
        if (type == stringType) {
            emit(Instruction.StringHash(destination, source))
            return destination
        }
        val function = FunctionRef.Imported(ImportId.of(ANY_HASH_CODE_IMPORT))

        fun invoke(value: RegisterId) {
            val receiver = asAnyReference(value, nullable = false)
            if (direct) {
                emit(Instruction.Call(Destination.Register(destination), function, listOf(receiver)))
            } else {
                emit(Instruction.CallVirtual(Destination.Register(destination), function, listOf(receiver)))
            }
        }
        if (!type.nullable) {
            invoke(source)
            return destination
        }
        val absent = allocate(type)
        emit(Instruction.Null(absent))
        val isNull = allocate(ValueType.Bool)
        emit(Instruction.RefEqual(isNull, source, absent))
        val nullBlock = createBlock()
        val valueBlock = createBlock()
        val join = createBlock()
        emit(Instruction.Branch(isNull, blockId(nullBlock), blockId(valueBlock)))
        currentBlock = nullBlock
        emit(Instruction.ValueHash(HashValueType.REFERENCE, destination, absent))
        jumpTo(join)
        currentBlock = valueBlock
        val receiver = allocate(type.copy(nullable = false))
        emit(Instruction.CheckedCast(receiver, source, type.type))
        invoke(receiver)
        jumpTo(join)
        currentBlock = join
        return destination
    }

    private fun compileDataHash() {
        val owner = function.parent as IrClass
        val properties = owner.declarations.filterIsInstance<IrProperty>()
        val names =
            owner.constructors
                .single { it.isPrimary }
                .parameters
                .filter { it.kind == IrParameterKind.Regular }
                .map { it.name }
        var result: RegisterId? = null
        val multiplier = allocate(ValueType.I32).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.I32(31)]))) }
        names.forEach { name ->
            val property = properties.single { it.name == name }
            val symbol = requireNotNull(property.backingField).symbol
            val field =
                fieldsByBacking[symbol] ?: currentClassInstance?.let { genericFieldsByBacking[symbol to it] }
                    ?: throw UnsupportedKotlinIr(property, "data class hash field is unavailable")
            val sourceType = resolvedType(requireNotNull(property.backingField).type)
            val arrayElement =
                GuestPrimitive.array(sourceType.makeNotNull())?.scalar ?: guestTypes.arrayElement(sourceType.makeNotNull())?.let {
                    guestTypes.valueClassBox(it)?.let { box -> ValueType.Ref(it.isNullable(), box.type) } ?: valueType(it, property)
                }
            val value = allocate(field.type)
            emit(Instruction.FieldGet(value, RegisterId.of(0u), FieldRef.Local(field.id)))
            val hash = if (arrayElement == null) hashValue(value) else hashArrayContents(value, arrayElement, multiplier)
            result = result?.let { previous ->
                val product = allocate(ValueType.I32).also { emit(Instruction.Multiply(ScalarValueType.I32, it, previous, multiplier)) }
                allocate(ValueType.I32).also { emit(Instruction.Add(ScalarValueType.I32, it, product, hash)) }
            } ?: hash
        }
        emit(Instruction.Return(Destination.Register(requireNotNull(result))))
    }

    private fun hashArrayContents(
        source: RegisterId,
        elementType: ValueType,
        multiplier: RegisterId,
    ): RegisterId {
        val type = registerValueType(source) as ValueType.Ref
        val result = allocate(ValueType.I32)
        // The exit precedes its callers in block order, so it must admit backward-edge safepoints.
        val exit = createBlock(loopHeader = true)
        val array =
            if (type.nullable) {
                val absent = allocate(type).also { emit(Instruction.Null(it)) }
                val isNull = allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, source, absent)) }
                val nullBlock = createBlock()
                val present = createBlock()
                emit(Instruction.Branch(isNull, blockId(nullBlock), blockId(present)))
                currentBlock = nullBlock
                emit(Instruction.ValueHash(HashValueType.REFERENCE, result, absent))
                jumpTo(exit)
                currentBlock = present
                allocate(type.copy(nullable = false)).also { emit(Instruction.CheckedCast(it, source, type.type)) }
            } else {
                source
            }
        val one = allocate(ValueType.I32).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.I32(1)]))) }
        emit(Instruction.Move(result, one))
        val index = allocate(ValueType.I32).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.I32(0)]))) }
        val length = allocate(ValueType.I32).also { emit(Instruction.ArrayLength(it, array)) }
        val condition = createBlock(loopHeader = true)
        val body = createBlock()
        jumpTo(condition)
        currentBlock = condition
        val hasNext = allocate(ValueType.Bool).also { emit(Instruction.Less(OrderedScalarValueType.I32, it, index, length)) }
        emit(Instruction.Branch(hasNext, blockId(body), blockId(exit)))
        currentBlock = body
        val element = allocate(elementType).also { emit(Instruction.ArrayLoad(it, array, index)) }
        val hash = hashValue(element)
        val product = allocate(ValueType.I32).also { emit(Instruction.Multiply(ScalarValueType.I32, it, result, multiplier)) }
        emit(Instruction.Add(ScalarValueType.I32, result, product, hash))
        emit(Instruction.Add(index, index, one))
        jumpTo(condition)
        currentBlock = exit
        return result
    }

    private fun convertToString(
        source: RegisterId,
        direct: Boolean = false,
        primitive: GuestPrimitive? = null,
    ): RegisterId {
        val type = registerValueType(source)
        if (type == stringType) return source
        val destination = allocate(stringType)
        if (isTerminated()) return destination
        if (type !is ValueType.Ref) {
            val conversion = primitive?.stringForm ?: scalarStringForm(type)
            prepareAllocationBlock()
            emit(Instruction.StringValueOf(conversion, destination, source))
            return destination
        }
        val function = FunctionRef.Imported(ImportId.of(ANY_TO_STRING_IMPORT))

        fun present() {
            val receiver = asAnyReference(source, nullable = false)
            if (direct) {
                emit(Instruction.Call(Destination.Register(destination), function, listOf(receiver)))
            } else {
                emit(Instruction.CallVirtual(Destination.Register(destination), function, listOf(receiver)))
            }
        }
        if (!type.nullable) {
            present()
        } else {
            val nullValue = allocate(type.copy(nullable = true))
            emit(Instruction.Null(nullValue))
            val absent = allocate(ValueType.Bool)
            emit(Instruction.RefEqual(absent, source, nullValue))
            val nullBlock = createBlock()
            val valueBlock = createBlock()
            val join = createBlock()
            emit(Instruction.Branch(absent, blockId(nullBlock), blockId(valueBlock)))
            currentBlock = nullBlock
            prepareAllocationBlock()
            emit(Instruction.StringValueOf(StringValueType.REFERENCE, destination, source))
            jumpTo(join)
            currentBlock = valueBlock
            present()
            jumpTo(join)
            currentBlock = join
        }
        return destination
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
                call.symbol.owner.resolveFakeOverrideMaybeAbstract() ?: call.symbol.owner
            }
        val targetName = target.fqNameWhenAvailable?.asString()
        if (targetName == "kotlin.internal.ir.EQEQEQ") {
            val operands = call.arguments.filterNotNull()
            if (operands.size != 2 || operands.any { !(it is IrConst && it.value == null) && valueType(it.type, it) !is ValueType.Ref }) {
                throw UnsupportedKotlinIr(call, "reference identity requires two reference operands")
            }
            val nullType =
                operands
                    .firstOrNull { !(it is IrConst && it.value == null) }
                    ?.let { valueType(it.type, it) as ValueType.Ref }
                    ?.copy(nullable = true)
                    ?: ValueType.Ref(nullable = true, type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))
            val references =
                operands.map { operand ->
                    if (operand is IrConst && operand.value == null) {
                        allocate(nullType).also { destination ->
                            emit(Instruction.Null(destination))
                        }
                    } else {
                        compileExpression(operand)
                    }
                }
            return allocate(ValueType.Bool).also { destination -> emit(Instruction.RefEqual(destination, references[0], references[1])) }
        }
        if (target.isExternal && targetName in setOf("kotlin.collections.listOf", "kotlin.collections.emptyList")) {
            return compileListFactory(call, targetName == "kotlin.collections.listOf")
        }
        if (target.isExternal && targetName in hashCollectionFactories) {
            return compileHashCollectionFactory(call, requireNotNull(hashCollectionFactories[targetName]))
        }
        if (target.isExternal && targetName == "kotlin.collections.mutableListStorage") {
            return compileMutableListStorage(call)
        }
        if ((
                targetName?.startsWith("kotlin.Function") == true ||
                    targetName?.startsWith("kotlin.reflect.KFunction") == true
            ) &&
            targetName.endsWith(".invoke")
        ) {
            val receiverExpression = dispatchReceiver(call, target, targetName)
            val shape =
                resolvedType(receiverExpression.type).guestFunctionShape()
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
        doubleCompanionConstant(target)?.let { value ->
            return emitF64Constant(value, call)
        }
        inlineValueClasses.getter(target.symbol)?.let { layout ->
            val receiver =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "value class property getter receiver is missing")
            val source = compileExpression(receiver)
            val box = requireNotNull(guestTypes.valueClassBox(resolvedType(receiver.type)))
            if (box.inlineType == null) return source
            val index = layout.getters.indexOf(target.symbol)
            val previousCount =
                box.propertyTypes.take(index).sumOf { type ->
                    guestTypes
                        .valueClassBox(type)
                        ?.takeUnless { type.isNullable() }
                        ?.componentTypes
                        ?.size ?: 1
                }
            val type = valueType(box.propertyTypes[index], call)
            if (type is ValueType.Inline) {
                val leaves = requireNotNull(guestTypes.valueClassBox(box.propertyTypes[index])).componentTypes
                val extracted =
                    leaves.mapIndexed { offset, leaf ->
                        allocate(leaf).also {
                            emit(Instruction.InlineComponent(it, source, (previousCount + offset).toUShort()))
                        }
                    }
                return allocate(type).also { emit(Instruction.InlineConstruct(it, extracted)) }
            }
            return allocate(type).also { emit(Instruction.InlineComponent(it, source, previousCount.toUShort())) }
        }
        if (target.fqNameWhenAvailable?.asString() == "kotlin.internal.ir.CHECK_NOT_NULL") {
            val argument = call.arguments.filterNotNull().single()
            val value = compileExpression(argument)
            val type = registerValueType(value)
            if (type !is ValueType.Ref) return value
            val absent = allocate(type.copy(nullable = true)).also { emit(Instruction.Null(it)) }
            val missing = allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, value, absent)) }
            val failed = createBlock()
            val present = createBlock()
            emit(Instruction.Branch(missing, blockId(failed), blockId(present)))
            currentBlock = failed
            prepareAllocationBlock()
            val npe = TypeRef.Imported(ImportId.of(14u))
            val failure = allocate(ValueType.Ref(false, npe)).also { emit(Instruction.NewObject(it, npe)) }
            emit(Instruction.Throw(failure))
            currentBlock = present
            val box = guestTypes.valueClassBox(resolvedType(call.type))
            if (box != null) return unboxValueClass(value, box)
            return allocate(type.copy(nullable = false)).also { emit(Instruction.CheckedCast(it, value, type.type)) }
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
            val parameterType = target.parameters.single { it.kind == IrParameterKind.Regular }.type
            val value = compileExpression(valueExpression, receiverClassInstance?.substitute(parameterType) ?: parameterType)
            emit(Instruction.FieldSet(receiver, FieldRef.Local(field.id), storedValue(value, valueExpression.type, field.type)))
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
            val stored =
                allocate(field.type).also { destination ->
                    emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(field.id)))
                }
            return loadedValue(stored, call.type, call)
        }
        throwablePropertyImport(target)?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull() ?: throw UnsupportedKotlinIr(call, "Throwable getter receiver is missing")
            val receiver = compileExpression(receiverExpression)
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Imported(ImportId.of(field))))
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
        if (target.name.asString() == "equals" && target.parameters.any { it.kind == IrParameterKind.DispatchReceiver } &&
            target.parameters
                .singleOrNull { it.kind == IrParameterKind.Regular }
                ?.type
                ?.isKotlinAny() == true &&
            call.arguments.filterNotNull().size == 2 &&
            (call.superQualifierSymbol == null || (target.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Any")
        ) {
            val expressions = call.arguments.filterNotNull()
            val left =
                if (expressions[0].type == unitType) {
                    if (expressions[0] !is IrGetObjectValue) compileStatement(expressions[0])
                    loadUnitReference()
                } else {
                    compileExpression(expressions[0])
                }
            val right = compileExpression(expressions[1], target.parameters.single { it.kind == IrParameterKind.Regular }.type)
            val receiver =
                if (registerValueType(left) !is ValueType.Ref &&
                    (
                        guestTypes.valueClassBox(resolvedType(expressions[0].type)) != null ||
                            GuestPrimitive.scalar(resolvedType(expressions[0].type)) != null
                    )
                ) {
                    boxValue(left, expressions[0].type, ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
                } else {
                    left
                }
            return equalsValue(receiver, right, direct = call.superQualifierSymbol != null, nullableOperator = false)
        }
        val memberHashCode =
            target.parameters.none { it.kind == IrParameterKind.Regular } &&
                target.parameters.any { it.kind == IrParameterKind.DispatchReceiver }
        if (target.name.asString() == "hashCode" && (memberHashCode || target.fqNameWhenAvailable?.asString() == "kotlin.hashCode") &&
            call.arguments.filterNotNull().size == 1 && call.type == intType &&
            (call.superQualifierSymbol == null || (target.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Any")
        ) {
            val argument = call.arguments.filterNotNull().single()
            if (argument.type == unitType) {
                if (argument !is IrGetObjectValue) compileStatement(argument)
                return hashValue(loadUnitReference())
            }
            val value = compileExpression(argument)
            val hashSource =
                if (registerValueType(value) is ValueType.Inline) {
                    boxValue(value, argument.type, ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
                } else {
                    value
                }
            return hashValue(hashSource, direct = call.superQualifierSymbol != null)
        }
        val memberToString =
            target.parameters.none { it.kind == IrParameterKind.Regular } &&
                target.parameters.any { it.kind == IrParameterKind.DispatchReceiver }
        val nullableToString = target.fqNameWhenAvailable?.asString() == "kotlin.toString"
        if (target.name.asString() == "toString" && (memberToString || nullableToString) &&
            call.arguments.filterNotNull().size == 1 && call.type == kotlinStringType &&
            (call.superQualifierSymbol == null || (target.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Any")
        ) {
            val argument = call.arguments.filterNotNull().single()
            if (argument.type == unitType) {
                if (argument !is IrGetObjectValue) compileStatement(argument)
                return convertToString(loadUnitReference())
            }
            return convertToString(
                compileStringValue(argument),
                direct = call.superQualifierSymbol != null,
                primitive = GuestPrimitive.scalar(resolvedType(argument.type)),
            )
        }
        if (target.fqNameWhenAvailable?.asString() == "kotlin.String.plus" && call.arguments.filterNotNull().size == 2) {
            val parts = call.arguments.filterNotNull()
            val left = compileExpression(parts[0])
            val right = compileStringPart(parts[1])
            prepareAllocationBlock()
            return allocate(stringType).also { emit(Instruction.StringConcat(it, left, right)) }
        }
        compilePrimitiveArrayFactory(call, target)?.let { return it }
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
            val arguments = mutableListOf<RegisterId>()
            argumentExpressions.zip(loweredParameters(target, session)).forEach { (argument, parameter) ->
                arguments += compileCallArgument(argument, parameter.type, arguments)
            }
            val destination = destinationFor(target.returnType, call)
            if (target.isSuspend) {
                val resume = createBlock()
                emit(Instruction.CallSuspend(destination, FunctionRef.Imported(external.importId), arguments, blockId(resume)))
                currentBlock = resume
            } else {
                val function = FunctionRef.Imported(external.importId)
                val owner = target.parent as? IrClass
                emit(
                    when {
                        call.superQualifierSymbol != null -> {
                            Instruction.Call(destination, function, arguments)
                        }

                        owner?.kind == ClassKind.INTERFACE -> {
                            Instruction.CallInterface(destination, function, arguments)
                        }

                        owner != null && target.requiresVirtualDispatch() -> {
                            Instruction.CallVirtual(destination, function, arguments)
                        }

                        else -> {
                            Instruction.Call(destination, function, arguments)
                        }
                    },
                )
            }
            if (target.returnType.isNothing()) emit(Instruction.Unreachable)
            return (destination as? Destination.Register)?.id
        }
        compileCompareToPredicate(call, target)?.let { return it }
        val targetId = projectFunctionId(call, target)
        if (targetId == null) {
            compileArrayCopyCall(call, target)?.let { return it }
            if (interfaceSuper) {
                throw UnsupportedKotlinIr(call, "interface super target is outside the project subset")
            }
            val argumentExpressions = call.arguments.filterNotNull()
            val universalEquality =
                target.name.asString() in setOf("EQEQ", "equals", "eqeq") &&
                    argumentExpressions.size == 2 &&
                    argumentExpressions.any {
                        resolvedType(it.type).isKotlinAny() || resolvedType(it.type).isNullableScalar() ||
                            resolvedType(it.type).isNullableString() ||
                            ((resolvedType(it.type) as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.kind == ClassKind.INTERFACE ||
                            (resolvedType(it.type).isNullable() && guestTypes.valueClassBox(resolvedType(it.type)) != null)
                    }
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
                    if (index == 2 && arrayStoreElementType != null) {
                        compileReferenceArrayElement(argument, arrayStoreElementType)
                    } else if (argument is IrConst && argument.value == null) {
                        val other = argumentExpressions.firstOrNull { it !== argument && it.type != argument.type }
                        val reference = other?.let { valueType(it.type, it) as? ValueType.Ref }
                        if (reference == null) {
                            compileExpression(argument)
                        } else {
                            allocate(reference.copy(nullable = true)).also { emit(Instruction.Null(it)) }
                        }
                    } else {
                        val compiled = compileExpression(argument)
                        if (universalEquality && scalarBoxes.any { it.valueType == registerValueType(compiled) }) {
                            boxValue(
                                compiled,
                                argument.type,
                                ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                            )
                        } else {
                            compiled
                        }
                    }
                }
            return compileBuiltinCall(call, target, argumentExpressions, arguments)
        }
        val specialization = projectFunctionInstance(call, target)
        val receiverSpecialization =
            resolveClassInstance(call.dispatchReceiver?.type)?.let { receiver ->
                (target.parent as? IrClass)?.let { resolveMemberOwner(receiver, it) } ?: receiver
            }
        val arguments = mutableListOf<RegisterId>()
        resolveProjectCallArguments(call, target).zip(loweredParameters(target, session)).forEach { (argument, parameter) ->
            val parameterType = specialization?.substitute(parameter.type) ?: receiverSpecialization?.substitute(parameter.type)
            arguments += compileCallArgument(argument, parameterType ?: parameter.type, arguments)
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
                    call.superQualifierSymbol != null -> {
                        Instruction.Call(destination, FunctionRef.Local(targetId), arguments)
                    }

                    owner?.kind == ClassKind.INTERFACE -> {
                        Instruction.CallInterface(destination, FunctionRef.Local(targetId), arguments)
                    }

                    owner != null && !inlineValueClasses.contains(owner.symbol) &&
                        target.requiresVirtualDispatch() -> {
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
                if (block is IrFunctionReference || (block is IrRichFunctionReference && block.reflectionTargetSymbol != null)) {
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
        signature: String = target.canonicalPlatformSignature(),
    ): List<ResolvedCallArgument> {
        val platformDefaults =
            session.platformDefaults[PlatformDeclarationIdentity(target.fqNameWhenAvailable?.asString().orEmpty(), signature)]
                ?: session.platformFunctions
                    .singleOrNull { link ->
                        link.symbol == target.fqNameWhenAvailable?.asString() &&
                            link.signature == signature
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
        evaluatedArguments: List<RegisterId> = emptyList(),
    ): RegisterId =
        when (argument) {
            is ResolvedCallArgument.Expression -> {
                rejectFunctionVariance(argument.expression.type, expectedType, argument.expression)
                compileExpression(argument.expression, expectedType)
            }

            is ResolvedCallArgument.PlatformDefault -> {
                when (val value = argument.value) {
                    PlatformDefaultArgument.ReceiverArraySize -> {
                        val receiver =
                            requireNotNull(evaluatedArguments.firstOrNull()) { "array receiver default requires an evaluated receiver" }
                        allocate(ValueType.I32).also { emit(Instruction.ArrayLength(it, receiver)) }
                    }

                    is PlatformDefaultArgument.IntValue -> {
                        val constantId =
                            constantIds[Constant.I32(value.value)]
                                ?: throw IllegalArgumentException("platform Int default is absent from canonical pool: ${value.value}")
                        allocate(ValueType.I32).also { destination ->
                            emit(Instruction.Const(destination, constantId))
                        }
                    }

                    PlatformDefaultArgument.NullValue -> {
                        val type =
                            valueType(expectedType, function) as? ValueType.Ref
                                ?: throw UnsupportedKotlinIr(function, "null default requires a reference parameter")
                        if (!type.nullable) throw UnsupportedKotlinIr(function, "null default requires a nullable parameter")
                        allocate(type).also { emit(Instruction.Null(it)) }
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
            expression.type in setOf(intType, floatType, doubleType, booleanType, charType, kotlinStringType)

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
    private fun compileMutableListStorage(call: IrCall): RegisterId {
        val elementType =
            call.typeArguments.singleOrNull()?.let(::resolvedType)
                ?: throw UnsupportedKotlinIr(call, "mutable list storage requires a concrete element type")
        val primitive = GuestPrimitive.scalar(elementType)?.takeUnless { elementType.isNullable() }
        val name = primitive?.let { "${it.sourceName}MutableListStorage" } ?: "ReferenceMutableListStorage"
        val target =
            if (primitive != null) {
                constructorLayouts.values.singleOrNull {
                    it.instance.declaration.fqNameWhenAvailable
                        ?.asString() == "kotlin.collections.$name"
                }
            } else {
                genericConstructorLayouts.entries
                    .singleOrNull {
                        it.key.declaration.fqNameWhenAvailable
                            ?.asString() == "kotlin.collections.$name" && it.key.arguments == listOf(elementType)
                    }?.value
            } ?: throw UnsupportedKotlinIr(call, "mutable list storage is unavailable for this element type")
        val capacity = compileExpression(call.arguments.filterNotNull().single())
        val ownerType = target.ownerType
        prepareAllocationBlock()
        return allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
            emit(Instruction.NewObject(destination, ownerType))
            emit(Instruction.Call(Destination.Unit, FunctionRef.Local(target.functionId), listOf(destination, capacity)))
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
        val target =
            genericConstructorLayouts.entries
                .singleOrNull {
                    it.key.declaration.fqNameWhenAvailable
                        ?.asString() == "kotlin.collections.ArrayList" &&
                        it.key.arguments == listOf(elementType)
                }?.value ?: throw UnsupportedKotlinIr(call, "ArrayList implementation is unavailable for this element type")
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
        val capacity = allocate(ValueType.I32)
        emit(Instruction.Const(capacity, requireNotNull(constantIds[Constant.I32(elements.size)])))
        val ownerType = target.ownerType
        prepareAllocationBlock()
        val list = allocate(ValueType.Ref(nullable = false, type = ownerType))
        emit(Instruction.NewObject(list, ownerType))
        emit(Instruction.Call(Destination.Unit, FunctionRef.Local(target.functionId), listOf(list, capacity)))
        if (elements.isNotEmpty()) {
            val add =
                genericMemberFunctionIds.entries
                    .singleOrNull { (method, _) ->
                        method.second == target.instance && method.first.owner.name
                            .asString() == "add" &&
                            method.first.owner.parameters
                                .count { it.kind == IrParameterKind.Regular } == 1
                    }?.value ?: throw UnsupportedKotlinIr(call, "ArrayList.add implementation is unavailable")
            val added = allocate(ValueType.Bool)
            elements.forEach { element ->
                val value = compileExpression(element, elementType)
                emit(Instruction.Call(Destination.Register(added), FunctionRef.Local(add), listOf(list, value)))
            }
        }
        return list
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileHashCollectionFactory(
        call: IrCall,
        ownerName: String,
    ): RegisterId {
        val types =
            call.typeArguments.map {
                it?.let(::resolvedType) ?: throw UnsupportedKotlinIr(call, "hash factory requires concrete type arguments")
            }
        val target =
            genericConstructorLayouts.entries
                .singleOrNull {
                    it.key.declaration.fqNameWhenAvailable
                        ?.asString() == ownerName && it.key.arguments == types
                }?.value ?: throw UnsupportedKotlinIr(call, "hash collection implementation is unavailable for these types")
        val elements =
            directVarargElements(
                call,
                "hash collection factories require direct vararg elements",
                "spread hash collection factory arguments are outside the project subset",
            )
        val map = ownerName == "kotlin.collections.HashMap"
        val insertName = if (map) "put" else "add"
        val insert =
            genericMemberFunctionIds.entries.singleOrNull { (method, _) ->
                method.second == target.instance && method.first.owner.name
                    .asString() == insertName
            } ?: throw UnsupportedKotlinIr(call, "hash collection insertion method is unavailable")
        val pairFields =
            if (map && elements.isNotEmpty()) {
                genericFieldsByGetter.entries
                    .filter { (key, field) ->
                        (field.property.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Pair" &&
                            key.second.arguments == types && field.property.name.asString() in setOf("first", "second")
                    }.sortedBy {
                        it.value.property.name
                            .asString()
                    }
            } else {
                emptyList()
            }
        if (map && elements.isNotEmpty() && pairFields.size != 2) {
            throw UnsupportedKotlinIr(call, "Pair fields are unavailable for these types")
        }
        val elementType = (call.arguments.filterNotNull().singleOrNull() as? IrVararg)?.varargElementType?.let(::resolvedType)
        // Kotlin evaluates the complete vararg before entering the factory and invoking user hashing.
        val values = elements.map { compileExpression(it, elementType) }
        val capacity = emitI32Constant(elements.size, call)
        prepareAllocationBlock()
        val collection = allocate(ValueType.Ref(false, target.ownerType))
        emit(Instruction.NewObject(collection, target.ownerType))
        emit(Instruction.Call(Destination.Unit, FunctionRef.Local(target.functionId), listOf(collection, capacity)))
        val resultType = target.instance.substitute(insert.key.first.owner.returnType)
        val inserted = allocate(valueType(resultType, call))
        values.forEach { value ->
            val arguments =
                if (map) {
                    pairFields.map { (_, field) ->
                        val stored = allocate(field.type)
                        emit(Instruction.FieldGet(stored, value, FieldRef.Local(field.id)))
                        loadedValue(stored, types[if (field.property.name.asString() == "first") 0 else 1], call)
                    }
                } else {
                    listOf(value)
                }
            emit(Instruction.Call(Destination.Register(inserted), FunctionRef.Local(insert.value), listOf(collection) + arguments))
        }
        return collection
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
        if (fqName == "kotlin.arrayOfNulls") {
            val length = compileExpression(call.arguments.filterNotNull().single())
            prepareAllocationBlock()
            val reference = arrayType as ValueType.Ref
            return allocate(reference).also { destination -> emit(Instruction.NewArray(destination, reference.type, length)) }
        }
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
        return compileArrayElements(call, arrayType as ValueType.Ref, elements) { compileReferenceArrayElement(it, elementType) }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compilePrimitiveArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val primitive = GuestPrimitive.array(resolvedType(call.type)) ?: return null
        if (target.fqNameWhenAvailable?.asString() != primitive.arrayFactory) return null
        val elements =
            directVarargElements(
                call,
                "primitive array factory requires a direct vararg",
                "spread primitive array arguments are outside the project subset",
            )
        return compileArrayElements(call, valueType(call.type, call) as ValueType.Ref, elements, ::compileExpression)
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

    private fun emitF64Constant(
        value: Double,
        element: IrElement,
    ): RegisterId {
        val id =
            constantIds[Constant.F64(value.toBits().toULong())]
                ?: throw UnsupportedKotlinIr(element, "generated Double constant is absent from canonical pool")
        return allocate(ValueType.F64).also { emit(Instruction.Const(it, id)) }
    }

    private fun widenToF64(
        value: RegisterId,
        type: IrType,
        element: IrElement,
    ): RegisterId = primitiveConvert(value, requireNotNull(GuestPrimitive.scalar(resolvedType(type))), GuestPrimitive.DOUBLE, element)

    private fun widenToF32(
        value: RegisterId,
        sourceType: IrType,
        element: IrElement,
    ): RegisterId = primitiveConvert(value, requireNotNull(GuestPrimitive.scalar(resolvedType(sourceType))), GuestPrimitive.FLOAT, element)

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
        val compareFqName =
            compareCall.symbol.owner.fqNameWhenAvailable
                ?.asString()
        val compared =
            if (compareFqName == "kotlin.String.compareTo" && operands.all { it.type == kotlinStringType }) {
                compileStringCompareTo(compareCall, listOf(left, right))
            } else if (compareFqName == "kotlin.Boolean.compareTo" && operands.all { it.type == booleanType }) {
                compileBooleanCompareTo(compareCall, listOf(left, right))
            } else {
                null
            }
        if (compared != null) {
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
        val primitiveOperands = operands.map { GuestPrimitive.scalar(resolvedType(it.type)) }
        val promoted = promotePrimitives(primitiveOperands)
        if (promoted != null) {
            left = primitiveConvert(left, requireNotNull(primitiveOperands[0]), promoted, call)
            right = primitiveConvert(right, requireNotNull(primitiveOperands[1]), promoted, call)
            return allocate(ValueType.Bool).also { destination ->
                emit(
                    when (predicateName) {
                        "less" -> Instruction.Less(promoted.orderedForm, destination, left, right)
                        "lessOrEqual" -> Instruction.LessOrEqual(promoted.orderedForm, destination, left, right)
                        "greater" -> Instruction.Greater(promoted.orderedForm, destination, left, right)
                        else -> Instruction.GreaterOrEqual(promoted.orderedForm, destination, left, right)
                    },
                )
            }
        }
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

    private fun promotePrimitives(primitives: List<GuestPrimitive?>): GuestPrimitive? {
        if (primitives.any { it == null || !it.numeric }) return null
        return GuestPrimitive.promote(primitives.filterNotNull())
    }

    private fun primitiveConvert(
        value: RegisterId,
        source: GuestPrimitive,
        destination: GuestPrimitive,
        element: IrElement,
    ): RegisterId {
        val target = destination.scalar
        val converted =
            if (registerValueType(value) == target) {
                value
            } else if (source == GuestPrimitive.CHAR || destination == GuestPrimitive.CHAR) {
                val integer =
                    if (registerValueType(value) == ValueType.I32) {
                        value
                    } else {
                        allocate(ValueType.I32).also { emit(Instruction.Convert(it, value, unsignedSource = source.unsigned)) }
                    }
                if (target == ValueType.I32) {
                    integer
                } else {
                    allocate(target).also {
                        emit(Instruction.Convert(it, integer, unsignedDestination = destination.unsigned))
                    }
                }
            } else {
                allocate(target).also {
                    emit(Instruction.Convert(it, value, unsignedSource = source.unsigned, unsignedDestination = destination.unsigned))
                }
            }
        return normalizePrimitive(converted, destination, element)
    }

    private fun normalizePrimitive(
        value: RegisterId,
        primitive: GuestPrimitive,
        element: IrElement,
    ): RegisterId {
        val bits = primitive.narrowBits ?: return value
        if (primitive.unsigned) {
            val mask = emitI32Constant((1 shl bits) - 1, element)
            return allocate(ValueType.I32).also { emit(Instruction.BitAnd(it, value, mask)) }
        }
        val count = emitI32Constant(32 - bits, element)
        val shifted = allocate(ValueType.I32).also { emit(Instruction.ShiftLeft(it, value, count)) }
        return allocate(ValueType.I32).also { emit(Instruction.ShiftRight(it, shifted, count)) }
    }

    private fun compilePrimitiveBuiltinCall(
        call: IrCall,
        target: IrSimpleFunction,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        val owner = (target.parent as? IrClass)?.fqNameWhenAvailable?.asString() ?: return null
        val primitive = GuestPrimitive.entries.singleOrNull { it.qualifiedName == owner } ?: return null
        val name = target.name.asString()
        val output = GuestPrimitive.scalar(resolvedType(call.type)) ?: return null
        // K2 numeric equality can retain a nullable receiver type in its guarded conversion branch.
        if (arguments.size == 1 && name == "to${output.sourceName}" &&
            registerValueType(arguments[0]) is ValueType.Ref &&
            expressions.singleOrNull()?.let { GuestPrimitive.scalar(resolvedType(it.type).makeNotNull()) } == primitive
        ) {
            return primitiveConvert(unboxScalar(arguments[0], primitive.scalar, primitive), primitive, output, call)
        }
        val source = expressions.firstOrNull()?.let { GuestPrimitive.scalar(resolvedType(it.type)) } ?: return null
        // Nullable receivers and Any.equals are handled by the reference dispatch path.
        if (arguments.isEmpty() || registerValueType(arguments[0]) is ValueType.Ref) return null

        fun result(
            type: ValueType = output.scalar,
            instruction: (RegisterId) -> Instruction,
        ): RegisterId = allocate(type).also { emit(instruction(it)) }

        fun scalarOne(): RegisterId =
            when (output.scalar) {
                ValueType.I64 -> emitI64Constant(1, call)
                ValueType.F32 -> emitF32Constant(1.0f, call)
                ValueType.F64 -> emitF64Constant(1.0, call)
                else -> emitI32Constant(1, call)
            }
        if (source == GuestPrimitive.BOOLEAN) {
            if (name == "not" && arguments.size == 1) {
                val falseValue =
                    allocate(
                        ValueType.Bool,
                    ).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.Bool(false)]))) }
                return result { Instruction.Equal(ScalarValueType.BOOL, it, arguments[0], falseValue) }
            }
            if (name in setOf("and", "or", "xor") && arguments.size == 2) {
                val destination = allocate(ValueType.Bool)
                if (name == "xor") {
                    val same = result { Instruction.Equal(ScalarValueType.BOOL, it, arguments[0], arguments[1]) }
                    val falseValue =
                        allocate(
                            ValueType.Bool,
                        ).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.Bool(false)]))) }
                    emit(Instruction.Equal(ScalarValueType.BOOL, destination, same, falseValue))
                } else {
                    val yes = createBlock()
                    val no = createBlock()
                    val join = createBlock()
                    emit(Instruction.Branch(arguments[0], blockId(yes), blockId(no)))
                    currentBlock = yes
                    emit(Instruction.Move(destination, if (name == "and") arguments[1] else arguments[0]))
                    jumpTo(join)
                    currentBlock = no
                    emit(Instruction.Move(destination, if (name == "or") arguments[1] else arguments[0]))
                    jumpTo(join)
                    currentBlock = join
                }
                return destination
            }
            return null
        }
        if (name.startsWith("to") && name == "to${output.sourceName}" && arguments.size == 1) {
            return primitiveConvert(arguments[0], source, output, call)
        }
        if (source == GuestPrimitive.CHAR) {
            if (name == "<get-code>") return primitiveConvert(arguments[0], source, GuestPrimitive.INT, call)
            if (name in setOf("plus", "minus", "inc", "dec")) {
                val left = primitiveConvert(arguments[0], source, GuestPrimitive.INT, call)
                val right =
                    if (arguments.size == 1) {
                        emitI32Constant(1, call)
                    } else {
                        primitiveConvert(
                            arguments[1],
                            requireNotNull(GuestPrimitive.scalar(resolvedType(expressions[1].type))),
                            GuestPrimitive.INT,
                            call,
                        )
                    }
                val integer =
                    result(ValueType.I32) {
                        if (name == "plus" || name == "inc") Instruction.Add(it, left, right) else Instruction.Subtract(it, left, right)
                    }
                return primitiveConvert(integer, GuestPrimitive.INT, output, call)
            }
            return null
        }
        if (!primitive.numeric) return null
        if (name == "compareTo" && arguments.size == 2) {
            val descriptors = expressions.map { requireNotNull(GuestPrimitive.scalar(resolvedType(it.type))) }
            if (descriptors.any { it == GuestPrimitive.FLOAT || it == GuestPrimitive.DOUBLE }) {
                return compileFloatingCompareTo(call, expressions.map { resolvedType(it.type) }, arguments)
            }
            val wide =
                if (descriptors.any { it.scalar == ValueType.I64 }) {
                    if (source.unsigned) GuestPrimitive.ULONG else GuestPrimitive.LONG
                } else {
                    if (source.unsigned) GuestPrimitive.UINT else GuestPrimitive.INT
                }
            val operands = arguments.mapIndexed { index, value -> primitiveConvert(value, descriptors[index], wide, call) }
            val less = result(ValueType.Bool) { Instruction.Less(wide.orderedForm, it, operands[0], operands[1]) }
            val equal = result(ValueType.Bool) { Instruction.Equal(wide.scalarForm, it, operands[0], operands[1]) }
            val negative = emitI32Constant(-1, call)
            val zero = emitI32Constant(0, call)
            val positive = emitI32Constant(1, call)
            val destination = allocate(ValueType.I32)
            val smaller = createBlock()
            val other = createBlock()
            val same = createBlock()
            val greater = createBlock()
            val join = createBlock()
            emit(Instruction.Branch(less, blockId(smaller), blockId(other)))
            currentBlock = smaller
            emit(Instruction.Move(destination, negative))
            jumpTo(join)
            currentBlock = other
            emit(Instruction.Branch(equal, blockId(same), blockId(greater)))
            currentBlock = same
            emit(Instruction.Move(destination, zero))
            jumpTo(join)
            currentBlock = greater
            emit(Instruction.Move(destination, positive))
            jumpTo(join)
            currentBlock = join
            return destination
        }
        if (name in setOf("plus", "minus", "times", "div", "rem") && arguments.size == 2) {
            val operands =
                arguments.mapIndexed { index, value ->
                    primitiveConvert(value, requireNotNull(GuestPrimitive.scalar(resolvedType(expressions[index].type))), output, call)
                }
            return result { destination ->
                when (name) {
                    "plus" -> Instruction.Add(output.scalarForm, destination, operands[0], operands[1])
                    "minus" -> Instruction.Subtract(output.scalarForm, destination, operands[0], operands[1])
                    "times" -> Instruction.Multiply(output.scalarForm, destination, operands[0], operands[1])
                    "div" -> Instruction.Divide(output.scalarForm, destination, operands[0], operands[1])
                    else -> Instruction.Remainder(output.scalarForm, destination, operands[0], operands[1])
                }
            }
        }
        if (name == "unaryPlus" && arguments.size == 1) return arguments[0]
        if (name == "unaryMinus" && arguments.size == 1) {
            val zero =
                when (output.scalar) {
                    ValueType.I32 -> emitI32Constant(0, call)
                    ValueType.I64 -> emitI64Constant(0, call)
                    ValueType.F32 -> emitF32Constant(-1.0f, call)
                    else -> emitF64Constant(-1.0, call)
                }
            return result {
                if (output.scalar == ValueType.F32 || output.scalar == ValueType.F64) {
                    Instruction.Multiply(output.scalarForm, it, arguments[0], zero)
                } else {
                    Instruction.Subtract(output.scalarForm, it, zero, arguments[0])
                }
            }
        }
        if (name in setOf("inc", "dec") && arguments.size == 1) {
            val one = scalarOne()
            val value =
                result {
                    if (name == "inc") {
                        Instruction.Add(output.scalarForm, it, arguments[0], one)
                    } else {
                        Instruction.Subtract(output.scalarForm, it, arguments[0], one)
                    }
                }
            return normalizePrimitive(value, output, call)
        }
        val bitForm = if (output.scalar == ValueType.I64) ScalarValueType.I64 else ScalarValueType.I32
        if (name in setOf("and", "or", "xor") && arguments.size == 2) {
            val value =
                result {
                    when (name) {
                        "and" -> Instruction.BitAnd(bitForm, it, arguments[0], arguments[1])
                        "or" -> Instruction.BitOr(bitForm, it, arguments[0], arguments[1])
                        else -> Instruction.BitXor(bitForm, it, arguments[0], arguments[1])
                    }
                }
            return normalizePrimitive(value, output, call)
        }
        if (name == "inv" && arguments.size == 1) {
            val mask = if (output.scalar == ValueType.I64) emitI64Constant(-1, call) else emitI32Constant(-1, call)
            return normalizePrimitive(result { Instruction.BitXor(bitForm, it, arguments[0], mask) }, output, call)
        }
        if (name in setOf("shl", "shr", "ushr") && arguments.size == 2) {
            return result {
                when {
                    name == "shl" -> Instruction.ShiftLeft(bitForm, it, arguments[0], arguments[1])
                    name == "ushr" || source.unsigned -> Instruction.ShiftUnsigned(bitForm, it, arguments[0], arguments[1])
                    else -> Instruction.ShiftRight(bitForm, it, arguments[0], arguments[1])
                }
            }
        }
        return null
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
        compilePrimitiveBuiltinCall(call, target, argumentExpressions, arguments)?.let { return it }

        fun result(
            type: ValueType,
            instruction: (RegisterId) -> Instruction,
        ): RegisterId = allocate(type).also { emit(instruction(it)) }
        if (
            fqName == "kotlin.String.compareTo" &&
            arguments.size == 2 &&
            argumentExpressions.all { it.type == kotlinStringType } &&
            call.type == intType
        ) {
            return compileStringCompareTo(call, arguments)
        }
        if (
            fqName == "kotlin.Char.compareTo" &&
            arguments.size == 2 &&
            argumentExpressions.all { it.type == charType } &&
            call.type == intType
        ) {
            val left = allocate(ValueType.I32)
            emit(Instruction.Convert(left, arguments[0]))
            val right = allocate(ValueType.I32)
            emit(Instruction.Convert(right, arguments[1]))
            return result(ValueType.I32) { Instruction.Subtract(it, left, right) }
        }
        if (
            fqName == "kotlin.Boolean.compareTo" &&
            arguments.size == 2 &&
            argumentExpressions.all { it.type == booleanType } &&
            call.type == intType
        ) {
            return compileBooleanCompareTo(call, arguments)
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
        val primitiveArray = argumentExpressions.firstOrNull()?.let { GuestPrimitive.array(resolvedType(it.type)) }
        if (primitiveArray != null && fqName == "${primitiveArray.arrayName}.<get-size>" && arguments.size == 1) {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (primitiveArray != null && fqName == "${primitiveArray.arrayName}.get" && arguments.size == 2) {
            return result(primitiveArray.scalar) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (primitiveArray != null && fqName == "${primitiveArray.arrayName}.set" && arguments.size == 3) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 1 && isSupportedReferenceArray(argumentExpressions[0].type) && name == "<get-size>") {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 2 && isSupportedReferenceArray(argumentExpressions[0].type) && name == "get") {
            val box = guestTypes.valueClassBox(resolvedType(call.type))
            return result(box?.let { ValueType.Ref(call.type.isNullable(), it.type) } ?: valueType(call.type, call)) {
                Instruction.ArrayLoad(it, arguments[0], arguments[1])
            }
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

    private fun compileBooleanCompareTo(
        call: IrCall,
        arguments: List<RegisterId>,
    ): RegisterId {
        val negative = emitI32Constant(-1, call)
        val zero = emitI32Constant(0, call)
        val positive = emitI32Constant(1, call)
        val destination = allocate(ValueType.I32)
        val equal = allocate(ValueType.Bool)
        emit(Instruction.Equal(ScalarValueType.BOOL, equal, arguments[0], arguments[1]))
        val equalBlock = createBlock()
        val differentBlock = createBlock()
        emit(Instruction.Branch(equal, blockId(equalBlock), blockId(differentBlock)))

        currentBlock = equalBlock
        emit(Instruction.Move(destination, zero))

        currentBlock = differentBlock
        val trueBlock = createBlock()
        val falseBlock = createBlock()
        emit(Instruction.Branch(arguments[0], blockId(trueBlock), blockId(falseBlock)))
        currentBlock = trueBlock
        emit(Instruction.Move(destination, positive))
        currentBlock = falseBlock
        emit(Instruction.Move(destination, negative))

        val join = createBlock()
        listOf(equalBlock, trueBlock, falseBlock).forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    private fun compileFloatingCompareTo(
        call: IrElement,
        types: List<IrType>,
        arguments: List<RegisterId>,
    ): RegisterId {
        val double = types.any { it == doubleType }
        val valueType = if (double) ValueType.F64 else ValueType.F32
        val scalar = if (double) ScalarValueType.F64 else ScalarValueType.F32
        val ordered = if (double) OrderedScalarValueType.F64 else OrderedScalarValueType.F32

        fun widen(
            value: RegisterId,
            type: IrType,
        ) = if (double) widenToF64(value, type, call) else widenToF32(value, type, call)
        val left = widen(arguments[0], types[0])
        val right = widen(arguments[1], types[1])
        val negative = emitI32Constant(-1, call)
        val zero = emitI32Constant(0, call)
        val positive = emitI32Constant(1, call)
        val oneFloat = if (double) emitF64Constant(1.0, call) else emitF32Constant(1.0f, call)
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

        // A floating value is ordered with itself exactly when it is not NaN.
        val leftNumeric = allocate(ValueType.Bool)
        emit(Instruction.GreaterOrEqual(ordered, leftNumeric, left, left))
        val rightNumeric = allocate(ValueType.Bool)
        emit(Instruction.GreaterOrEqual(ordered, rightNumeric, right, right))
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
        emit(Instruction.Less(ordered, less, left, right))
        select(less, negative)
        val greater = allocate(ValueType.Bool)
        emit(Instruction.Greater(ordered, greater, left, right))
        select(greater, positive)

        // IEEE comparisons equate both zero signs; their reciprocals retain the sign as infinity.
        val leftReciprocal = allocate(valueType)
        emit(Instruction.Divide(scalar, leftReciprocal, oneFloat, left))
        val rightReciprocal = allocate(valueType)
        emit(Instruction.Divide(scalar, rightReciprocal, oneFloat, right))
        val negativeZero = allocate(ValueType.Bool)
        emit(Instruction.Less(ordered, negativeZero, leftReciprocal, rightReciprocal))
        select(negativeZero, negative)
        val positiveZero = allocate(ValueType.Bool)
        emit(Instruction.Greater(ordered, positiveZero, leftReciprocal, rightReciprocal))
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

    private fun compileArrayCopyCall(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        val fqName = target.fqNameWhenAvailable?.asString()
        if (fqName !in setOf("kotlin.collections.copyOf", "kotlin.collections.copyInto") || !target.isExternal) return null
        val receiverExpression = call.arguments.firstOrNull() ?: return null
        val receiverType = resolvedType(receiverExpression.type)
        if (GuestPrimitive.array(receiverType) == null && !isSupportedReferenceArray(receiverType)) {
            return null
        }
        var owner = target.parent
        while (owner is IrDeclaration && owner !is IrFile) owner = owner.parent
        if (owner is IrFile && session.trustedPlatformModule(owner.fileEntry.name) == null) return null
        // Kotlin's IR may substitute nullable array type arguments into an
        // external generic target. Bind its canonical platform template by
        // admitted storage shape and arity, not that substituted signature.
        val arrayName =
            when {
                GuestPrimitive.array(receiverType) != null -> "${requireNotNull(GuestPrimitive.array(receiverType)).sourceName}Array"
                else -> "Array<T>"
            }
        val arity = target.parameters.count { it.kind == IrParameterKind.Regular }
        val signature =
            when {
                fqName == "kotlin.collections.copyInto" && arity == 4 -> {
                    "fun($arrayName.$arrayName,Int,Int,Int):$arrayName"
                }

                fqName == "kotlin.collections.copyOf" && arity == 0 -> {
                    "fun($arrayName.):$arrayName"
                }

                fqName == "kotlin.collections.copyOf" && arity == 1 -> {
                    val result = if (arrayName == "Array<T>") "Array<T?>" else arrayName
                    "fun($arrayName.Int):$result"
                }

                else -> {
                    return null
                }
            }
        val registered =
            session.canonicalIntrinsicRegistry?.handlers?.keys?.any {
                it.module == PlatformModuleId("kotlin", "builtins") && it.callableId.asSingleFqName().asString() == fqName &&
                    it.signature.value == signature
            } == true
        if (!registered) return null
        val arguments = mutableListOf<RegisterId>()
        resolveProjectCallArguments(call, target, signature).zip(loweredParameters(target, session)).forEach { (argument, parameter) ->
            arguments +=
                when (argument) {
                    is ResolvedCallArgument.Expression -> compileExpression(argument.expression)
                    else -> compileCallArgument(argument, parameter.type, arguments)
                }
        }
        val source = arguments[0]
        val zero = emitI32Constant(0, call)
        if (fqName == "kotlin.collections.copyInto") {
            val destination = arguments[1]
            val destinationStart = arguments[2]
            val sourceStart = arguments[3]
            val end = arguments[4]
            val length = allocate(ValueType.I32).also { emit(Instruction.Subtract(it, end, sourceStart)) }
            emit(Instruction.ArrayCopy(source, destination, sourceStart, destinationStart, length))
            return destination
        }
        val sourceSize = allocate(ValueType.I32).also { emit(Instruction.ArrayLength(it, source)) }
        val newSize = arguments.getOrNull(1) ?: sourceSize
        prepareAllocationBlock()
        val destination = allocate(valueType(call.type, call))
        emit(Instruction.NewArray(destination, (registerValueType(destination) as ValueType.Ref).type, newSize))
        val length = allocate(ValueType.I32)
        val smaller = allocate(ValueType.Bool).also { emit(Instruction.Less(OrderedScalarValueType.I32, it, newSize, sourceSize)) }
        val truncated = createBlock()
        val complete = createBlock()
        val copy = createBlock()
        emit(Instruction.Branch(smaller, blockId(truncated), blockId(complete)))
        currentBlock = truncated
        emit(Instruction.Move(length, newSize))
        jumpTo(copy)
        currentBlock = complete
        emit(Instruction.Move(length, sourceSize))
        jumpTo(copy)
        currentBlock = copy
        emit(Instruction.ArrayCopy(source, destination, zero, zero, length))
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

        val zero = emitI32Constant(0, call)
        emit(Instruction.ArrayCopy(source, destination, start, zero, length))
        return destination
    }

    private fun comparison(
        call: IrCall,
        name: String,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        if (arguments.size != 2) return null
        val leftType = resolvedType(expressions[0].type)
        val rightType = resolvedType(expressions[1].type)
        val floatingPrimitive = GuestPrimitive.scalar(leftType.makeNotNull())
        if (name.equals("ieee754Equals", ignoreCase = true) &&
            floatingPrimitive in setOf(GuestPrimitive.FLOAT, GuestPrimitive.DOUBLE) &&
            GuestPrimitive.scalar(rightType.makeNotNull()) == floatingPrimitive &&
            listOf(leftType, rightType).any { it.isNullable() }
        ) {
            return compileNullableFloatingEquality(arguments, requireNotNull(floatingPrimitive))
        }
        // Generic Kotlin equality uses wrapper semantics even in an unboxed specialization.
        if (name in setOf("EQEQ", "equals", "eqeq") &&
            expressions.any { expression ->
                (expression.type as? IrSimpleType)?.classifier is IrTypeParameterSymbol &&
                    GuestPrimitive.scalar(resolvedType(expression.type).makeNotNull()) in setOf(GuestPrimitive.FLOAT, GuestPrimitive.DOUBLE)
            }
        ) {
            val references =
                arguments.mapIndexed { index, argument ->
                    if (registerValueType(argument) is ValueType.Ref) {
                        argument
                    } else {
                        boxValue(
                            argument,
                            expressions[index].type,
                            ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))),
                        )
                    }
                }
            return equalsValue(references[0], references[1])
        }
        if (name in setOf("EQEQ", "equals", "eqeq") && arguments.any { registerValueType(it) is ValueType.Inline }) {
            val references =
                arguments.mapIndexed { index, value ->
                    if (registerValueType(value) is ValueType.Ref) {
                        value
                    } else {
                        boxValue(value, expressions[index].type, ValueType.Ref(false, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE))))
                    }
                }
            return equalsValue(references[0], references[1])
        }
        val sourcePrimitives = listOf(leftType, rightType).map(GuestPrimitive::scalar)
        val promoted = if (arguments.all { registerValueType(it) !is ValueType.Ref }) promotePrimitives(sourcePrimitives) else null
        val numeric = promoted != null
        val operands =
            if (promoted == null) {
                arguments
            } else {
                arguments.mapIndexed { index, value ->
                    primitiveConvert(value, requireNotNull(sourcePrimitives[index]), promoted, call)
                }
            }
        val floatIeeeEquality =
            name.equals("ieee754Equals", ignoreCase = true) &&
                ((leftType == floatType && rightType == floatType) || (leftType == doubleType && rightType == doubleType))
        if (name in setOf("EQEQ", "equals", "eqeq") || floatIeeeEquality) {
            if (!numeric && (registerValueType(operands[0]) is ValueType.Ref || registerValueType(operands[1]) is ValueType.Ref)) {
                if (expressions.any { it is IrConst && it.value == null }) {
                    return allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, operands[0], operands[1])) }
                }
                return equalsValue(operands[0], operands[1])
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
                    val type = promoted?.scalarForm ?: scalarType(leftType, call)
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
        val orderedType = promoted?.orderedForm ?: orderedType(leftType, call)
        return allocate(ValueType.Bool).also { destination ->
            val instruction = instructionFactory(orderedType, destination)
            emit(instruction)
        }
    }

    private fun compileNullableFloatingEquality(
        arguments: List<RegisterId>,
        primitive: GuestPrimitive,
    ): RegisterId {
        val result = allocate(ValueType.Bool)
        val left = arguments[0]
        val right = arguments[1]
        val leftBlocks = if (registerValueType(left) is ValueType.Ref) createBlock() to createBlock() else null
        val rightBlocks = if (registerValueType(right) is ValueType.Ref) createBlock() to createBlock() else null
        val join = createBlock()
        val falseConstant = requireNotNull(constantIds[Constant.Bool(false)])

        fun isNull(value: RegisterId): RegisterId {
            val type = registerValueType(value) as ValueType.Ref
            val absent = allocate(type.copy(nullable = true)).also { emit(Instruction.Null(it)) }
            return allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, value, absent)) }
        }
        if (leftBlocks != null) {
            val (absent, present) = leftBlocks
            emit(Instruction.Branch(isNull(left), blockId(absent), blockId(present)))
            currentBlock = absent
            if (registerValueType(right) is ValueType.Ref) {
                emit(Instruction.Move(result, isNull(right)))
            } else {
                emit(Instruction.Const(result, falseConstant))
            }
            jumpTo(join)
            currentBlock = present
        }
        if (rightBlocks != null) {
            val (absent, present) = rightBlocks
            emit(Instruction.Branch(isNull(right), blockId(absent), blockId(present)))
            currentBlock = absent
            emit(Instruction.Const(result, falseConstant))
            jumpTo(join)
            currentBlock = present
        }

        fun unbox(value: RegisterId) =
            if (registerValueType(value) is ValueType.Ref) unboxScalar(value, primitive.scalar, primitive) else value
        emit(Instruction.Equal(primitive.scalarForm, result, unbox(left), unbox(right)))
        jumpTo(join)
        currentBlock = join
        return result
    }

    private fun asAnyReference(
        source: RegisterId,
        nullable: Boolean = (registerValueType(source) as? ValueType.Ref)?.nullable == true,
    ): RegisterId {
        val anyType = ValueType.Ref(nullable, TypeRef.Imported(ImportId.of(ANY_RUNTIME_TYPE)))
        val sourceType = registerValueType(source)
        check(sourceType is ValueType.Ref) { "Any reference coercion requires nominal boxing at the source expression" }
        if (sourceType == anyType) return source
        // Interface views need an explicit Any cast; their nominal type has no class-supertype edge.
        return allocate(anyType).also { emit(Instruction.CheckedCast(it, source, anyType.type)) }
    }

    private fun equalsValue(
        left: RegisterId,
        right: RegisterId,
        direct: Boolean = false,
        nullableOperator: Boolean = true,
    ): RegisterId {
        val receiver = asAnyReference(left)
        val argument = asAnyReference(right)
        val receiverType = registerValueType(receiver) as ValueType.Ref
        val result = allocate(ValueType.Bool)

        fun invoke() {
            val nonNull =
                if (receiverType.nullable) {
                    allocate(receiverType.copy(nullable = false)).also { emit(Instruction.CheckedCast(it, receiver, receiverType.type)) }
                } else {
                    receiver
                }
            val target = FunctionRef.Imported(ImportId.of(ANY_EQUALS_IMPORT))
            emit(
                if (direct) {
                    Instruction.Call(Destination.Register(result), target, listOf(nonNull, argument))
                } else {
                    Instruction.CallVirtual(Destination.Register(result), target, listOf(nonNull, argument))
                },
            )
        }
        if (nullableOperator && receiverType.nullable) {
            val nullRef = allocate(receiverType).also { emit(Instruction.Null(it)) }
            val isNull = allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, receiver, nullRef)) }
            val absent = createBlock()
            val present = createBlock()
            val join = createBlock()
            emit(Instruction.Branch(isNull, blockId(absent), blockId(present)))
            currentBlock = absent
            emit(Instruction.RefEqual(result, receiver, argument))
            jumpTo(join)
            currentBlock = present
            invoke()
            jumpTo(join)
            currentBlock = join
        } else {
            invoke()
        }
        return result
    }

    private fun compileDataEquals() {
        val owner = function.parent as IrClass
        val ownerType = registerValueType(RegisterId.of(0u)) as ValueType.Ref

        fun returnBool(value: Boolean) {
            val result = allocate(ValueType.Bool).also { emit(Instruction.Const(it, requireNotNull(constantIds[Constant.Bool(value)]))) }
            emit(Instruction.Return(Destination.Register(result)))
        }

        fun requireTrue(value: RegisterId) {
            val next = createBlock()
            val failed = createBlock()
            emit(Instruction.Branch(value, blockId(next), blockId(failed)))
            currentBlock = failed
            returnBool(false)
            currentBlock = next
        }
        val same = allocate(ValueType.Bool).also { emit(Instruction.RefEqual(it, RegisterId.of(0u), RegisterId.of(1u))) }
        val identical = createBlock()
        val different = createBlock()
        emit(Instruction.Branch(same, blockId(identical), blockId(different)))
        currentBlock = identical
        returnBool(true)
        currentBlock = different
        val matches = allocate(ValueType.Bool).also { emit(Instruction.IsType(it, RegisterId.of(1u), ownerType.type)) }
        requireTrue(matches)
        val other = allocate(ownerType).also { emit(Instruction.CheckedCast(it, RegisterId.of(1u), ownerType.type)) }
        val properties = owner.declarations.filterIsInstance<IrProperty>()
        owner.constructors.single { it.isPrimary }.parameters.filter { it.kind == IrParameterKind.Regular }.forEach { parameter ->
            val property = properties.single { it.name == parameter.name }
            val symbol = requireNotNull(property.backingField).symbol
            val field =
                fieldsByBacking[symbol] ?: currentClassInstance?.let { genericFieldsByBacking[symbol to it] }
                    ?: throw UnsupportedKotlinIr(property, "data class equality field is unavailable")
            val left = allocate(field.type).also { emit(Instruction.FieldGet(it, RegisterId.of(0u), FieldRef.Local(field.id))) }
            val right = allocate(field.type).also { emit(Instruction.FieldGet(it, other, FieldRef.Local(field.id))) }
            val equal =
                when (field.type) {
                    ValueType.F32 -> {
                        val a = hashValue(left)
                        val b = hashValue(right)
                        allocate(ValueType.Bool).also { emit(Instruction.Equal(ScalarValueType.I32, it, a, b)) }
                    }

                    ValueType.F64 -> {
                        val compared = compileFloatingCompareTo(function, listOf(doubleType, doubleType), listOf(left, right))
                        val zero = emitI32Constant(0, function)
                        allocate(ValueType.Bool).also { emit(Instruction.Equal(ScalarValueType.I32, it, compared, zero)) }
                    }

                    is ValueType.Ref -> {
                        equalsValue(left, right)
                    }

                    else -> {
                        allocate(ValueType.Bool).also {
                            emit(
                                Instruction.Equal(
                                    when (field.type) {
                                        ValueType.I32 -> ScalarValueType.I32
                                        ValueType.I64 -> ScalarValueType.I64
                                        ValueType.Bool -> ScalarValueType.BOOL
                                        ValueType.Char -> ScalarValueType.CHAR
                                        else -> error("unsupported data equality field")
                                    },
                                    it,
                                    left,
                                    right,
                                ),
                            )
                        }
                    }
                }
            requireTrue(equal)
        }
        returnBool(true)
    }

    private fun compileWhile(loop: IrWhileLoop) {
        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = compileExpression(loop.condition)
        if (isTerminated()) return
        val body = createBlock()
        val branchBlock = currentBlock
        val branchIndex = blocks[branchBlock].instructions.size
        emit(Instruction.Branch(condition, blockId(body), blockId(header)))
        currentBlock = body
        val context = LoopContext(loop, continueTarget = header, finallyDepth = finallyContexts.size)
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
        if (GuestPrimitive.entries.any { iteratorCall?.targetFqName() == "${it.arrayName}.iterator" }) {
            compilePrimitiveArrayForLoop(block)
        } else if (iteratorCall?.targetFqName() in
            setOf("kotlin.collections.Iterable.iterator", "kotlin.collections.List.iterator")
        ) {
            block.statements.forEach(::compileStatement)
        } else if (intForLoopPlan(block) != null) {
            compileIntForLoop(block)
        } else {
            block.statements.forEach(::compileStatement)
        }
    }

    private fun compilePrimitiveArrayForLoop(block: IrBlock) {
        val plan =
            primitiveArrayForLoopPlan(block) ?: throw UnsupportedKotlinIr(block, "unsupported canonical primitive-array for-loop shape")
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
        val loopValue = allocate(valueType(plan.canonical.loopVariable.type, plan.canonical.loopVariable))
        values[plan.canonical.loopVariable.symbol] = loopValue
        emit(Instruction.ArrayLoad(loopValue, array, index))
        val context = LoopContext(plan.canonical.loop, continueTarget = null, placeholderTarget = body, finallyDepth = finallyContexts.size)
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
        val context = LoopContext(plan.loop, continueTarget = null, placeholderTarget = body, finallyDepth = finallyContexts.size)
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

        val canonical = canonicalIntForLoopBody(block, iterator, iteratorName = "Iterator") ?: return null
        return IntForLoopPlan(canonical.loop, canonical.loopVariable, bounds[0], bounds[1], inclusive, descending, step, canonical.body)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun primitiveArrayForLoopPlan(block: IrBlock): PrimitiveArrayForLoopPlan? {
        if (block.statements.size != 2) return null
        val iterator = block.statements[0] as? IrVariable ?: return null
        if (iterator.origin.toString() != "FOR_LOOP_ITERATOR") return null
        val iteratorCall = iterator.initializer as? IrCall ?: return null
        val array = iteratorCall.arguments.filterNotNull().singleOrNull() ?: return null
        val primitive = GuestPrimitive.array(resolvedType(array.type)) ?: return null
        if (iteratorCall.targetFqName() != "${primitive.arrayName}.iterator") return null
        val loopBody = ((block.statements[1] as? IrWhileLoop)?.body as? IrBlock) ?: return null
        val loopVariable = loopBody.statements.firstOrNull() as? IrVariable ?: return null
        if (GuestPrimitive.scalar(resolvedType(loopVariable.type)) != primitive) return null
        val iteratorName = if (primitive.unsigned) "Iterator" else "${primitive.sourceName}Iterator"
        val canonical = canonicalIntForLoopBody(block, iterator, loopVariable.type, iteratorName) ?: return null
        return PrimitiveArrayForLoopPlan(canonical, array)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun canonicalIntForLoopBody(
        block: IrBlock,
        iterator: IrVariable,
        elementType: IrType = intType,
        iteratorName: String = "IntIterator",
    ): CanonicalIntForLoopBody? {
        val loop = block.statements[1] as? IrWhileLoop ?: return null
        if (loop.origin?.toString() != "FOR_LOOP_INNER_WHILE") return null
        val hasNext = loop.condition as? IrCall ?: return null
        if (hasNext.targetFqName() != "kotlin.collections.$iteratorName.hasNext") return null
        val iteratorRead = hasNext.arguments.filterNotNull().singleOrNull() as? IrGetValue ?: return null
        if (iteratorRead.symbol !== iterator.symbol) return null

        val loopBody = loop.body as? IrBlock ?: return null
        if (loopBody.statements.size != 2) return null
        val loopVariable = loopBody.statements[0] as? IrVariable ?: return null
        if (loopVariable.origin.toString() != "FOR_LOOP_VARIABLE" || loopVariable.type != elementType) return null
        val nextCall = loopVariable.initializer as? IrCall ?: return null
        if (nextCall.targetFqName() != "kotlin.collections.$iteratorName.next") return null
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
        emitNonlocalExit(context.finallyDepth, jump) {
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
        return resolved.classInstance(
            classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration } +
                guestTypes.valueClassBoxes.values.associate { it.symbol to it.symbol.owner } +
                externalClassTypes.keys.associateWith { it.owner },
        )
    }

    private fun resolveMemberOwner(
        receiver: GuestClassInstance,
        owner: IrClass,
    ): GuestClassInstance? {
        val classes =
            classInstanceTypeIds.keys.associate { it.declaration.symbol to it.declaration } +
                guestTypes.valueClassBoxes.values.associate { it.symbol to it.symbol.owner } +
                externalClassTypes.keys.associateWith { it.owner }
        val visited = mutableSetOf<GuestClassInstance>()

        fun find(instance: GuestClassInstance): GuestClassInstance? {
            if (!visited.add(instance)) return null
            if (instance.declaration == owner) return instance
            return instance.declaration.superTypes.firstNotNullOfOrNull { superType ->
                instance.substitute(superType).classInstance(classes)?.let(::find)
            }
        }
        return find(receiver)
    }

    private fun projectFunctionId(
        call: IrCall,
        target: IrSimpleFunction,
    ): FunctionId? {
        if (target.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
            val receiver = resolveClassInstance(call.dispatchReceiver?.type)

            fun overridden(function: IrSimpleFunction): List<Pair<FunctionId, IrType>> =
                function.overriddenSymbols.flatMap { symbol ->
                    val base = symbol.owner
                    val owner = base.parent as? IrClass
                    val instance =
                        if (owner != null && receiver != null && owner.typeParameters.size == receiver.arguments.size) {
                            GuestClassInstance(owner, receiver.arguments)
                        } else {
                            null
                        }
                    val id = instance?.let { genericMemberFunctionIds[base.symbol to it] } ?: functionIds[base.symbol]
                    val candidate = id?.let { it to (instance?.substitute(base.returnType) ?: base.returnType) }
                    listOfNotNull(candidate) + overridden(base)
                }
            val candidates = overridden(target)
            val resultType = resolvedType(call.type)
            (candidates.firstOrNull { resolvedType(it.second) == resultType } ?: candidates.firstOrNull())?.let { return it.first }
        }
        return if (target.typeParameters.isNotEmpty()) {
            val instance = projectFunctionInstance(call, target) ?: return null
            genericFunctionIds[instance]
                ?: throw UnsupportedKotlinIr(call, "generic function specialization is missing")
        } else if ((target.parent as? IrClass)?.typeParameters?.isNotEmpty() == true) {
            if (genericMemberFunctionIds.keys.none { it.first == target.symbol }) return null
            val receiver =
                resolveClassInstance(call.dispatchReceiver?.type)
                    ?: throw UnsupportedKotlinIr(call, "generic method receiver has no concrete class instance")
            val owner =
                resolveMemberOwner(receiver, target.parent as IrClass)
                    ?: throw UnsupportedKotlinIr(call, "generic method owner has no concrete class instance")
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
                resolvedType(type)
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
        for ((index, branch) in expression.branches.withIndex()) {
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall()) {
                    emitNoWhenBranchMatched()
                } else {
                    compileStatement(branch.result)
                }
            } else {
                val condition = compileExpression(branch.condition)
                if (isTerminated()) break
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
        if (exits.isEmpty()) return
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
        for ((index, branch) in expression.branches.withIndex()) {
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall()) {
                    emitNoWhenBranchMatched()
                } else if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    val source = coerceLocalValue(compileExpression(branch.result, expression.type), expression.type, branch.result)
                    emit(Instruction.Move(destination, source))
                }
            } else {
                val condition = compileExpression(branch.condition)
                if (isTerminated()) break
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    val source = coerceLocalValue(compileExpression(branch.result, expression.type), expression.type, branch.result)
                    emit(Instruction.Move(destination, source))
                    if (!isTerminated()) exits += currentBlock
                }
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) exits += currentBlock
        if (exits.isEmpty()) return destination
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

    private fun emitNoWhenBranchMatched() {
        val type = TypeRef.Imported(ImportId.of(10u))
        prepareAllocationBlock()
        val exception = allocate(ValueType.Ref(false, type))
        emit(Instruction.NewObject(exception, type))
        emit(Instruction.Throw(exception))
    }

    private fun compileReturn(statement: IrReturn) {
        val context = returnableContexts[statement.returnTargetSymbol]
        if (context == null && statement.returnTargetSymbol != function.symbol) {
            throw UnsupportedKotlinIr(statement, "return target is outside the current function or inline block")
        }
        val type = context?.type ?: function.returnType
        if (statement.value.type.isNothing()) {
            compileStatement(statement.value)
            return
        }
        rejectFunctionVariance(statement.value.type, type, statement)
        val value =
            if (type == unitType) {
                if (statement.value !is IrGetObjectValue || statement.value.type != unitType) {
                    compileStatement(statement.value)
                }
                null
            } else {
                coerceLocalValue(compileExpression(statement.value, type), type, statement.value)
            }
        if (isTerminated()) return
        val targetDepth = context?.finallyDepth ?: 0
        val exitValue =
            if (value != null && finallyContexts.size > targetDepth) {
                allocate(registerValueType(value)).also { emit(Instruction.Move(it, value)) }
            } else {
                value
            }
        emitNonlocalExit(targetDepth, statement) {
            if (context != null) {
                context.destination?.let { emit(Instruction.Move(it, requireNotNull(exitValue))) }
                context.exits += currentBlock
                jumpTo(0) // Patched when the block's continuation is known.
            } else {
                emit(Instruction.Return(exitValue?.let { Destination.Register(it) } ?: Destination.Unit))
            }
        }
    }

    private fun compileReturnableBlock(block: IrReturnableBlock): RegisterId? {
        val destination = if (block.type == unitType || block.type.isNothing()) null else allocate(valueType(block.type, block))
        val context = ReturnableContext(block.type, destination, finallyDepth = finallyContexts.size)
        check(returnableContexts.put(block.symbol, context) == null)
        try {
            if (destination == null) {
                block.statements.forEach(::compileStatement)
            } else {
                val tail =
                    block.statements.lastOrNull() as? IrExpression
                        ?: throw UnsupportedKotlinIr(block, "value inline block has no result expression")
                block.statements.dropLast(1).forEach(::compileStatement)
                if (!isTerminated()) {
                    if (tail.type.isNothing()) {
                        compileStatement(tail)
                    } else {
                        val value = coerceLocalValue(compileExpression(tail, block.type), block.type, tail)
                        emit(Instruction.Move(destination, value))
                    }
                }
            }
            if (!isTerminated()) {
                context.exits += currentBlock
                jumpTo(0)
            }
        } finally {
            check(returnableContexts.remove(block.symbol) === context)
        }
        if (context.exits.isNotEmpty()) {
            val continuation = createBlock()
            context.exits.forEach { patchJumpTarget(it, continuation) }
            currentBlock = continuation
        }
        return destination
    }

    private fun compileBlockValue(
        block: IrBlock,
        expectedType: IrType? = null,
    ): RegisterId {
        val result =
            block.statements.lastOrNull() as? IrExpression
                ?: throw UnsupportedKotlinIr(block, "value block has no result expression")
        block.statements.dropLast(1).forEach(::compileStatement)
        if (!isTerminated() && result.type.isNothing()) compileStatement(result)
        if (isTerminated()) return allocate(valueType(expectedType ?: block.type, block))
        return compileExpression(result, expectedType ?: block.type)
    }

    private fun trustedIntrinsic(function: IrSimpleFunction): LoweredCapabilityOperation? = resolveTrustedIntrinsic(function, session)

    private fun resolvedType(type: IrType): IrType =
        valueClassInitializerInstance?.substitute(type) ?: currentClassInstance?.substitute(currentInstance?.substitute(type) ?: type)
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
        GuestPrimitive.scalar(resolvedType(type))?.scalarForm
            ?: when (valueType(type, element)) {
                ValueType.I32 -> ScalarValueType.I32
                ValueType.I64 -> ScalarValueType.I64
                ValueType.F32 -> ScalarValueType.F32
                ValueType.F64 -> ScalarValueType.F64
                ValueType.Bool -> ScalarValueType.BOOL
                ValueType.Char -> ScalarValueType.CHAR
                else -> throw UnsupportedKotlinIr(element, "unsupported equality operand")
            }

    private fun orderedType(
        type: IrType,
        element: IrElement,
    ): OrderedScalarValueType =
        GuestPrimitive.scalar(resolvedType(type))?.orderedForm
            ?: when (valueType(type, element)) {
                ValueType.I32 -> OrderedScalarValueType.I32
                ValueType.I64 -> OrderedScalarValueType.I64
                ValueType.F32 -> OrderedScalarValueType.F32
                ValueType.F64 -> OrderedScalarValueType.F64
                ValueType.Char -> OrderedScalarValueType.CHAR
                else -> throw UnsupportedKotlinIr(element, "unsupported ordered-comparison operand")
            }

    private fun allocate(type: ValueType): RegisterId =
        RegisterId
            .of((leadingParameterTypes.size + sourceParameters.size + localTypes.size).toUInt())
            .also { localTypes += type }

    private fun emit(instruction: Instruction) {
        // A nested Nothing expression can transfer control before its caller writes a result.
        if (isTerminated()) return
        val source = activeSource
        val path = session.originalSourcePath(source) ?: session.virtualSourcePath(function.file.fileEntry.name)
        if (path != null && source.startOffset >= 0 && source.endOffset >= source.startOffset) {
            val position = session.sourcePosition(path, source.startOffset)
            val entry =
                DebugEntry(
                    functionId,
                    blockId(currentBlock),
                    blocks[currentBlock].instructions.size.toUInt(),
                    source.startOffset.toUInt(),
                    source.endOffset.toUInt(),
                    null,
                    MetadataText.of(path.value),
                    position?.first,
                    position?.second,
                )
            val previous = debug.lastOrNull()
            if (previous == null || previous.block != entry.block || previous.sourcePath != entry.sourcePath ||
                previous.startUtf16 != entry.startUtf16 || previous.endUtf16 != entry.endUtf16
            ) {
                debug += entry
            }
        }
        blocks[currentBlock].instructions += instruction
    }

    private fun createBlock(loopHeader: Boolean = false): Int {
        blocks += MutableBlock(loopHeader)
        return blocks.lastIndex
    }

    private fun blockId(local: Int): BlockId = BlockId.of((blockBase + local).toUInt())

    private fun jumpTo(local: Int) = emit(Instruction.Jump(blockId(local)))

    private fun prepareAllocationBlock() {
        if (isTerminated()) return
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

    private data class ReturnableContext(
        val type: IrType,
        val destination: RegisterId?,
        val finallyDepth: Int,
        val exits: MutableList<Int> = mutableListOf(),
    )

    private data class LoopContext(
        val loop: IrLoop,
        val continueTarget: Int?,
        val placeholderTarget: Int = requireNotNull(continueTarget),
        val finallyDepth: Int,
        val breakBlocks: MutableList<Int> = mutableListOf(),
        val continueBlocks: MutableList<Int> = mutableListOf(),
    )

    private data class FinallyContext(
        val expression: IrExpression,
        val exits: MutableList<FinallyExit> = mutableListOf(),
    )

    private data class FinallyExit(
        val block: Int,
        val targetDepth: Int,
        val source: IrElement,
        val action: () -> Unit,
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

    private data class PrimitiveArrayForLoopPlan(
        val canonical: CanonicalIntForLoopBody,
        val array: IrExpression,
    )
}

private fun IrType.isExactClass(symbol: IrClassSymbol): Boolean = (this as? IrSimpleType)?.classifier == symbol

private fun IrType.isNullableString(): Boolean =
    isNullable() &&
        ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString() == "kotlin.String"

private fun IrType.isNullableScalar(): Boolean = isNullable() && GuestPrimitive.scalar(this) != null

private fun IrType.nullableScalarRuntimeType(): UInt = requireNotNull(GuestPrimitive.scalar(this)).boxType

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
    shape: GuestFunctionShape,
    unitBlockShape: GuestFunctionShape,
): String =
    if (shape == unitBlockShape) {
        "kotlin.Function0<Unit>"
    } else {
        "kotlin.Function${shape.parameters.size}<${(shape.parameters + shape.result).joinToString(
            ",",
        ) { it.specializationTypeIdentity() }}>"
    }

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

private fun IrSimpleFunction.requiresVirtualDispatch(): Boolean {
    val owner = parent as? IrClass
    // Keep inherited fake overrides on their established resolution path.
    // A concrete declaration in a final class has one runtime implementation;
    // its VIRTUAL flag still serves calls through parent declarations.
    if (owner?.modality == Modality.FINAL && owner.kind != ClassKind.INTERFACE &&
        modality != Modality.ABSTRACT && origin != IrDeclarationOrigin.FAKE_OVERRIDE
    ) {
        return false
    }
    return modality != Modality.FINAL || overriddenSymbols.isNotEmpty()
}

private fun IrSimpleFunction.isDirectFieldAccessor(): Boolean =
    origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR && modality == Modality.FINAL && overriddenSymbols.isEmpty()

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun collectWrittenGuestValues(roots: List<IrElement>): Set<IrValueSymbol> {
    val written = mutableSetOf<IrValueSymbol>()
    val collector =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitSetValue(expression: IrSetValue) {
                written += expression.symbol
                super.visitSetValue(expression)
            }
        }
    roots.forEach { it.accept(collector, null) }
    return written
}

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
                if (expression.reflectionTargetSymbol == null ||
                    (target?.parent is IrFile && expression.boundValues.isEmpty()) ||
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
            val receiverType = boundReceiver?.type ?: shape?.parameters?.firstOrNull()
            val ownerInstance =
                (receiverType as? IrSimpleType)?.let { receiver ->
                    (referenceTarget.parent as? IrClass)?.takeIf { receiver.classifier == it.symbol }?.let { owner ->
                        GuestClassInstance(owner, receiver.arguments.filterIsInstance<IrTypeProjection>().map { it.type })
                    }
                }

            fun substituted(type: IrType): IrType = ownerInstance?.substitute(type) ?: type
            val sourceParameters =
                listOfNotNull(dispatchReceiver?.type?.takeIf { instanceMethod && boundReceiver == null }?.let(::substituted)) +
                    referenceTarget.parameters.filter { it.kind == IrParameterKind.Regular }.map { substituted(it.type) }
            if (expression is IrRichFunctionReference &&
                (expression.hasUnitConversion || expression.hasSuspendConversion || expression.hasVarargConversion)
            ) {
                throw UnsupportedKotlinIr(expression, "adapted function references are not supported")
            }
            if (shape == null || referenceTarget.isSuspend ||
                substituted(referenceTarget.returnType) != shape.result ||
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
            val resultType = shape?.result as? IrSimpleType
            val instance =
                resultType?.let {
                    GuestClassInstance(
                        constructor.parentAsClass,
                        it.arguments.filterIsInstance<IrTypeProjection>().map { argument ->
                            argument.type
                        },
                    )
                }
            val parameters =
                constructor.parameters.filter { it.kind == IrParameterKind.Regular }.map {
                    instance?.substitute(it.type)
                        ?: it.type
                }
            if (shape == null || !constructor.isPrimary ||
                (instance?.substitute(constructor.returnType) ?: constructor.returnType) != shape.result ||
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
            val adapter =
                when (expression) {
                    is IrFunctionReference -> expression.symbol.owner as? IrSimpleFunction
                    is IrRichFunctionReference -> expression.invokeFunction
                    else -> null
                }
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
            when (expression) {
                is IrFunctionExpression -> {
                    expression.function
                }

                is IrRichFunctionReference -> {
                    if (expression.boundValues.isNotEmpty() || expression.hasUnitConversion ||
                        expression.hasSuspendConversion || expression.hasVarargConversion
                    ) {
                        throw UnsupportedKotlinIr(expression, "adapted lambda references are not supported")
                    }
                    expression.invokeFunction
                }

                else -> {
                    throw UnsupportedKotlinIr(expression, "unsupported function reference")
                }
            }
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

                override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                    owned += expression.invokeFunction.parameters.map { it.symbol }
                    super.visitRichFunctionReference(expression)
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

private val hashCollectionInterfaces =
    setOf(
        "kotlin.collections.Map",
        "kotlin.collections.MutableMap",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Map.Entry",
        "kotlin.collections.MutableMap.MutableEntry",
    )

private val specializedCollectionInterfaces =
    setOf(
        "kotlin.collections.Iterable",
        "kotlin.collections.Iterator",
        "kotlin.collections.Collection",
        "kotlin.collections.List",
        "kotlin.collections.MutableIterable",
        "kotlin.collections.MutableIterator",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.MutableList",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Map",
        "kotlin.collections.MutableMap",
        "kotlin.collections.Map.Entry",
        "kotlin.collections.MutableMap.MutableEntry",
    )

private fun loweredParameters(
    function: IrFunction,
    session: CompilationSession,
) = when (function) {
    is IrConstructor -> {
        listOf(requireNotNull(function.parentAsClass.thisReceiver)) +
            function.parameters.filter { it.kind == IrParameterKind.Regular }
    }

    is IrSimpleFunction -> {
        loweredParameters(function, session)
    }

    else -> {
        throw UnsupportedKotlinIr(function, "unsupported external function declaration")
    }
}

private fun loweredParameters(
    function: IrSimpleFunction,
    session: CompilationSession,
) = if (
    (function.parent as? IrClass)?.fqNameWhenAvailable?.asString() in
    specializedCollectionInterfaces
) {
    function.parameters
} else if (
    session.platformFunctions.any { link ->
        link.symbol == function.fqNameWhenAvailable?.asString() && link.signature == function.canonicalPlatformSignature()
    }
) {
    if ((function.parent as? IrClass)?.let { it.kind == ClassKind.OBJECT && !it.isManagedPlatformObject() } == true) {
        function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
    } else {
        function.parameters
    }
} else if (
    (function.parent as? IrClass)?.let { it.kind == ClassKind.OBJECT && !it.isManagedPlatformObject() } == true &&
    session.trustedPlatformModule(function.file.fileEntry.name) != null
) {
    function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
} else {
    function.parameters
}

internal fun IrSimpleFunction.canonicalPlatformSignature(): String {
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
) : IrVisitorVoid() {
    val values = mutableListOf<Any>()
    val strings: List<String>
        get() = values.filterIsInstance<String>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitConst(expression: IrConst) {
        expression
            .primitiveLiteralValue()
            ?.takeIf {
                it is String || it is Byte || it is Short || it is Int || it is Long || it is Float || it is Double || it is Boolean ||
                    it is Char
            }?.let(values::add)
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
        doubleCompanionConstant(expression.symbol.owner)?.let(values::add)
        if (fqName == "kotlin.emptyArray" || fqName == "kotlin.collections.emptyList") {
            values += 0
        } else if (fqName == "kotlin.arrayOf" || GuestPrimitive.entries.any { fqName == it.arrayFactory } ||
            fqName == "kotlin.collections.listOf" || fqName in hashCollectionFactories
        ) {
            val size = (expression.arguments.filterNotNull().singleOrNull() as? IrVararg)?.elements?.size
            if (size != null) {
                values.addAll(0..size)
            } else if (fqName == "kotlin.collections.listOf" || fqName in hashCollectionFactories) {
                values += 0
            }
        } else if (fqName in setOf("kotlin.collections.copyOfRange", "kotlin.collections.copyOf", "kotlin.collections.copyInto")) {
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

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun doubleCompanionConstant(function: IrSimpleFunction): Double? {
    val owner = function.parent as? IrClass ?: return null
    if (owner.fqNameWhenAvailable?.asString() != "kotlin.Double.Companion") return null
    return when (function.name.asString()) {
        "<get-POSITIVE_INFINITY>" -> Double.POSITIVE_INFINITY
        "<get-NEGATIVE_INFINITY>" -> Double.NEGATIVE_INFINITY
        "<get-NaN>" -> Double.NaN
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
    val nullable: Boolean

    data class ValueClass(
        val box: GuestValueClassBox,
        override val nullable: Boolean,
    ) : ReferenceArrayElement

    data class Runtime(
        val runtimeType: UInt,
        override val nullable: Boolean,
    ) : ReferenceArrayElement

    data class PlatformClass(
        val symbol: IrClassSymbol,
        override val nullable: Boolean,
    ) : ReferenceArrayElement

    data class GuestClass(
        val instance: GuestClassInstance,
        override val nullable: Boolean,
    ) : ReferenceArrayElement
}

private class ReferenceArrayUsageCollector(
    private val guestTypes: GuestTypeRegistry,
    private val classes: Map<IrClassSymbol, IrClass>,
    private val instances: Set<GuestClassInstance>,
    private val importedClasses: Set<IrClassSymbol>,
) {
    val arrays = linkedMapOf<String, ReferenceArrayElement>()

    fun consider(
        type: IrType,
        substitute: (IrType) -> IrType = { it },
    ) {
        val resolved = substitute(type)
        val element = guestTypes.arrayElement(resolved) ?: return
        guestTypes.valueClassBox(element)?.let { box ->
            arrays[resolved.specializationTypeIdentity()] = ReferenceArrayElement.ValueClass(box, element.isNullable())
            return
        }
        val runtimeType =
            when {
                element.isKotlinAny() -> ANY_RUNTIME_TYPE

                element.isNullableScalar() -> element.nullableScalarRuntimeType()

                element.isNullable() &&
                    element.isNullableString() -> 1u

                else -> null
            }
        if (runtimeType != null) {
            arrays[resolved.specializationTypeIdentity()] = ReferenceArrayElement.Runtime(runtimeType, element.isNullable())
            return
        }
        val symbol = (element as? IrSimpleType)?.classifier as? IrClassSymbol
        if (symbol in importedClasses && symbol?.owner?.typeParameters.isNullOrEmpty()) {
            arrays[resolved.specializationTypeIdentity()] =
                ReferenceArrayElement.PlatformClass(requireNotNull(symbol), element.isNullable())
            return
        }
        val instance = element.classInstance(classes) ?: return
        if (instance in instances) {
            arrays[resolved.specializationTypeIdentity()] = ReferenceArrayElement.GuestClass(instance, element.isNullable())
        }
    }

    fun scan(
        root: IrElement,
        substitute: (IrType) -> IrType = { it },
    ) {
        root.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    if (element is IrClass && element !== root) return
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
internal fun IrType.specializationTypeIdentity(): String {
    val simple = this as? IrSimpleType ?: return toString()
    val name =
        when (val classifier = simple.classifier) {
            is IrClassSymbol -> {
                val qualified = classifier.owner.fqNameWhenAvailable?.asString() ?: classifier.owner.relativeClassName()
                // Built-in scalar names retain their established spelling; all other arguments retain their package.
                if (qualified.startsWith("kotlin.") && '.' !in qualified.removePrefix("kotlin.")) {
                    qualified.removePrefix("kotlin.")
                } else if ('.' !in qualified) {
                    "<root>.$qualified"
                } else {
                    qualified
                }
            }

            is IrTypeParameterSymbol -> {
                classifier.owner.name.asString()
            }

            else -> {
                classifier.toString()
            }
        }
    val arguments = simple.arguments.mapNotNull { (it as? IrTypeProjection)?.type?.specializationTypeIdentity() }
    return name + arguments.takeIf { it.isNotEmpty() }?.joinToString(prefix = "<", postfix = ">", separator = ",").orEmpty() +
        if (simple.isNullable()) "?" else ""
}

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
                "compukters" to "peripheral" -> 6u
                "compukter" to "sound" -> 1u
                "compukters" to "display" -> 4u
                "compukter" to "timer" -> 1u
                else -> error("unknown Compukters capability ${capability.namespace}:${capability.name}")
            },
        )

private fun Any.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (this) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(this)]))
        is Byte -> Constant.I32(toInt())
        is Short -> Constant.I32(toInt())
        is Int -> Constant.I32(this)
        is Long -> Constant.I64(this)
        is Float -> Constant.F32(toBits().toUInt())
        is Double -> Constant.F64(toBits().toULong())
        is Boolean -> Constant.Bool(this)
        is Char -> Constant.Char(code.toUShort())
        else -> error("unsupported literal $this")
    }

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrConst.primitiveLiteralValue(): Any? =
    when (GuestPrimitive.scalar(type)) {
        GuestPrimitive.UBYTE -> (value as? Number)?.toInt()?.and(255) ?: value
        GuestPrimitive.USHORT -> (value as? Number)?.toInt()?.and(65535) ?: value
        else -> value
    }

private fun IrConst.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (val literal = primitiveLiteralValue()) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(literal)]))
        is Byte -> Constant.I32(literal.toInt())
        is Short -> Constant.I32(literal.toInt())
        is Int -> Constant.I32(literal)
        is Long -> Constant.I64(literal)
        is Float -> Constant.F32(literal.toBits().toUInt())
        is Double -> Constant.F64(literal.toBits().toULong())
        is Boolean -> Constant.Bool(literal)
        is Char -> Constant.Char(literal.code.toUShort())
        else -> throw UnsupportedKotlinIr(this, "unsupported constant")
    }

private fun IrExpression.isTrueConstant(): Boolean = this is IrConst && value == true
