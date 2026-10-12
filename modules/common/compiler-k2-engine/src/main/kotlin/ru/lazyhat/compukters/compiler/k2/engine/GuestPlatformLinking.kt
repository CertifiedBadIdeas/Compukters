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

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.platform.bundle.PlatformDeclarationIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument

/** Source symbols discovered before layout; no executable import IDs are assigned here. */
internal data class GuestPlatformLinks(
    val functions: Map<IrFunctionSymbol, ExternalFunctionTarget>,
    val symbols: LinkedPlatformSymbols,
)

internal fun discoverGuestPlatformLinks(
    functionRoots: List<IrElement>,
    symbolRoots: List<IrElement>,
    session: CompilationSession,
): GuestPlatformLinks =
    GuestPlatformLinks(
        linkedPlatformFunctions(functionRoots, session),
        linkedPlatformSymbols(symbolRoots, session),
    )

/** The library dependencies used by emission; per-function register and block state stays in the emitter. */
internal data class GuestFunctionImports(
    val classTypes: Map<IrClassSymbol, TypeRef.Imported>,
    val fieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    val enumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    val singletons: Map<IrClassSymbol, ExternalFieldTarget>,
    val defaultEnumEntries: Map<String, ExternalFieldTarget>,
    val functions: Map<IrFunctionSymbol, ExternalFunctionTarget>,
)

/** Deterministic library import tables shared by artifact assembly and function emission. */
internal data class GuestPlatformImports(
    val types: Map<IrClassSymbol, ExternalTypeTarget>,
    val boxTypes: Map<GuestValueClassBox, ExternalTypeTarget>,
    val inlineTypes: Map<GuestValueClassBox, ExternalTypeTarget>,
    val fields: List<ExternalFieldTarget>,
    val boxFields: List<ExternalFieldTarget>,
    val boxPayloadFields: Map<GuestValueClassBox, List<FieldRef.Imported>>,
    val emitter: GuestFunctionImports,
) {
    val typeCount: Int get() = types.size + boxTypes.size + inlineTypes.size
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun bindGuestPlatformImports(
    links: GuestPlatformLinks,
    valueClassBoxes: Collection<GuestValueClassBox>,
    session: CompilationSession,
    firstImportId: Int,
): GuestPlatformImports {
    val externalTypeImports =
        links.symbols.types.entries
            .sortedBy { (_, target) -> target.sortKey }
            .mapIndexed { index, (symbol, target) ->
                symbol to target.copy(importId = ImportId.of((firstImportId + index).toUInt()))
            }.toMap()
    val externalBoxTypeImports =
        valueClassBoxes
            .filter { it.symbol !in externalTypeImports }
            .mapNotNull { box ->
                val link = session.platformTypes.singleOrNull { it.symbol == box.name } ?: return@mapNotNull null
                box to ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
            }.sortedBy { (_, target) -> target.sortKey }
            .mapIndexed { index, (box, target) ->
                box to target.copy(importId = ImportId.of((firstImportId + externalTypeImports.size + index).toUInt()))
            }.toMap()
    val externalInlineImports =
        valueClassBoxes
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
                                    firstImportId + externalTypeImports.size + externalBoxTypeImports.size +
                                        index
                                ).toUInt(),
                            ),
                    )
            }.toMap()
    val externalTypeImportCount = externalTypeImports.size + externalBoxTypeImports.size + externalInlineImports.size
    val externalClassTypes = externalTypeImports.mapValues { (_, target) -> TypeRef.Imported(target.importId) }
    val externalBoxFields =
        valueClassBoxes.filter { it.symbol in externalClassTypes || it in externalBoxTypeImports }.flatMap { box ->
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
            links.symbols.fieldsByGetter.values + links.symbols.enumEntries.values + links.symbols.singletons.values +
                links.symbols.defaultEnumEntries.values +
                externalBoxFields
        ).distinctBy(ExternalFieldTarget::sortKey)
            .sortedBy(ExternalFieldTarget::sortKey)
            .mapIndexed { index, target ->
                target.copy(importId = ImportId.of((firstImportId + externalTypeImportCount + index).toUInt()))
            }
    val externalFieldsBySortKey = externalFieldImports.associateBy(ExternalFieldTarget::sortKey)
    val externalGetterFieldImports =
        links.symbols.fieldsByGetter.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
    val externalEnumFieldImports =
        links.symbols.enumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
    val externalSingletonFieldImports =
        links.symbols.singletons.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
    val externalDefaultEnumFieldImports =
        links.symbols.defaultEnumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
    val externalFieldImportCount = externalFieldImports.size
    val externalFunctionImports =
        links.functions.entries
            .sortedBy { (_, target) -> target.sortKey }
            .mapIndexed { index, (symbol, target) ->
                symbol to
                    target.copy(
                        importId =
                            ImportId.of(
                                (firstImportId + externalTypeImportCount + externalFieldImportCount + index).toUInt(),
                            ),
                    )
            }.toMap()
    val boxPayloadFields =
        valueClassBoxes.filter { it.symbol in externalClassTypes || it in externalBoxTypeImports }.associateWith { box ->
            box.componentSourceTypes.indices.map { index ->
                val target =
                    externalBoxFields.single {
                        it.ownerSymbol == box.symbol && it.exportName == box.payloadExportName(index)
                    }
                FieldRef.Imported(requireNotNull(externalFieldsBySortKey[target.sortKey]).importId)
            }
        }
    return GuestPlatformImports(
        types = externalTypeImports,
        boxTypes = externalBoxTypeImports,
        inlineTypes = externalInlineImports,
        fields = externalFieldImports,
        boxFields = externalBoxFields.map { externalFieldsBySortKey.getValue(it.sortKey) },
        boxPayloadFields = boxPayloadFields,
        emitter =
            GuestFunctionImports(
                classTypes = externalClassTypes,
                fieldsByGetter = externalGetterFieldImports,
                enumEntries = externalEnumFieldImports,
                singletons = externalSingletonFieldImports,
                defaultEnumEntries = externalDefaultEnumFieldImports,
                functions = externalFunctionImports,
            ),
    )
}

internal data class ExternalFunctionTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

internal data class ExternalTypeTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

internal data class ExternalFieldTarget(
    val exportName: String,
    val ownerSymbol: IrClassSymbol,
    val moduleHash: ByteArray,
    val static: Boolean,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

internal data class LinkedPlatformSymbols(
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
                if (element is IrValueDeclaration) considerType(element.type)
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
                val target = expression.symbol.owner.resolveFakeOverrideMaybeAbstract() ?: expression.symbol.owner
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

internal fun IrConstructor.platformConstructorSignature(): String =
    "constructor(${parameters.filter { it.kind == IrParameterKind.Regular }.joinToString(",") { it.type.canonicalPlatformType() }})"
