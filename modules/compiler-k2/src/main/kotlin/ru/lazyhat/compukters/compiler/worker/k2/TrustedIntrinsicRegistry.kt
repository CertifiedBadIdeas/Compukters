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
    OTHER,
}

internal data class TrustedCallableIdentity(
    val bundleIdentity: String?,
    val name: String,
    val suspending: Boolean,
    val parameters: List<TrustedValueType>,
    val result: TrustedValueType,
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

internal sealed interface TrustedIntrinsic {
    data class CapabilityOperation(
        val capability: TrustedCapabilityIdentity,
        val operation: UInt,
        val asynchronous: Boolean,
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
    const val TERMINAL_BUNDLE_ID = "compukter.terminal-api@1"
    const val PROCESS_BUNDLE_ID = "compukter.process-api@1"
    const val FILESYSTEM_BUNDLE_ID = "compukter.filesystem-api@1"
    const val COMPILER_BUNDLE_ID = "compukter.compiler-api@1"
    val CORE_SOURCE_BUNDLES =
        listOf(
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
        )
    val TERMINAL_CAPABILITY = TrustedCapabilityIdentity("compukter", "terminal", 2u.toUShort(), 0u.toUShort(), 14u)
    val PROCESS_CAPABILITY = TrustedCapabilityIdentity("compukter", "process", 1u.toUShort(), 1u.toUShort(), 3u)
    val FILESYSTEM_CAPABILITY = TrustedCapabilityIdentity("compukter", "filesystem", 1u.toUShort(), 0u.toUShort(), 7u)
    val COMPILER_CAPABILITY = TrustedCapabilityIdentity("compukter", "compiler", 1u.toUShort(), 0u.toUShort(), 2u)

    private val providers: List<TrustedIntrinsicProvider> =
        listOf(CompilerIntrinsicProvider, FilesystemIntrinsicProvider, ProcessIntrinsicProvider, TerminalIntrinsicProvider)

    fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? =
        providers.firstNotNullOfOrNull { provider -> provider.resolve(callable) }
}

private object CompilerIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.COMPILER_BUNDLE_ID) return null
        return when (callable.name) {
            "compukter.compiler.Compiler.compile" -> {
                TrustedIntrinsic
                    .CapabilityOperation(
                        TrustedIntrinsicRegistry.COMPILER_CAPABILITY,
                        0u,
                        asynchronous = true,
                    ).takeIf {
                        callable.suspending &&
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
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.FILESYSTEM_BUNDLE_ID || callable.suspending) return null
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
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.PROCESS_BUNDLE_ID) return null
        return when (callable.name) {
            "compukter.process.Process.run" -> {
                val operation =
                    when (callable.parameters) {
                        listOf(TrustedValueType.STRING, TrustedValueType.INT) -> 0u
                        listOf(TrustedValueType.STRING, TrustedValueType.INT, TrustedValueType.STRING) -> 1u
                        else -> return null
                    }
                TrustedIntrinsic
                    .CapabilityOperation(
                        TrustedIntrinsicRegistry.PROCESS_CAPABILITY,
                        operation,
                        asynchronous = true,
                    ).takeIf { callable.suspending && callable.result == TrustedValueType.INT }
            }

            "compukter.process.Process.commandLine" -> {
                sync(
                    2u,
                    callable,
                    emptyList(),
                    TrustedValueType.STRING,
                    TrustedIntrinsicRegistry.PROCESS_CAPABILITY,
                )
            }

            else -> {
                null
            }
        }
    }
}

private object TerminalIntrinsicProvider : TrustedIntrinsicProvider {
    override fun resolve(callable: TrustedCallableIdentity): TrustedIntrinsic? {
        if (callable.bundleIdentity != TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID) return null
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
                    async(3u, callable, TrustedValueType.INT)
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
        TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, operation, asynchronous = false).takeIf {
            !callable.suspending && callable.parameters == parameters && callable.result == result
        }

    private fun async(
        operation: UInt,
        callable: TrustedCallableIdentity,
        result: TrustedValueType,
    ): TrustedIntrinsic? =
        TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, operation, asynchronous = true).takeIf {
            callable.suspending && callable.parameters.isEmpty() && callable.result == result
        }
}

private fun sync(
    operation: UInt,
    callable: TrustedCallableIdentity,
    parameters: List<TrustedValueType>,
    result: TrustedValueType,
    capability: TrustedCapabilityIdentity,
): TrustedIntrinsic.CapabilityOperation? =
    TrustedIntrinsic.CapabilityOperation(capability, operation, asynchronous = false).takeIf {
        !callable.suspending && callable.parameters == parameters && callable.result == result
    }
