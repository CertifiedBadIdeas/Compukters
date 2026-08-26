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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustedIntrinsicRegistryTest {
    @Test
    fun `stdio provider pins ordinary Kotlin console calls and stderr to exact signatures`() {
        fun resolve(
            bundle: String,
            name: String,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
            origin: TrustedCallableOrigin =
                if (bundle.startsWith("kotlin-stdlib@")) {
                    TrustedCallableOrigin.PINNED_KOTLIN_STDLIB
                } else {
                    TrustedCallableOrigin.TRUSTED_SDK_SOURCE
                },
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(bundle, name, false, parameters, result, origin),
        )

        val stdio = TrustedCapabilityIdentity("compukter", "stdio", 1u.toUShort(), 0u.toUShort(), 3u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(stdio, 0u, BlockingMode.VM_TASK),
            resolve("kotlin-stdlib@2.4.10", "kotlin.io.readln", emptyList(), TrustedValueType.STRING),
        )
        listOf(TrustedValueType.OTHER, TrustedValueType.INT, TrustedValueType.BOOL, TrustedValueType.CHAR).forEach { type ->
            assertEquals(
                TrustedIntrinsic.StandardOutput(newline = false, declaredType = type),
                resolve("kotlin-stdlib@2.4.10", "kotlin.io.print", listOf(type), TrustedValueType.UNIT),
            )
            assertEquals(
                TrustedIntrinsic.StandardOutput(newline = true, declaredType = type),
                resolve("kotlin-stdlib@2.4.10", "kotlin.io.println", listOf(type), TrustedValueType.UNIT),
            )
        }
        assertEquals(
            TrustedIntrinsic.StandardOutput(newline = true, declaredType = null),
            resolve("kotlin-stdlib@2.4.10", "kotlin.io.println", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(stdio, 1u, BlockingMode.NONE),
            resolve(
                "compukter.stdio-api@1",
                "compukter.io.StdioBindings.write",
                listOf(TrustedValueType.STRING),
                TrustedValueType.UNIT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(stdio, 2u, BlockingMode.NONE),
            resolve("compukter.stdio-api@1", "compukter.io.Stderr.write", listOf(TrustedValueType.STRING), TrustedValueType.UNIT),
        )
        assertNull(resolve("kotlin-stdlib@2.4.9", "kotlin.io.readln", emptyList(), TrustedValueType.STRING))
        assertNull(resolve("kotlin-stdlib@2.4.10", "kotlin.io.print", listOf(TrustedValueType.STRING), TrustedValueType.UNIT))
        assertNull(resolve("compukter.stdio-api@1", "compukter.io.Stderr.write", listOf(TrustedValueType.INT), TrustedValueType.UNIT))
        assertNull(
            resolve(
                "kotlin-stdlib@2.4.10",
                "kotlin.io.readln",
                emptyList(),
                TrustedValueType.STRING,
                TrustedCallableOrigin.PLAYER_SOURCE,
            ),
        )
    }

    @Test
    fun `stdio integer formatter covers the full signed range`() {
        val method =
            Class
                .forName("compukter.io.StderrKt")
                .getDeclaredMethod("stdoutInt", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }

        listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE).forEach { value ->
            assertEquals(value.toString(), method.invoke(null, value))
        }
    }

    @Test
    fun `process v2 encoder preserves exact length delimited utf16 arguments`() {
        val method =
            Class
                .forName("compukter.process.ProcessKt")
                .getDeclaredMethod("encodeArgs", Array<String>::class.java)
                .also { it.isAccessible = true }
        val encoded = method.invoke(null, arrayOf("a\u0000😀", "")) as String

        assertContentEquals(
            charArrayOf(
                '\u0002',
                '\u0000',
                '\u0004',
                '\u0000',
                'a',
                '\u0000',
                '\uD83D',
                '\uDE00',
                '\u0000',
                '\u0000',
            ),
            encoded.toCharArray(),
        )
    }

    @Test
    fun `compiler provider requires trusted bundle and exact vm-blocking and sync signatures`() {
        fun resolve(
            name: String,
            suspending: Boolean,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
            bundle: String? = "compukter.compiler-api@1",
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(bundle, name, suspending, parameters, result),
        )

        val compiler = TrustedCapabilityIdentity("compukter", "compiler", 1u.toUShort(), 0u.toUShort(), 2u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(compiler, 0u, BlockingMode.VM_TASK),
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(compiler, 1u, BlockingMode.NONE),
            resolve(
                "compukter.compiler.Compiler.diagnostics",
                suspending = false,
                parameters = emptyList(),
                result = TrustedValueType.STRING,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
                bundle = null,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Other.compile",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.diagnostics",
                suspending = true,
                parameters = emptyList(),
                result = TrustedValueType.STRING,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
    }

    @Test
    fun `filesystem provider requires trusted facade and exact synchronous signatures`() {
        fun resolve(
            name: String,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
            bundle: String? = "compukter.filesystem-api@1",
            suspending: Boolean = false,
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(bundle, name, suspending, parameters, result),
        )

        val filesystem = TrustedCapabilityIdentity("compukter", "filesystem", 1u.toUShort(), 0u.toUShort(), 7u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 0u, BlockingMode.NONE),
            resolve("compukter.filesystem.FileSystem.stat", listOf(TrustedValueType.STRING), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 1u, BlockingMode.NONE),
            resolve("compukter.filesystem.FileSystem.list", listOf(TrustedValueType.STRING), TrustedValueType.STRING),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 2u, BlockingMode.NONE),
            resolve("compukter.filesystem.FileSystem.readText", listOf(TrustedValueType.STRING), TrustedValueType.STRING),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 3u, BlockingMode.NONE),
            resolve(
                "compukter.filesystem.FileSystem.writeText",
                listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
                bundle = null,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.Other.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
                suspending = true,
            ),
        )
        assertNull(
            resolve("compukter.filesystem.FileSystem.list", emptyList(), TrustedValueType.STRING),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.writeText",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.readText",
                listOf(TrustedValueType.STRING),
                TrustedValueType.STRING,
                bundle = null,
            ),
        )
    }

    @Test
    fun `process v2 provider exposes only exact private binding signatures`() {
        val trustedRun =
            TrustedCallableIdentity(
                bundleIdentity = TrustedIntrinsicRegistry.PROCESS_BUNDLE_ID,
                name = "compukter.process.ProcessBindings.run",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            )

        val process = TrustedCapabilityIdentity("compukter", "process", 2u.toUShort(), 0u.toUShort(), 3u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(
                capability = process,
                operation = 0u,
                blocking = BlockingMode.VM_TASK,
            ),
            TrustedIntrinsicRegistry.resolve(trustedRun),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(process, 1u, BlockingMode.NONE),
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(
                    name = "compukter.process.ProcessBindings.takeFailureDiagnostic",
                    parameters = emptyList(),
                    result = TrustedValueType.STRING,
                ),
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(process, 2u, BlockingMode.NONE, terminal = true),
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(
                    name = "compukter.process.ProcessBindings.exit",
                    parameters = listOf(TrustedValueType.INT),
                    result = TrustedValueType.NOTHING,
                ),
            ),
        )
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(bundleIdentity = null)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(suspending = true)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(parameters = listOf(TrustedValueType.STRING))))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(result = TrustedValueType.UNIT)))
        assertNull(
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(
                    name = "compukter.process.Process.run",
                ),
            ),
        )
    }

    @Test
    fun `guest declaration cannot spoof a terminal intrinsic by name and signature`() {
        val guestWrite =
            TrustedCallableIdentity(
                bundleIdentity = null,
                name = "compukter.terminal.Terminal.write",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING),
                result = TrustedValueType.UNIT,
            )

        assertNull(TrustedIntrinsicRegistry.resolve(guestWrite))
    }

    @Test
    fun `terminal provider requires its trusted bundle and exact signatures`() {
        val trustedAwait =
            TrustedCallableIdentity(
                bundleIdentity = TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                name = "compukter.terminal.Terminal.awaitEvent",
                suspending = false,
                parameters = emptyList(),
                result = TrustedValueType.INT,
            )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(
                capability = TrustedIntrinsicRegistry.TERMINAL_CAPABILITY,
                operation = 3u,
                blocking = BlockingMode.VM_TASK,
            ),
            TrustedIntrinsicRegistry.resolve(trustedAwait),
        )
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(bundleIdentity = null)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(suspending = true)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(parameters = listOf(TrustedValueType.STRING))))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(result = TrustedValueType.UNIT)))
    }

    @Test
    fun `terminal provider exposes raw synchronous operations`() {
        fun resolve(
            name: String,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(
                TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                name,
                false,
                parameters,
                result,
            ),
        )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 0u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.write", listOf(TrustedValueType.STRING), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 1u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.erasePrevious", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 2u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.clear", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 4u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.eventText", emptyList(), TrustedValueType.STRING),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 5u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.eventKey", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 6u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.eventAction", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 7u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.eventModifiers", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 8u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.finishEvent", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 9u, BlockingMode.NONE),
            resolve(
                "compukter.terminal.Terminal.setCursor",
                listOf(TrustedValueType.INT, TrustedValueType.INT),
                TrustedValueType.UNIT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 10u, BlockingMode.NONE),
            resolve("compukter.terminal.Terminal.setCursorVisible", listOf(TrustedValueType.BOOL), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 11u, BlockingMode.NONE),
            resolve(
                "compukter.terminal.Terminal.setColors",
                listOf(TrustedValueType.INT, TrustedValueType.INT),
                TrustedValueType.UNIT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 12u, BlockingMode.NONE),
            resolve(
                "compukter.terminal.Terminal.writeAt",
                listOf(TrustedValueType.INT, TrustedValueType.INT, TrustedValueType.STRING),
                TrustedValueType.UNIT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 13u, BlockingMode.NONE),
            resolve(
                "compukter.terminal.Terminal.fill",
                listOf(
                    TrustedValueType.INT,
                    TrustedValueType.INT,
                    TrustedValueType.INT,
                    TrustedValueType.INT,
                    TrustedValueType.CHAR,
                ),
                TrustedValueType.UNIT,
            ),
        )
    }
}
