/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** Canonical stateless platform objects have erased receivers, including after shared inlining. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object GuestInlineObjectReceivers {
    fun lower(
        file: IrFile,
        session: CompilationSession,
    ) {
        val aliases = mutableMapOf<IrValueSymbol, IrGetObjectValue>()
        file.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                override fun visitVariable(declaration: IrVariable) {
                    val receiver =
                        when (val initializer = declaration.initializer) {
                            is IrGetObjectValue -> initializer
                            is IrGetValue -> aliases[initializer.symbol]
                            else -> null
                        }
                    if (!declaration.isVar && receiver != null) {
                        val owner = receiver.symbol.owner
                        if (session.trustedPlatformModule(owner.file.fileEntry.name) != null && !owner.isManagedPlatformObject()) {
                            aliases[declaration.symbol] = receiver
                        }
                    }
                    super.visitVariable(declaration)
                }
            },
            null,
        )
        if (aliases.isEmpty()) return
        file.transformChildren(
            object : IrElementTransformerVoid() {
                override fun visitGetValue(expression: IrGetValue): IrExpression =
                    aliases[expression.symbol]?.deepCopyWithSymbols() ?: expression
            },
            null,
        )
        file.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                override fun visitBlockBody(body: IrBlockBody) {
                    body.statements.removeAll { it is IrVariable && it.symbol in aliases }
                    super.visitBlockBody(body)
                }

                override fun visitContainerExpression(expression: IrContainerExpression) {
                    expression.statements.removeAll { it is IrVariable && it.symbol in aliases }
                    super.visitContainerExpression(expression)
                }
            },
            null,
        )
    }
}
