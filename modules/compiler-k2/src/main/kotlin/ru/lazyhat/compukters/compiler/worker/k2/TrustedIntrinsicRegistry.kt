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
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID || !callable.suspending) return null
        val operation =
            when (callable.name) {
                "print" -> {
                    0u.takeIf {
                        callable.parameters == listOf(TrustedValueType.STRING) && callable.result == TrustedValueType.UNIT
                    }
                }

                "println" -> {
                    1u.takeIf {
                        callable.parameters == listOf(TrustedValueType.STRING) && callable.result == TrustedValueType.UNIT
                    }
                }

                "readln" -> {
                    2u.takeIf {
                        callable.parameters.isEmpty() && callable.result == TrustedValueType.STRING
                    }
                }

                else -> {
                    null
                }
            }
        return operation?.let { TrustedIntrinsic.CapabilityOperation(capability = 0u, operation = it) }
    }
}
