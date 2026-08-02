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
    fun serverRuntimeForwardsRetainedBytesWithoutOwningAReplica() {
        val runtime =
            root
                .resolve(
                    "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/" +
                        "K16RuntimeDevice.kt",
                ).readText()
        val bridge =
            root
                .resolve(
                    "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/" +
                        "DisplayNetworkBridge.kt",
                ).readText()

        for (forbidden in
            listOf(
                "RetainedDisplayReplica",
                "RetainedDisplayResource",
                "RetainedDrawList",
                "RetainedDisplayProtocol",
                "NETWORK_MAGIC",
                "\"KDSP\"",
            )
        ) {
            assertFalse(runtime.contains(forbidden), "server runtime must not own or decode $forbidden")
            assertFalse(bridge.contains(forbidden), "server bridge must not own or decode $forbidden")
        }
        assertTrue(runtime.contains("sendRetainedDisplayPayload"))
        assertTrue(runtime.contains("pollRetainedDisplayPayload"))
        assertFalse(runtime.contains("Command.DrainRetainedDisplayPayload"))
        assertFalse(runtime.contains("retainedDisplaySessions.sessionsSnapshot()"))
        assertTrue(bridge.contains("fun sendRetainedDisplayPayload"))
    }
}
