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

internal enum class TrustedValueType {
    UNIT,
    STRING,
    INT,
    BOOL,
    CHAR,
    NOTHING,
    OTHER,
}

internal enum class TrustedCallableOrigin {
    TRUSTED_SDK_SOURCE,
    PINNED_KOTLIN_STDLIB,
    PLAYER_SOURCE,
}

internal data class TrustedCallableIdentity(
    val bundleIdentity: String?,
    val name: String,
    val suspending: Boolean,
    val parameters: List<TrustedValueType>,
    val result: TrustedValueType,
    val origin: TrustedCallableOrigin =
        if (bundleIdentity == null) TrustedCallableOrigin.PLAYER_SOURCE else TrustedCallableOrigin.TRUSTED_SDK_SOURCE,
)

internal data class TrustedCapabilityIdentity(
    val namespace: String,
    val name: String,
    val abiMajor: UShort,
    val abiMinor: UShort,
    val operationCount: UInt,
) : Comparable<TrustedCapabilityIdentity> {
    override fun compareTo(other: TrustedCapabilityIdentity): Int =
        compareValuesBy(this, other, { it.namespace }, { it.name }, { it.abiMajor }, { it.abiMinor }, { it.operationCount })
}

internal enum class BlockingMode {
    NONE,
    VM_TASK,
}

internal sealed interface TrustedIntrinsic {
    data class CapabilityOperation(
        val capability: TrustedCapabilityIdentity,
        val operation: UInt,
        val blocking: BlockingMode,
        val terminal: Boolean = false,
    ) : TrustedIntrinsic

    data class StandardOutput(
        val newline: Boolean,
        val declaredType: TrustedValueType?,
    ) : TrustedIntrinsic
}

internal fun interface TrustedIntrinsicProvider {
    fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic?
}

internal data class TrustedApiSourceBundle(
    val identity: String,
    val resource: String,
    val fileName: String,
)

internal object TrustedIntrinsicRegistry {
    const val KOTLIN_STDLIB_BUNDLE_ID = "kotlin-stdlib@2.4.10"
    const val STDIO_BUNDLE_ID = "compukter.stdio-api@1"
    const val TERMINAL_BUNDLE_ID = "compukter.terminal-api@1"
    const val PROCESS_BUNDLE_ID = "compukter.process-api@2"
    const val FILESYSTEM_BUNDLE_ID = "compukter.filesystem-api@1"
    const val COMPILER_BUNDLE_ID = "compukter.compiler-api@1"
    const val REDSTONE_BUNDLE_ID = "compukter.redstone-api@1"
    val CORE_SOURCE_BUNDLES =
        listOf(
            TrustedApiSourceBundle(
                STDIO_BUNDLE_ID,
                "/compukter-guest-api/compukter/io/Stderr.kt",
                "stdio.kt",
            ),
            TrustedApiSourceBundle(
                TERMINAL_BUNDLE_ID,
                "/compukter-guest-api/compukter/terminal/Terminal.kt",
                "terminal.kt",
            ),
            TrustedApiSourceBundle(
                PROCESS_BUNDLE_ID,
                "/compukter-guest-api/compukter/process/Process.kt",
                "process.kt",
            ),
            TrustedApiSourceBundle(
                FILESYSTEM_BUNDLE_ID,
                "/compukter-guest-api/compukter/filesystem/FileSystem.kt",
                "filesystem.kt",
            ),
            TrustedApiSourceBundle(
                COMPILER_BUNDLE_ID,
                "/compukter-guest-api/compukter/compiler/Compiler.kt",
                "compiler.kt",
            ),
            TrustedApiSourceBundle(
                REDSTONE_BUNDLE_ID,
                "/compukter-guest-api/compukter/redstone/Redstone.kt",
                "redstone.kt",
            ),
        )
    val TERMINAL_CAPABILITY = TrustedCapabilityIdentity("compukter", "terminal", 2u.toUShort(), 0u.toUShort(), 14u)
    val STDIO_CAPABILITY = TrustedCapabilityIdentity("compukter", "stdio", 1u.toUShort(), 0u.toUShort(), 3u)
    val PROCESS_CAPABILITY = TrustedCapabilityIdentity("compukter", "process", 2u.toUShort(), 0u.toUShort(), 3u)
    val FILESYSTEM_CAPABILITY = TrustedCapabilityIdentity("compukter", "filesystem", 1u.toUShort(), 0u.toUShort(), 7u)
    val COMPILER_CAPABILITY = TrustedCapabilityIdentity("compukter", "compiler", 1u.toUShort(), 0u.toUShort(), 2u)
    val REDSTONE_CAPABILITY = TrustedCapabilityIdentity("compukter", "redstone", 1u.toUShort(), 0u.toUShort(), 8u)

