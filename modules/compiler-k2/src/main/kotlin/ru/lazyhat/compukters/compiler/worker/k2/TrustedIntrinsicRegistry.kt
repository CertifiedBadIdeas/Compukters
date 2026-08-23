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

internal enum class TrustedValueType {
    UNIT,
    STRING,
    INT,
    OTHER,
}

internal data class TrustedCallableIdentity(
    val bundleIdentity: String?,
    val name: String,
    val suspending: Boolean,
    val parameters: List<TrustedValueType>,
    val result: TrustedValueType,
)

internal sealed interface TrustedIntrinsic {
    data class CapabilityOperation(
        val capability: UInt,
        val operation: UInt,
        val asynchronous: Boolean,
    ) : TrustedIntrinsic
}

internal fun interface TrustedIntrinsicProvider {
    fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic?
}

internal object TrustedIntrinsicRegistry {
    const val TERMINAL_BUNDLE_ID = "compukter.terminal-api@1"

    private val providers: List<TrustedIntrinsicProvider> = listOf(TerminalIntrinsicProvider)

    fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? =
        providers.firstNotNullOfOrNull { provider -> provider.resolve(callable) }
}

private object TerminalIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID) return null
        val intrinsic =
            when (callable.name) {
                "terminalWrite" -> {
                    sync(0u, callable, listOf(TrustedValueType.STRING), TrustedValueType.UNIT)
                }

                "terminalErasePrevious" -> {
                    sync(1u, callable, emptyList(), TrustedValueType.UNIT)
                }

                "terminalClear" -> {
                    sync(2u, callable, emptyList(), TrustedValueType.UNIT)
                }

                "terminalAwaitEvent" -> {
                    async(3u, callable, TrustedValueType.INT)
                }

                "terminalEventText" -> {
                    sync(4u, callable, emptyList(), TrustedValueType.STRING)
                }

                "terminalEventKey" -> {
                    sync(5u, callable, emptyList(), TrustedValueType.INT)
                }

                "terminalEventAction" -> {
                    sync(6u, callable, emptyList(), TrustedValueType.INT)
                }

                "terminalEventModifiers" -> {
                    sync(7u, callable, emptyList(), TrustedValueType.INT)
                }

                "terminalFinishEvent" -> {
                    sync(8u, callable, emptyList(), TrustedValueType.UNIT)
                }

                else -> {
                    null
                }
            }
        return intrinsic
    }

    private fun sync(
        operation: UInt,
        callable: TrustedCallableIdentity,
        parameters: List<TrustedValueType>,
        result: TrustedValueType,
    ): TrustedIntrinsic? =
        TrustedIntrinsic.CapabilityOperation(0u, operation, asynchronous = false).takeIf {
            !callable.suspending && callable.parameters == parameters && callable.result == result
        }

    private fun async(
        operation: UInt,
        callable: TrustedCallableIdentity,
        result: TrustedValueType,
    ): TrustedIntrinsic? =
        TrustedIntrinsic.CapabilityOperation(0u, operation, asynchronous = true).takeIf {
            callable.suspending && callable.parameters.isEmpty() && callable.result == result
        }
}
