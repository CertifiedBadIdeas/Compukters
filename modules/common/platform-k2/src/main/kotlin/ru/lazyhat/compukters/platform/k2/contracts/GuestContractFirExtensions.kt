/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ru.lazyhat.compukters.platform.k2.contracts

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms

/** The source frontend and IDEA use the same native interface augmentation. No JVM model participates. */
class GuestContractFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::GuestContractDeclarations
        +::GuestContractSupertypes
    }
}

private object GuestContractKey : GeneratedDeclarationKey()

object GuestContractAnnotations {
    const val PACKAGE = "ru.lazyhat.compukters.api.contracts"
    val device = ClassId.topLevel(FqName("$PACKAGE.GuestDevice"))
    val service = ClassId.topLevel(FqName("$PACKAGE.GuestService"))
    val resource = ClassId.topLevel(FqName("$PACKAGE.GuestResource"))
    val command = ClassId.topLevel(FqName("$PACKAGE.GuestCommand"))
    val operations = listOf("GuestRead", "GuestCall", "GuestCommand", "GuestWait").map { ClassId.topLevel(FqName("$PACKAGE.$it")) }
}

private val devicePredicate = LookupPredicate.create { annotated(GuestContractAnnotations.device.asSingleFqName()) }
private val servicePredicate = LookupPredicate.create { annotated(GuestContractAnnotations.service.asSingleFqName()) }
private val resourcePredicate = LookupPredicate.create { annotated(GuestContractAnnotations.resource.asSingleFqName()) }
private val commandPredicate = LookupPredicate.create { annotated(GuestContractAnnotations.command.asSingleFqName()) }
private val operationPredicate = LookupPredicate.create { annotated(GuestContractAnnotations.operations.map { it.asSingleFqName() }) }
private val companionPredicate =
    LookupPredicate.create {
        parentAnnotated(GuestContractAnnotations.device.asSingleFqName(), GuestContractAnnotations.service.asSingleFqName())
    }
private val peripheral = ClassId.topLevel(FqName("compukter.peripheral.Peripheral"))
private val provider = ClassId.topLevel(FqName("compukter.peripheral.TypedPeripheralProvider"))
private val command = ClassId.topLevel(FqName("compukter.commands.Command"))

private fun FirSession.contractCompanionOwner(symbol: FirClassSymbol<*>): FirClassSymbol<*>? {
    if (moduleData.platform != CompuktersPlatforms.default) return null
    if ((symbol.fir as? FirRegularClass)?.status?.isCompanion != true) return null
    val outer = symbol.classId.outerClassId ?: return null
    val owner = symbolProvider.getClassLikeSymbolByClassId(outer) as? FirClassSymbol<*> ?: return null
    return owner.takeIf { predicateBasedProvider.matches(devicePredicate, it) || predicateBasedProvider.matches(servicePredicate, it) }
}

private class GuestContractSupertypes(
    session: FirSession,
) : FirSupertypeGenerationExtension(session) {
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(devicePredicate, companionPredicate)
    }

    override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
        session.moduleData.platform == CompuktersPlatforms.default && declaration is FirRegularClass &&
            (
                (declaration.classKind == ClassKind.INTERFACE && session.predicateBasedProvider.matches(devicePredicate, declaration)) ||
                    session.contractCompanionOwner(declaration.symbol) != null
            )

    override fun computeAdditionalSupertypes(
        classLikeDeclaration: FirClassLikeDeclaration,
        resolvedSupertypes: List<FirResolvedTypeRef>,
        typeResolver: TypeResolveService,
    ): List<ConeKotlinType> {
        val owner = session.contractCompanionOwner(classLikeDeclaration.symbol as FirClassSymbol<*>)
        val type =
            when {
                owner == null -> peripheral.constructClassLikeType(emptyArray(), false)

                session.predicateBasedProvider.matches(
                    devicePredicate,
                    owner,
                ) -> provider.constructClassLikeType(arrayOf(owner.defaultType()), false)

                else -> owner.defaultType()
            }
        return if (resolvedSupertypes.any { it.coneType == type }) emptyList() else listOf(type)
    }
}