    private val providers: List<TrustedIntrinsicProvider> =
        listOf(
            CompilerIntrinsicProvider,
            FilesystemIntrinsicProvider,
            ProcessIntrinsicProvider,
            RedstoneIntrinsicProvider,
            StdioIntrinsicProvider,
            TerminalIntrinsicProvider,
        )

    fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? =
        providers.firstNotNullOfOrNull { provider -> provider.resolve(callable) }
}

private object RedstoneIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (
            callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE ||
            callable.bundleIdentity != TrustedIntrinsicRegistry.REDSTONE_BUNDLE_ID ||
            callable.suspending
        ) {
            return null
        }
        val binding = "compukter.redstone.RedstoneBindings."
        return when (callable.name) {
            "${binding}input" -> operation(0u, callable, listOf(TrustedValueType.INT), TrustedValueType.INT, BlockingMode.NONE)
            "${binding}awaitInputChange" ->
                operation(1u, callable, listOf(TrustedValueType.INT), TrustedValueType.INT, BlockingMode.VM_TASK)
            "${binding}awaitInput" ->
                operation(2u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.INT, BlockingMode.VM_TASK)
            "${binding}awaitAtLeastInput" ->
                operation(3u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.INT, BlockingMode.VM_TASK)
            "${binding}awaitAtMostInput" ->
                operation(4u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.INT, BlockingMode.VM_TASK)
            "${binding}outputs" -> operation(5u, callable, emptyList(), TrustedValueType.INT, BlockingMode.NONE)
            "${binding}setOutput" ->
                operation(6u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.UNIT, BlockingMode.VM_TASK)
            "${binding}setOutputs" ->
                operation(7u, callable, listOf(TrustedValueType.INT), TrustedValueType.UNIT, BlockingMode.VM_TASK)
            else -> null
        }
    }

    private fun operation(
        operation: UInt,
        callable: TrustedCallableIdentity,
        parameters: List<TrustedValueType>,
        result: TrustedValueType,
        blocking: BlockingMode,
    ): TrustedIntrinsic? =
        TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.REDSTONE_CAPABILITY, operation, blocking).takeIf {
            callable.parameters == parameters && callable.result == result
        }
}

private object StdioIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? =
        when (callable.bundleIdentity) {
            TrustedIntrinsicRegistry.KOTLIN_STDLIB_BUNDLE_ID -> resolveKotlin(callable)
            TrustedIntrinsicRegistry.STDIO_BUNDLE_ID -> resolveFacade(callable)
            else -> null
        }

    private fun resolveKotlin(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (callable.origin != TrustedCallableOrigin.PINNED_KOTLIN_STDLIB) return null
        if (callable.suspending) return null
        if (callable.name == "kotlin.io.readln") {
            return TrustedIntrinsic
                .CapabilityOperation(
                    TrustedIntrinsicRegistry.STDIO_CAPABILITY,
                    0u,
                    BlockingMode.VM_TASK,
                ).takeIf { callable.parameters.isEmpty() && callable.result == TrustedValueType.STRING }
        }
        if (callable.result != TrustedValueType.UNIT) return null
        if (callable.name == "kotlin.io.println" && callable.parameters.isEmpty()) {
            return TrustedIntrinsic.StandardOutput(newline = true, declaredType = null)
        }
        if (callable.name !in setOf("kotlin.io.print", "kotlin.io.println")) return null
        val type = callable.parameters.singleOrNull() ?: return null
        if (type !in setOf(TrustedValueType.OTHER, TrustedValueType.INT, TrustedValueType.BOOL, TrustedValueType.CHAR)) return null
        return TrustedIntrinsic.StandardOutput(
            newline = callable.name == "kotlin.io.println",
            declaredType = type,
        )
    }

    private fun resolveFacade(callable: TrustedCallableIdentity): TrustedIntrinsic? =
        if (callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE) {
            null
        } else {
            when (callable.name) {
                "compukter.io.StdioBindings.write" -> {
                    sync(
                        1u,
                        callable,
                        listOf(TrustedValueType.STRING),
                        TrustedValueType.UNIT,
                        TrustedIntrinsicRegistry.STDIO_CAPABILITY,
                    )
                }

                "compukter.io.Stderr.write" -> {
                    sync(
                        2u,
                        callable,
                        listOf(TrustedValueType.STRING),
                        TrustedValueType.UNIT,
                        TrustedIntrinsicRegistry.STDIO_CAPABILITY,
                    )
                }

                else -> {
                    null
                }
            }
        }
}

