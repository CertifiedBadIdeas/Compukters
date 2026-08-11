/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.core.device.display.retained

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedDisplayArchitectureTest {
    private val root =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve("gradle/libs.versions.toml").toFile().exists() }

    @Test
    fun logicalReplicaIsMinecraftAndLegacyDisplayIndependent() {
        val source =
            root
                .resolve(
                    "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/display/retained/" +
                        "RetainedDisplayReplica.kt",
                ).readText()

        for (forbidden in
            listOf(
                "net.minecraft",
                "GuiGraphics",
                "PoseStack",
                "NativeDisplayFrameCodec",
                "DisplayFrameDelta",
                "DisplayFrameOperation",
                "K16ComputerEndpoint",
            )
        ) {
            assertFalse(source.contains(forbidden), "logical replica must not depend on $forbidden")
        }
        assertTrue(source.contains("class RetainedDisplayReplica"))
        assertTrue(source.contains("private class LeReader"))
    }

    @Test
    fun productionSourcesDoNotRetainFramebufferTransport() {
        val productionRoots =
            listOf(
                "modules/core/src/main",
                "modules/native-runtime/src/main",
                "modules/v1_21_1/v1_21_1-common/src/main",
                "modules/v1_21_1/v1_21_1-neoforge/src/main",
            ).map(root::resolve)
        val forbidden =
            listOf(
                "DisplayFrameDelta",
                "NativeDisplayFrameCodec",
                "drainGpu0Frames",
                "sendNativeDisplayFrameBytes",
                "ClientDisplayBuffer",
                "ClientDisplayProfiling",
            )

        for (productionRoot in productionRoots) {
            Files.walk(productionRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
                    .forEach { path ->
                        val source = path.readText()
                        for (legacyName in forbidden) {
                            assertFalse(source.contains(legacyName), "$path must not retain $legacyName")
                        }
                    }
            }
        }
        for (removed in
            listOf(
                "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/" +
                    "NativeDisplayFrameCodec.kt",
                "modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/" +
                    "DisplayModels.kt",
                "modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/" +
                    "client/ClientDisplayBuffer.kt",
            )
        ) {
            assertFalse(Files.exists(root.resolve(removed)), "$removed must stay deleted")
        }
    }
}
