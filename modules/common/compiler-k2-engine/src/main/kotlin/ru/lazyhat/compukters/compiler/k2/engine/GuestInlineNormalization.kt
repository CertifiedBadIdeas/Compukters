/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.PreSerializationSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** The single Guest source normalization boundary; canonical platform calls remain atomic. */
@OptIn(UnsafeDuringIrConstructionAPI::class, CompilerConfiguration.Internals::class)
internal object GuestInlineNormalization {
    fun lower(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
        session: CompilationSession,
        maximumVariants: Int = 256,
        maximumSpecializationDepth: Int = 64,
        maximumWork: Long = 1_000_000,
        maximumDepth: Int = 64,
    ) {
        if (module in session.normalizedGuestModules) return
        module.files.forEach { file ->
            session.virtualSourcePath(file.fileEntry.name)?.let { path ->
                session.recordSourceFile(path, file.fileEntry)
                file.accept(
                    object : IrVisitorVoid() {
                        override fun visitElement(element: IrElement) {
                            session.recordSource(element, path)
                            element.acceptChildren(this, null)
                        }
                    },
                    null,
                )
            }
        }
        val files =
            module.files.filter {
                session.virtualSourcePath(it.fileEntry.name) != null &&
                    (
                        session.trustedPlatformModule(it.fileEntry.name) == null ||
                            session.virtualSourcePath(it.fileEntry.name) in session.sourcePlatformPaths
                    )
            }
        var needed = false
        files.forEach { file ->
            file.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildren(this, null)
                    }

                    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                        if (declaration.isInline) needed = true
                        super.visitSimpleFunction(declaration)
                    }