private object CompilerIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (
            callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE ||
            callable.bundleIdentity != TrustedIntrinsicRegistry.COMPILER_BUNDLE_ID
        ) {
            return null
        }
        return when (callable.name) {
            "compukter.compiler.Compiler.compile" -> {
                TrustedIntrinsic
                    .CapabilityOperation(
                        TrustedIntrinsicRegistry.COMPILER_CAPABILITY,
                        0u,
                        blocking = BlockingMode.VM_TASK,
                    ).takeIf {
                        !callable.suspending &&
                            callable.parameters == listOf(TrustedValueType.STRING, TrustedValueType.STRING) &&
                            callable.result == TrustedValueType.INT
                    }
            }

            "compukter.compiler.Compiler.diagnostics" -> {
                sync(
                    1u,
                    callable,
                    emptyList(),
                    TrustedValueType.STRING,
                    TrustedIntrinsicRegistry.COMPILER_CAPABILITY,
                )
            }

            else -> {
                null
            }
        }
    }
}

private object FilesystemIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (
            callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE ||
            callable.bundleIdentity != TrustedIntrinsicRegistry.FILESYSTEM_BUNDLE_ID ||
            callable.suspending
        ) {
            return null
        }
        return when (callable.name) {
            "compukter.filesystem.FileSystem.stat" -> {
                sync(
                    0u,
                    callable,
                    listOf(TrustedValueType.STRING),
                    TrustedValueType.INT,
                    TrustedIntrinsicRegistry.FILESYSTEM_CAPABILITY,
                )
            }

            "compukter.filesystem.FileSystem.list" -> {
                sync(
                    1u,
                    callable,
                    listOf(TrustedValueType.STRING),
                    TrustedValueType.STRING,
                    TrustedIntrinsicRegistry.FILESYSTEM_CAPABILITY,
                )
            }

            "compukter.filesystem.FileSystem.readText" -> {
                sync(
                    2u,
                    callable,
                    listOf(TrustedValueType.STRING),
                    TrustedValueType.STRING,
                    TrustedIntrinsicRegistry.FILESYSTEM_CAPABILITY,
                )
            }

            "compukter.filesystem.FileSystem.writeText" -> {
                sync(
                    3u,
                    callable,
                    listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                    TrustedValueType.INT,
                    TrustedIntrinsicRegistry.FILESYSTEM_CAPABILITY,
                )
            }

            else -> {
                null
            }
        }
    }
}

