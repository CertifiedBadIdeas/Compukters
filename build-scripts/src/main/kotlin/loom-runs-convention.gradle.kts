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

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.configuration.ide.RunConfigSettings
import java.util.UUID

// ---------------------------------------------------------------------------
// Centralised Loom run-configuration declarations.
//
// Goals:
//  * Keep `run/client`, `run/client2`, `run/server` at the same depth so the
//    layout is symmetric and easy to wipe selectively.
//  * Enable two parallel client instances out of the box for local
//    multiplayer testing without rebuilding/sideloading jars.
// ---------------------------------------------------------------------------

fun RunConfigSettings.applyShared() {
    ideConfigGenerated(true)
    if (buildContext().versionKey == "v261") {
        vmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    }
}

private val DEV_CLIENT_USERNAMES = listOf("DevA", "DevB", "DevC")

val loom = extensions.getByType<LoomGradleExtensionAPI>()
val runs = loom.runs

// Primary client — auto-registered by Loom; we just normalise it.
runs.named("client") {
    runDir("run/client")
    applyShared()
    programArgs("--username", DEV_CLIENT_USERNAMES[0])
}

// Second client instance for local multiplayer testing. Reuses the main mod classpath, so any
// change picked up by `runClient` is also picked up here on
// restart — no jar build / sideload needed.
runs.register("client2") {
    client()
    configName = "Minecraft Client 2"
    runDir("run/client2")
    applyShared()
    programArgs("--username", DEV_CLIENT_USERNAMES[1])
}

// Third client instance for local multiplayer testing. Reuses the main mod classpath, so any
// change picked up by `runClient` is also picked up here on
// restart — no jar build / sideload needed.
runs.register("client3") {
    client()
    configName = "Minecraft Client 3"
    runDir("run/client3")
    applyShared()
    programArgs("--username", DEV_CLIENT_USERNAMES[2])
}

// One-shot dev server: same as `server` but with a separate run dir whose
// `eula.txt` and `server.properties` are pre-seeded by `prepareServerDev`,
// so it boots in a single command without manual EULA / world setup. Use
// for local multiplayer testing where `runClient*` instances connect
// to `localhost:25565`.
runs.named("server") {
    runDir("run/server")
    applyShared()
}

val prepareServerDev =
    tasks.register<PrepareServerDev>("prepareServerDev") {
        description = "Seeds the development server run directory with its EULA, properties, and operator list."
        runDirectories.set(listOf(layout.projectDirectory.dir("run/server")))
    }

tasks.matching { it.name == "runServer" }.configureEach {
    dependsOn(prepareServerDev)
}

// ---------------------------------------------------------------------------
// Client run dirs: pre-seed `options.txt` (vsync off, low master volume,
// music silenced) and a `servers.dat` containing the local dev server entry.
// ---------------------------------------------------------------------------

private val CLIENT_RUN_DIRS =
    buildList {
        add("run/client")
        add("run/client2")
        add("run/client3")
    }

val prepareClientDev =
    tasks.register<PrepareClientDev>("prepareClientDev") {
        group = "loom"
        description = "Seed dev client run dirs with sane options.txt and servers.dat (vsync off, music silenced, dev server pre-added)."
        runDirectories.set(CLIENT_RUN_DIRS.map { layout.projectDirectory.dir(it) })
    }

private val CLIENT_RUN_TASKS =
    buildSet {
        add("runClient")
        add("runClient2")
        add("runClient3")
    }

tasks.matching { it.name in CLIENT_RUN_TASKS }.configureEach {
    dependsOn(prepareClientDev)
}
