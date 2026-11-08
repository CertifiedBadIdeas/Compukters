/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class, org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class)

package ru.lazyhat.compukters.compiler.k2.engine.build

import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirEvaluatorResult
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.getDeclaredConstructors
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
import ru.lazyhat.compukters.platform.k2.build.PlatformRecordCandidate
import ru.lazyhat.compukters.platform.k2.contracts.GuestContractAnnotations
import ru.lazyhat.compukters.platform.k2.contracts.GuestContractFirExtensionRegistrar
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** Source coordinates for expansion; signatures and boundary fields come from resolved native FIR. */
data class NativeGuestInterface(
    val qualifiedName: String,
    val path: String,
    val bodyEnd: Int,
    val headerEnd: Int,
    val hasSupertypes: Boolean,
    val companionStart: Int?,
    val companionBodyEnd: Int?,
    val companionHasSupertypes: Boolean,
)

fun analyzeNativeGuestContract(
    base: PlatformBundle,
    root: Path,
    addon: String,
): DeclarativeGuestContract {
    val sources =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted()
                .map { path ->
                    PlatformSource(root.relativize(path).invariantSeparatorsPathString, ImmutableBytes.of(Files.readAllBytes(path)))
                }.toList()
        }
    require(sources.isNotEmpty()) { "Guest module has no Kotlin sources: $root" }
    val module = PlatformModuleId(addon, "api")
    val sourceText = sources.associate { it.path to it.content.toByteArray().decodeToString(throwOnInvalidSequence = true) }
    val candidates =
        (
            base.modules.flatMap { PlatformMetadataCompiler().compile(it.id, it.sources).recordCandidates } +
                PlatformMetadataCompiler().compile(module, sources).recordCandidates
        ).associateBy { it.symbol }
    return CompuktersFirBuildEnvironment.create().use { environment ->
        val output =
            environment.compileGuest(
                module,
                sources,
                listOf(base.builtins) + base.modules,
                extensions = listOf(GuestContractFirExtensionRegistrar()),
            )
        val errors = output.diagnostics.diagnostics.filter { it.severity.name == "ERROR" }
        require(errors.isEmpty()) { errors.joinToString("\n") { "${it.factoryName}@${it.firstRange}: ${it.renderMessage()}" } }
        val session = output.frontendOutput.session
        val resolver = NativeBoundaryResolver(session, candidates)
        val interfaces = mutableListOf<NativeGuestInterface>()
        val types = mutableListOf<GuestContractType>()
        output.frontendOutput.fir.forEach { file ->
            val sourceFile = (file.source as KtPsiSourceElement).psi as KtFile
            sourceFile.collectDescendantsOfType<KtNamedDeclaration>().forEach { declaration ->
                require(!declaration.hasModifier(KtTokens.EXTERNAL_KEYWORD)) { "Only annotated server operations may declare intrinsics" }
                require(
                    declaration.name?.startsWith("__Compukters") != true,
                ) { "The __Compukters prefix is reserved for generated declarations" }
            }
            val pkg = file.packageDirective.packageFqName.asString()
            require(pkg == addon || pkg.startsWith("$addon.")) { "Guest source package must belong to $addon: $pkg" }
            val path = "${pkg.replace('.', '/')}/${file.name}"
            require(path in sourceText) { "Guest source path must match package $pkg: ${file.name}" }
            require(path.startsWith(pkg.replace('.', '/') + "/")) { "Guest source path must match package $pkg: $path" }
            file.declarations.filterIsInstance<FirRegularClass>().forEach type@{ klass ->
                val annotations =
                    listOf(GuestContractAnnotations.device, GuestContractAnnotations.service, GuestContractAnnotations.resource)
                        .mapNotNull { id -> klass.symbol.getAnnotationByClassId(id, session)?.let { id to it } }
                if (annotations.isEmpty()) return@type
                require(annotations.size == 1 && klass.classKind == ClassKind.INTERFACE && klass.typeParameters.isEmpty()) {
                    "${klass.name}: declare one non-generic @GuestDevice, @GuestService or @GuestResource interface"
                }
                val (kind, annotation) = annotations.single()
                val device = kind != GuestContractAnnotations.service
                val deviceId = if (kind == GuestContractAnnotations.device) annotation.string("id") else null
                require(
                    deviceId == null || deviceId.matches(Regex("[a-z][a-z0-9_.-]*:[a-z][a-z0-9_./-]*")),
                ) { "Invalid device contract id: $deviceId" }
                val owner = "$pkg.${klass.name.asString()}"
                val bindings = annotation.string("bindings").ifEmpty { "${klass.name.asString()}Bindings" }
                require(bindings.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid bindings owner: $bindings" }
                val operations =
                    klass.declarations.filterIsInstance<FirNamedFunction>().mapNotNull { function ->
                        val operationAnnotations =
                            GuestContractAnnotations.operations.mapNotNull { id ->
                                function.symbol.getAnnotationByClassId(id, session)?.let { id to it }
                            }
                        if (operationAnnotations.isEmpty()) {
                            require(
                                function.body != null,
                            ) { "$owner.${function.name}: bodyless methods require a server operation annotation" }
                            return@mapNotNull null
                        }
                        require(
                            operationAnnotations.size == 1 && function.body == null,
                        ) { "$owner.${function.name}: server operations must have no body" }
                        require(
                            function.typeParameters.isEmpty() && function.contextParameters.isEmpty() && function.receiverParameter == null,
                        ) {
                            "$owner.${function.name}: server operations cannot have type parameters or context/extension receivers"
                        }
                        val (operationId, operationAnnotation) = operationAnnotations.single()
                        val operationKind =
                            when (operationId.shortClassName.asString()) {
                                "GuestRead" -> GuestOperationKind.READ
                                "GuestCall" -> GuestOperationKind.CALL
                                "GuestCommand" -> GuestOperationKind.COMMAND
                                else -> GuestOperationKind.WAIT
                            }
                        val result = resolver.type(function.symbol.resolvedReturnType)
                        require(
                            operationKind != GuestOperationKind.COMMAND || result == "Unit",
                        ) { "$owner.${function.name}: @GuestCommand must return Unit" }
                        require(
                            operationKind != GuestOperationKind.READ || result != "Unit",
                        ) { "$owner.${function.name}: @GuestRead must return a value" }
                        val parameters =
                            function.valueParameters.map { parameter ->
                                require(
                                    parameter.defaultValue == null && !parameter.isVararg,
                                ) { "${function.name}: server parameters cannot have defaults or varargs" }
                                val type = resolver.type(parameter.symbol.resolvedReturnType)
                                require(type != "Unit") { "${function.name}: server parameters cannot be Unit" }
                                val constraints = parameter.annotations.mapNotNull { value -> constraint(value, type, session) }
                                GuestContractParameter(parameter.name.asString(), type, constraints)
                            }
                        val binding =
                            operationAnnotation.string("binding").ifEmpty {
                                klass.name.asString().replaceFirstChar(Char::lowercase) +
                                    function.name.asString().replaceFirstChar(Char::uppercase)
                            }
                        require(binding.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid binding: $binding" }
                        GuestContractOperation(
                            function.name.asString(),
                            result,
                            operationKind,
                            "$pkg.$bindings.$binding",
                            device,
                            parameters,
                            "",
                        )
                    }
                val psi = (klass.source as KtPsiSourceElement).psi as KtClass
                val body = requireNotNull(psi.body) { "$owner must declare an interface body" }
                val companion = psi.companionObjects.singleOrNull()
                require(companion == null || companion.body != null) { "$owner: a declared companion must have a body" }
                interfaces +=
                    NativeGuestInterface(
                        owner,
                        path,
                        requireNotNull(body.rBrace).textOffset,
                        body.textOffset,
                        psi.superTypeListEntries.isNotEmpty(),
                        companion?.body?.textOffset,
                        companion?.body?.rBrace?.textOffset,
                        companion?.superTypeListEntries?.isNotEmpty() == true,
                    )
                types +=
                    GuestContractType(
                        pkg,
                        klass.name.asString(),
                        deviceId,
                        false,
                        kind == GuestContractAnnotations.resource,
                        emptyList(),
                        operations,
                        emptyList(),
                        "",
                    )
            }
        }
        val operations = types.flatMap { it.operations }
        require(operations.distinctBy { it.binding }.size == operations.size) { "Duplicate server binding in $addon" }
        types.forEach { type ->
            val names =
                type.operations.map { it.name } +
                    type.operations.filter { it.kind == GuestOperationKind.COMMAND }.map { it.name + "Command" }
            require(names.distinct().size == names.size) { "${type.name}: overloaded or generated server method collision" }
        }
        DeclarativeGuestContract(types, sourceText, resolver.records.toMap(), interfaces)
    }
}

private class NativeBoundaryResolver(
    private val session: FirSession,
    private val candidates: Map<String, PlatformRecordCandidate>,
) {
    val records = linkedMapOf<String, GuestContractType>()
    private val active = mutableSetOf<String>()

    fun type(raw: ConeKotlinType): String {
        val type = raw.fullyExpandedType(session)
        require(
            !type.isMarkedNullable && type.typeArguments.isEmpty(),
        ) { "Nullable and generic server boundary types are unsupported: $type" }
        val symbol = type.toRegularClassSymbol(session) ?: error("Server boundary requires a concrete type: $type")
        val name = symbol.classId.asSingleFqName().asString()
        if (name in scalarNames) return name.removePrefix("kotlin.")
        if (symbol.getAnnotationByClassId(GuestContractAnnotations.resource, session) != null) return name
        if (name in records) return name
        require(active.add(name) && active.size <= 8) { "Cyclic or excessively nested server boundary: $name" }
        val candidate = requireNotNull(candidates[name]) { "Unsupported server boundary type $name" }
        require(candidate.rejection == null) { "$name: ${candidate.rejection}" }
        val constructor =
            symbol
                .declaredMemberScope(
                    session,
                    memberRequiredPhase = null,
                ).getDeclaredConstructors()
                .single { it.fir.isPrimary }
        val fields =
            constructor.valueParameterSymbols.map { field ->
                val fieldType = type(field.resolvedReturnType)
                require(fieldType != "Unit" && fieldType != "ByteArray") { "$name: record fields cannot be Unit or arrays" }
                GuestContractParameter(field.name.asString(), fieldType, emptyList())
            }
        records[name] =
            GuestContractType(
                symbol.classId.packageFqName.asString(),
                symbol.classId.relativeClassName.asString(),
                null,
                true,
                false,
                fields,
                emptyList(),
                emptyList(),
                "",
            )
        active.remove(name)
        return name
    }

    companion object {
        private val scalarNames =
            setOf(
                "Unit",
                "Int",
                "Long",
                "Float",
                "Double",
                "Boolean",
                "Char",
                "String",
                "ByteArray",
            ).mapTo(mutableSetOf()) {
                "kotlin.$it"
            }
    }
}

private fun FirAnnotation.string(name: String) = getStringArgument(Name.identifier(name)).orEmpty()

private fun constraint(
    annotation: FirAnnotation,
    type: String,
    session: FirSession,
): GuestArgumentConstraint? {
    val id = annotation.toAnnotationClassId(session) ?: return null
    if (id.packageFqName.asString() != GuestContractAnnotations.PACKAGE) return null
    val arguments = FirExpressionEvaluator.evaluateAnnotationArguments(annotation, session)

    fun number(
        name: String,
        default: Number,
    ): Number {
        val argument = arguments[Name.identifier(name)] ?: return default
        return ((argument as? FirEvaluatorResult.Evaluated)?.result as? FirLiteralExpression)?.value as? Number
            ?: error("$id.$name must be a numeric constant")
    }
    return when (val name = id.shortClassName.asString()) {
        "Finite" -> {
            require(type == "Double" || type == "Float") { "@Finite requires Double or Float" }
            GuestArgumentConstraint(name)
        }

        "DoubleRange" -> {
            require(type == "Double") { "@DoubleRange requires Double" }
            val min = number("min", -Double.MAX_VALUE).toDouble()
            val max = number("max", Double.MAX_VALUE).toDouble()
            require(min.isFinite() && max.isFinite() && min <= max) { "Invalid @DoubleRange" }
            GuestArgumentConstraint(name, min.toString(), max.toString())
        }

        "IntRange" -> {
            require(type == "Int") { "@IntRange requires Int" }
            val min = number("min", Int.MIN_VALUE).toInt()
            val max = number("max", Int.MAX_VALUE).toInt()
            require(min <= max) { "Invalid @IntRange" }
            GuestArgumentConstraint(name, min.toString(), max.toString())
        }

        "MaxLength" -> {
            require(type == "String" || type == "ByteArray") { "@MaxLength requires String or ByteArray" }
            val value = number("value", -1).toInt()
            require(value >= 0) { "Invalid @MaxLength" }
            GuestArgumentConstraint(name, maximum = value.toString())
        }

        else -> {
            null
        }
    }
}