private object ProcessIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (
            callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE ||
            callable.bundleIdentity != TrustedIntrinsicRegistry.PROCESS_BUNDLE_ID ||
            callable.suspending
        ) {
            return null
        }
        return when (callable.name) {
            "compukter.process.ProcessBindings.run" -> {
                TrustedIntrinsic
                    .CapabilityOperation(
                        TrustedIntrinsicRegistry.PROCESS_CAPABILITY,
                        0u,
                        blocking = BlockingMode.VM_TASK,
                    ).takeIf {
                        callable.parameters == listOf(TrustedValueType.STRING, TrustedValueType.STRING) &&
                            callable.result == TrustedValueType.INT
                    }
            }

            "compukter.process.ProcessBindings.takeFailureDiagnostic" -> {
                sync(
                    1u,
                    callable,
                    emptyList(),
                    TrustedValueType.STRING,
                    TrustedIntrinsicRegistry.PROCESS_CAPABILITY,
                )
            }

            "compukter.process.ProcessBindings.exit" -> {
                TrustedIntrinsic
                    .CapabilityOperation(
                        TrustedIntrinsicRegistry.PROCESS_CAPABILITY,
                        2u,
                        BlockingMode.NONE,
                        terminal = true,
                    ).takeIf {
                        callable.parameters == listOf(TrustedValueType.INT) && callable.result == TrustedValueType.NOTHING
                    }
            }

            else -> {
                null
            }
        }
    }
}

private object TerminalIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (
            callable.origin != TrustedCallableOrigin.TRUSTED_SDK_SOURCE ||
            callable.bundleIdentity != TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID
        ) {
            return null
        }
        val intrinsic =
            when (callable.name) {
                "compukter.terminal.Terminal.write" -> {
                    sync(0u, callable, listOf(TrustedValueType.STRING), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.erasePrevious" -> {
                    sync(1u, callable, emptyList(), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.clear" -> {
                    sync(2u, callable, emptyList(), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.awaitEvent" -> {
                    vmBlocking(3u, callable, TrustedValueType.INT)
                }

                "compukter.terminal.Terminal.eventText" -> {
                    sync(4u, callable, emptyList(), TrustedValueType.STRING)
                }

                "compukter.terminal.Terminal.eventKey" -> {
                    sync(5u, callable, emptyList(), TrustedValueType.INT)
                }

                "compukter.terminal.Terminal.eventAction" -> {
                    sync(6u, callable, emptyList(), TrustedValueType.INT)
                }

                "compukter.terminal.Terminal.eventModifiers" -> {
                    sync(7u, callable, emptyList(), TrustedValueType.INT)
                }

                "compukter.terminal.Terminal.finishEvent" -> {
                    sync(8u, callable, emptyList(), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.setCursor" -> {
                    sync(9u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.setCursorVisible" -> {
                    sync(10u, callable, listOf(TrustedValueType.BOOL), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.setColors" -> {
                    sync(11u, callable, listOf(TrustedValueType.INT, TrustedValueType.INT), TrustedValueType.UNIT)
                }

                "compukter.terminal.Terminal.writeAt" -> {
                    sync(
                        12u,
                        callable,
                        listOf(TrustedValueType.INT, TrustedValueType.INT, TrustedValueType.STRING),
                        TrustedValueType.UNIT,
                    )
                }

                "compukter.terminal.Terminal.fill" -> {
                    sync(
                        13u,
                        callable,
                        listOf(
                            TrustedValueType.INT,
                            TrustedValueType.INT,
                            TrustedValueType.INT,
                            TrustedValueType.INT,
                            TrustedValueType.CHAR,
                        ),
                        TrustedValueType.UNIT,
                    )
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
        TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, operation, BlockingMode.NONE).takeIf {
            !callable.suspending && callable.parameters == parameters && callable.result == result
        }

    private fun vmBlocking(
        operation: UInt,
        callable: TrustedCallableIdentity,
        result: TrustedValueType,
    ): TrustedIntrinsic? =
        TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, operation, BlockingMode.VM_TASK).takeIf {
            !callable.suspending && callable.parameters.isEmpty() && callable.result == result
        }
}

private fun sync(
    operation: UInt,
    callable: TrustedCallableIdentity,
    parameters: List<TrustedValueType>,
    result: TrustedValueType,
    capability: TrustedCapabilityIdentity,
): TrustedIntrinsic.CapabilityOperation? =
    TrustedIntrinsic.CapabilityOperation(capability, operation, BlockingMode.NONE).takeIf {
        !callable.suspending && callable.parameters == parameters && callable.result == result
    }