                    override fun visitCall(expression: IrCall) {
                        if (expression.symbol.owner.isInline) needed = true
                        super.visitCall(expression)
                    }
                },
                null,
            )
        }
        if (!needed) {
            session.normalizedGuestModules += module
            return
        }
        val admitted = files.toSet()

        fun sourceTarget(
            function: IrSimpleFunction,
            location: IrElement,
        ): IrSimpleFunction? {
            if (!function.isInline) return null
            val file = runCatching { function.file }.getOrNull()
            if (file !in admitted) {
                val moduleId = file?.let { session.trustedPlatformModule(it.fileEntry.name) }
                val symbol = function.fqNameWhenAvailable?.asString()
                val signature = function.canonicalPlatformSignature()
                val canonical =
                    (file == null || moduleId != null) && (
                        session.platformFunctions.any { it.symbol == symbol && it.signature == signature } ||
                            session.canonicalIntrinsicRegistry?.handlers?.keys.orEmpty().any {
                                (
                                    moduleId?.let { sourceModule -> it.module == sourceModule }
                                        ?: (it.module in session.selectedPlatformModules)
                                ) &&
                                    it.callableId.asSingleFqName().asString() == symbol && it.signature.value == signature
                            }
                    )
                if (canonical) return null
                throw UnsupportedKotlinIr(location, "inline body is not from an admitted Guest source")
            }
            if (function.parent !is IrFile) {
                throw UnsupportedKotlinIr(
                    location,
                    "inline functions currently require a top-level declaration",
                )
            }
            if (function.isSuspend) throw UnsupportedKotlinIr(location, "suspend inline functions are unsupported")
            if (function.typeParameters.any { it.isReified }) {
                throw UnsupportedKotlinIr(
                    location,
                    "reified inline specialization is unsupported",
                )
            }
            if (function.body == null) throw UnsupportedKotlinIr(location, "inline source body is unavailable")
            return function
        }
        val targetOf: (IrCall) -> IrSimpleFunction? = { sourceTarget(it.symbol.owner, it) }
        files.forEach { file ->
            file.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildren(this, null)
                    }

                    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                        if (declaration.isInline) sourceTarget(declaration, declaration)
                        super.visitSimpleFunction(declaration)
                    }
                },
                null,
            )
        }
        GuestInlineExpansionGuard(maximumWork, maximumDepth, targetOf).verify(roots(files))
        val specialization = GuestInlineSpecialization(maximumVariants, maximumSpecializationDepth, targetOf)
        files.forEach { file ->
            try {
                specialization.lower(file)
            } catch (
                failure: UnsupportedKotlinIr,
            ) {
                throw failure
            } catch (failure: IllegalArgumentException) {
                throw UnsupportedKotlinIr(file, failure.message ?: "inline specialization failed")
            }
        }
        var normalizationLocation: IrElement = files.first()
        val context =
            object : LoweringContext {
                override val configuration = CompilerConfiguration()
                override var inVerbosePhase = false
                override val irBuiltIns = pluginContext.irBuiltIns
                override val irFactory = pluginContext.irFactory
                override val messageCollector = MessageCollector.NONE
                override val symbols: PreSerializationSymbols get() =
                    throw UnsupportedKotlinIr(normalizationLocation, "inline shape requires backend-specific symbols")
                override val sharedVariablesManager: SharedVariablesManager get() =
                    throw UnsupportedKotlinIr(normalizationLocation, "inline shape requires backend shared-variable lowering")
            }
        val resolver =
            object : InlineFunctionResolver() {
                override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? =
                    (symbol.owner as? IrSimpleFunction)?.let { sourceTarget(it, it) }
            }
        val inliner = object : FunctionInlining(context, resolver) {}
        files.forEach { file ->
            normalizationLocation = file
            UpgradeCallableReferences(
                context,
                upgradeFunctionReferencesAndLambdas = true,
                upgradePropertyReferences = false,
                upgradeLocalDelegatedPropertyReferences = false,
                upgradeSamConversions = false,
                upgradeExtractedAdaptedBlocks = false,
                castDispatchReceiver = false,
                generateFakeAccessorsForReflectionProperty = false,
            ).lower(file)
        }
        roots(files).forEach { declaration ->
            normalizationLocation = declaration
            when (declaration) {
                is IrFunction -> {
                    declaration.parameters.forEach { parameter -> parameter.defaultValue?.let { inliner.lower(it, parameter) } }
                    declaration.body?.let { inliner.lower(it, declaration) }
                }

                is IrField -> {
                    declaration.initializer?.let { inliner.lower(it, declaration) }
                }

                is IrAnonymousInitializer -> {
                    inliner.lower(declaration.body, declaration)
                }
            }
        }
        val required = mutableSetOf<IrFunctionSymbol>()
        val pending = ArrayDeque<IrSimpleFunction>()

        fun retain(symbol: IrFunctionSymbol) {
            val target = symbol.owner as? IrSimpleFunction ?: return
            if (target.isInline && runCatching { target.file in admitted }.getOrDefault(false) && required.add(symbol)) pending += target
        }
        val references =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                    if (!declaration.isInline || declaration.symbol in required) super.visitSimpleFunction(declaration)
                }

                override fun visitCall(expression: IrCall) {
                    if (targetOf(expression) != null) throw UnsupportedKotlinIr(expression, "source inline call was not expanded")
                    super.visitCall(expression)
                }

                override fun visitFunctionReference(expression: IrFunctionReference) {
                    retain(expression.symbol)
                    expression.reflectionTarget?.let(::retain)
                    super.visitFunctionReference(expression)
                }

                override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                    expression.reflectionTargetSymbol?.let(::retain)
                    super.visitRichFunctionReference(expression)
                }
            }
        files.forEach { it.accept(references, null) }
        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            // Open generic function references remain subject to the existing signature rejection.
            if (target.typeParameters.isEmpty()) target.accept(references, null)
        }
        files.forEach { file -> file.declarations.removeAll { it is IrSimpleFunction && it.isInline && it.symbol !in required } }
        session.normalizedGuestModules += module
    }

    private fun roots(files: List<IrFile>): List<IrDeclaration> =
        buildList {
            val visitor =
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildren(this, null)
                    }

                    override fun visitBody(body: IrBody) {}

                    override fun visitFunction(declaration: IrFunction) {
                        if (declaration is IrSimpleFunction && declaration.isInline && declaration.typeParameters.isNotEmpty()) return
                        add(declaration)
                    }

                    override fun visitField(declaration: IrField) {
                        add(declaration)
                    }

                    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
                        add(declaration)
                    }
                }
            files.forEach { it.accept(visitor, null) }
        }
}