private class GuestContractDeclarations(
    session: FirSession,
) : FirDeclarationGenerationExtension(session) {
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(devicePredicate, servicePredicate, resourcePredicate, commandPredicate, operationPredicate, companionPredicate)
    }

    private fun FirClassSymbol<*>.isDevice() = session.predicateBasedProvider.matches(devicePredicate, this)

    private fun FirClassSymbol<*>.isService() = session.predicateBasedProvider.matches(servicePredicate, this)

    private fun FirClassSymbol<*>.isContract(): Boolean =
        session.moduleData.platform == CompuktersPlatforms.default && (fir as? FirRegularClass)?.classKind == ClassKind.INTERFACE &&
            (isDevice() || isService() || session.predicateBasedProvider.matches(resourcePredicate, this))

    private fun companionOwner(symbol: FirClassSymbol<*>): FirClassSymbol<*>? = session.contractCompanionOwner(symbol)

    override fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        context: NestedClassGenerationContext,
    ): Set<Name> =
        if (classSymbol.isContract() && (classSymbol.isDevice() || classSymbol.isService()) &&
            (
                (classSymbol.fir as FirRegularClass)
                    .companionObjectSymbol
                    ?.fir
                    ?.origin
                    .let { it == null || it == GuestContractKey.origin }
            )
        ) {
            setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
        } else {
            emptySet()
        }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: NestedClassGenerationContext,
    ): FirClassLikeSymbol<*>? {
        if (name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT || !owner.isContract()) return null
        return createCompanionObject(owner, GuestContractKey) {
            superType(if (owner.isDevice()) provider.constructClassLikeType(arrayOf(owner.defaultType()), false) else owner.defaultType())
        }.symbol
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext,
    ): Set<Name> {
        val outer = companionOwner(classSymbol)
        if (outer?.isDevice() == true) {
            return buildSet {
                if (classSymbol.fir.origin == GuestContractKey.origin) add(SpecialNames.INIT)
                add(Name.identifier("wrap"))
            }
        }
        val contract = if (outer?.isService() == true) outer else classSymbol.takeIf { it.isContract() } ?: return emptySet()
        return buildSet {
            if (outer != null && classSymbol.fir.origin == GuestContractKey.origin) add(SpecialNames.INIT)
            functions(contract).forEach { function ->
                if (outer != null &&
                    GuestContractAnnotations.operations.any { function.symbol.hasAnnotation(it, session) }
                ) {
                    add(function.name)
                }
                if (function.symbol.hasAnnotation(GuestContractAnnotations.command, session)) {
                    add(Name.identifier("${function.name.asString()}Command"))
                }
            }
        }
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        if (companionOwner(context.owner) == null || context.owner.fir.origin != GuestContractKey.origin) return emptyList()
        return listOf(createConstructor(context.owner, GuestContractKey, isPrimary = true) { visibility = Visibilities.Private }.symbol)
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        val owner = context?.owner ?: return emptyList()
        val outer = companionOwner(owner)
        if (outer?.isDevice() == true && callableId.callableName.asString() == "wrap") {
            return listOf(
                createMemberFunction(owner, GuestContractKey, callableId.callableName, outer.defaultType()) {
                    visibility = Visibilities.Protected
                    status { isOverride = true }
                    valueParameter(Name.identifier("handle"), session.builtinTypes.intType.coneType)
                    withGeneratedDefaultBody()
                }.symbol,
            )
        }
        val contract = if (outer?.isService() == true) outer else owner.takeIf { it.isContract() } ?: return emptyList()
        return functions(contract).mapNotNull { function ->
            val factory =
                callableId.callableName.asString() == "${function.name.asString()}Command" &&
                    function.symbol.hasAnnotation(GuestContractAnnotations.command, session)
            val serviceCall =
                outer != null && callableId.callableName == function.name &&
                    GuestContractAnnotations.operations.any { function.symbol.hasAnnotation(it, session) }
            if (!factory && !serviceCall) return@mapNotNull null
            // Server-bound generics/context receivers are rejected by the contract validator.
            if (function.typeParameters.isNotEmpty() || function.contextParameters.isNotEmpty() || function.receiverParameter != null) {
                return@mapNotNull null
            }
            val result =
                if (factory) {
                    command.constructClassLikeType(
                        arrayOf(session.builtinTypes.unitType.coneType),
                        false,
                    )
                } else {
                    function.symbol.resolvedReturnType
                }
            createMemberFunction(owner, GuestContractKey, callableId.callableName, result) {
                modality = if (outer == null) Modality.ABSTRACT else Modality.FINAL
                status { isOverride = outer != null }
                function.valueParameters.forEach { valueParameter(it.name, it.symbol.resolvedReturnType) }
                if (outer != null) withGeneratedDefaultBody()
            }.symbol
        }
    }

    // Inspect the author's declarations, excluding this extension's own generated members.
    @OptIn(DirectDeclarationsAccess::class)
    private fun functions(owner: FirClassSymbol<*>): List<FirNamedFunction> = owner.fir.declarations.filterIsInstance<FirNamedFunction>()
}
