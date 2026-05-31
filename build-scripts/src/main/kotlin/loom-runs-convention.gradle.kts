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

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.configuration.ide.RunConfigSettings
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

plugins {
    id("dev.architectury.loom")
}

// ---------------------------------------------------------------------------
// Centralised Loom run-configuration declarations.
//
// Goals:
//  * Keep `run/client`, `run/client2`, `run/server` at the same depth so the
//    layout is symmetric and easy to wipe selectively.
//  * Apply the same JVM properties (e.g. coroutine debug switch) to every
//    run without copy-pasting in each loader build script.
//  * Enable two parallel client instances out of the box for local
//    multiplayer testing without rebuilding/sideloading jars.
// ---------------------------------------------------------------------------

val sharedRunProperties: Map<String, String> =
    mapOf(
        // Coroutine debug agent doubles allocation and slows ticks; off by
        // default for dev runs. Override per-run with `-D...=on` if needed.
        "kotlinx.coroutines.debug" to "off",
    )

fun RunConfigSettings.applyShared() {
    sharedRunProperties.forEach { (k, v) -> property(k, v) }
    ideConfigGenerated(true)
}

private val DEV_CLIENT_USERNAMES = listOf("DevA", "DevB", "DevC")

private val k16VmCrateDir = rootProject.layout.projectDirectory.dir("native/k16-vm")
private val k16VmNativePlatform = currentK16VmNativePlatform()
private val k16VmNativeLibrary = k16VmCrateDir.file("target/debug/${k16VmNativePlatform.libraryName}")
private val k16VmReleaseNativeLibrary = k16VmCrateDir.file("target/release/${k16VmNativePlatform.libraryName}")
private val k16VmWindowsX64Target = "x86_64-pc-windows-gnu"
private val k16VmWindowsX64NativeLibrary = k16VmCrateDir.file("target/$k16VmWindowsX64Target/release/k16_vm.dll")
private val k16VmNativeDistDir = k16VmCrateDir.dir("dist/natives")
private val productionK16VmNativeResources = layout.buildDirectory.dir("generated/production-k16-vm-native-resources")
private val isProductionUniversalJarRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName == "buildProductionUniversalJar" || taskName.endsWith(":buildProductionUniversalJar")
    }

val buildK16VmNativeLibrary =
    tasks.register<Exec>("buildK16VmNativeLibrary") {
        group = "loom"
        description = "Build the local K16 VM JNI library used by K16 VM dev run configurations."
        workingDir = k16VmCrateDir.asFile
        commandLine("cargo", "build")
        inputs.file(k16VmCrateDir.file("Cargo.toml"))
        inputs.file(k16VmCrateDir.file("Cargo.lock"))
        inputs.dir(k16VmCrateDir.dir("src"))
        outputs.file(k16VmNativeLibrary)
    }

val buildK16VmNativeLibraryRelease =
    tasks.register<Exec>("buildK16VmNativeLibraryRelease") {
        group = "build"
        description = "Build the release K16 VM JNI library for bundling into production mod jars."
        workingDir = k16VmCrateDir.asFile
        commandLine("cargo", "build", "--release")
        inputs.file(k16VmCrateDir.file("Cargo.toml"))
        inputs.file(k16VmCrateDir.file("Cargo.lock"))
        inputs.dir(k16VmCrateDir.dir("src"))
        outputs.file(k16VmReleaseNativeLibrary)
    }

val buildK16VmWindowsX64NativeLibraryRelease =
    tasks.register<Exec>("buildK16VmWindowsX64NativeLibraryRelease") {
        group = "build"
        description = "Cross-build the release K16 VM JNI library for Windows x64 production jars."
        workingDir = k16VmCrateDir.asFile
        commandLine("cargo", "build", "--release", "--target", k16VmWindowsX64Target)
        environment("CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER", "x86_64-w64-mingw32-gcc")
        inputs.file(k16VmCrateDir.file("Cargo.toml"))
        inputs.file(k16VmCrateDir.file("Cargo.lock"))
        inputs.dir(k16VmCrateDir.dir("src"))
        outputs.file(k16VmWindowsX64NativeLibrary)
        doFirst {
            require(commandAvailable("x86_64-w64-mingw32-gcc")) {
                "Missing x86_64-w64-mingw32-gcc. Install MinGW-w64 and run " +
                    "`rustup target add $k16VmWindowsX64Target` before building a production universal jar."
            }
        }
    }

val prepareBundledK16VmNativeLibraries =
    tasks.register<Sync>("prepareBundledK16VmNativeLibraries") {
        group = "build"
        description = "Stage K16 VM native libraries under natives/<os-arch>/ for universal mod jars."
        dependsOn(buildK16VmNativeLibraryRelease)

        from(k16VmReleaseNativeLibrary) {
            into("natives/${k16VmNativePlatform.id}")
            rename { k16VmNativePlatform.libraryName }
        }
        from(k16VmNativeDistDir) {
            include("**/*")
        }
        into(layout.buildDirectory.dir("generated/k16-vm-native-resources"))
    }

val stageProductionK16VmNativeLibraries =
    tasks.register<Sync>("stageProductionK16VmNativeLibraries") {
        group = "build"
        description = "Stage current-platform and Windows x64 K16 VM natives for production universal jars."
        dependsOn(buildK16VmNativeLibraryRelease)
        if (k16VmNativePlatform.id != "windows-x86_64") {
            dependsOn(buildK16VmWindowsX64NativeLibraryRelease)
        }

        from(k16VmReleaseNativeLibrary) {
            into("natives/${k16VmNativePlatform.id}")
            rename { k16VmNativePlatform.libraryName }
        }
        from(if (k16VmNativePlatform.id == "windows-x86_64") k16VmReleaseNativeLibrary else k16VmWindowsX64NativeLibrary) {
            into("natives/windows-x86_64")
            rename { "k16_vm.dll" }
        }
        from(k16VmNativeDistDir) {
            include("**/*")
        }
        into(productionK16VmNativeResources)
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareBundledK16VmNativeLibraries)
    from(prepareBundledK16VmNativeLibraries)
    if (isProductionUniversalJarRequested) {
        dependsOn(stageProductionK16VmNativeLibraries)
        from(productionK16VmNativeResources)
    }
}

tasks.register("buildProductionUniversalJar") {
    group = "build"
    description = "Build a production mod jar with current-platform and Windows x64 K16 VM natives bundled."
    dependsOn(stageProductionK16VmNativeLibraries)
    dependsOn(tasks.named("remapJar"))
}

fun RunConfigSettings.applyK16Vm() {
    property("k16.vm.native.display", "true")
    property("k16.vm.native.daemon", "true")
}

val loom = extensions.getByType<LoomGradleExtensionAPI>()
val runs = loom.runs

// Primary client — auto-registered by Loom; we just normalise it.
runs.named("client") {
    runDir("run/client")
    applyShared()
    applyK16Vm()
    programArgs("--username", DEV_CLIENT_USERNAMES[0])
}

// Second client instance for local multiplayer testing of the
// workbench's CRDT sync. Reuses the main mod classpath, so any
// change picked up by `runClient` is also picked up here on
// restart — no jar build / sideload needed.
runs.register("client2") {
    client()
    configName = "Minecraft Client 2"
    runDir("run/client2")
    applyShared()
    applyK16Vm()
    programArgs("--username", DEV_CLIENT_USERNAMES[1])
}

// Second client instance for local multiplayer testing of the
// workbench's CRDT sync. Reuses the main mod classpath, so any
// change picked up by `runClient` is also picked up here on
// restart — no jar build / sideload needed.
runs.register("client3") {
    client()
    configName = "Minecraft Client 3"
    runDir("run/client3")
    applyShared()
    applyK16Vm()
    programArgs("--username", DEV_CLIENT_USERNAMES[2])
}

// One-shot dev server: same as `server` but with a separate run dir whose
// `eula.txt` and `server.properties` are pre-seeded by `prepareServerDev`,
// so it boots in a single command without manual EULA / world setup. Use
// for collaborative CRDT testing where two `runClient*` instances connect
// to `localhost:25565`.
runs.named("server") {
    runDir("run/server")
    applyShared()
    applyK16Vm()
}

private val DEV_SERVER_SEED = "compukterkraft"
private val DEV_SERVER_PROPERTIES =
    mapOf(
        "online-mode" to "false",
        "level-seed" to DEV_SERVER_SEED,
        "level-name" to "world",
        "motd" to "Compukter Kraft dev server",
        "max-players" to "8",
        "spawn-protection" to "0",
        "enable-command-block" to "true",
        "sync-chunk-writes" to "false",
        // Creative mode by default + force it on every join so dev clients
        // always land in /gamemode creative regardless of their own state.
        "gamemode" to "creative",
        "force-gamemode" to "true",
        "allow-flight" to "true",
        "difficulty" to "peaceful",
        "op-permission-level" to "4",
    )

val prepareServerDev =
    tasks.register("prepareServerDev") {
        val runDirs =
            listOf(layout.projectDirectory.dir("run/server"))
        runDirs.forEach { runDir ->
            outputs.file(runDir.file("eula.txt"))
            outputs.file(runDir.file("server.properties"))
            outputs.file(runDir.file("ops.json"))
        }
        doLast {
            runDirs.forEach { runDir ->
                val dir = runDir.asFile
                dir.mkdirs()
                dir.resolve("eula.txt").writeText(
                    "# Auto-generated by prepareServerDev. Removing this file or flipping\n" +
                        "# the flag to false will simply force a regenerate on next run.\n" +
                        "eula=true\n",
                )
                val propsFile = dir.resolve("server.properties")
                // Preserve manual edits: only write properties that are not yet present.
                val existing =
                    if (propsFile.exists()) {
                        propsFile
                            .readLines()
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .associate {
                                val eq = it.indexOf('=')
                                if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
                            }.toMutableMap()
                    } else {
                        mutableMapOf()
                    }
                DEV_SERVER_PROPERTIES.forEach { (k, v) -> existing.putIfAbsent(k, v) }
                propsFile.writeText(
                    buildString {
                        appendLine("# Auto-managed by prepareServerDev — entries are only added, not overwritten.")
                        existing.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
                    },
                )
                // Op every dev username so all clients have full command access
                // out of the box. UUIDs are derived the same way the vanilla
                // server does for offline-mode players: UUIDv3 over the bytes
                // "OfflinePlayer:<name>".
                seedOpsJson(dir.resolve("ops.json"))
            }
        }
    }

tasks.matching { it.name == "runServer" }.configureEach {
    dependsOn(prepareServerDev, buildK16VmNativeLibrary)
}

// ---------------------------------------------------------------------------
// Client run dirs: pre-seed `options.txt` (vsync off, low master volume,
// music silenced) and a `servers.dat` containing the local dev server entry.
// ---------------------------------------------------------------------------

private val DEV_CLIENT_OPTIONS =
    linkedMapOf(
        "enableVsync" to "false",
        // GUI scale 3 = ~3x default; matches the user's preferred zoom.
        "guiScale" to "3",
        "soundCategory_master" to "0.2",
        "soundCategory_music" to "0.0",
        "soundCategory_record" to "0.0",
        "soundCategory_weather" to "0.4",
        "soundCategory_block" to "0.6",
        "soundCategory_hostile" to "0.6",
        "soundCategory_neutral" to "0.6",
        "soundCategory_player" to "0.6",
        "soundCategory_ambient" to "0.4",
        "soundCategory_voice" to "0.6",
        // Keep the title screen calm and the chat window unobtrusive.
        "showSubtitles" to "true",
        "pauseOnLostFocus" to "false",
    )

private data class DevServerEntry(
    val name: String,
    val ip: String,
)

private val DEV_CLIENT_SERVERS =
    listOf(
        DevServerEntry(name = "Compukter Kraft (dev)", ip = "localhost"),
    )

private val CLIENT_RUN_DIRS =
    listOf(
        "run/client",
        "run/client2",
        "run/client3",
    )

val prepareClientDev =
    tasks.register("prepareClientDev") {
        group = "loom"
        description = "Seed dev client run dirs with sane options.txt and servers.dat (vsync off, music silenced, dev server pre-added)."
        CLIENT_RUN_DIRS.forEach { rel ->
            val dir = layout.projectDirectory.dir(rel)
            outputs.file(dir.file("options.txt"))
            outputs.file(dir.file("servers.dat"))
        }
        doLast {
            CLIENT_RUN_DIRS.forEach { rel ->
                val dir = layout.projectDirectory.dir(rel).asFile
                dir.mkdirs()
                seedOptionsTxt(dir.resolve("options.txt"))
                seedServersDat(dir.resolve("servers.dat"))
            }
        }
    }

private val CLIENT_RUN_TASKS =
    setOf(
        "runClient",
        "runClient2",
        "runClient3",
    )

tasks.matching { it.name in CLIENT_RUN_TASKS }.configureEach {
    dependsOn(prepareClientDev, buildK16VmNativeLibrary)
}

private fun seedOptionsTxt(file: File) {
    // options.txt uses `key:value` (one per line). Preserve manual edits:
    // only add keys that are not yet present.
    val existing: MutableMap<String, String> =
        if (file.exists()) {
            file
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val sep = line.indexOf(':')
                    if (sep < 0) null else line.substring(0, sep) to line.substring(sep + 1)
                }.toMap()
                .toMutableMap()
        } else {
            mutableMapOf()
        }
    DEV_CLIENT_OPTIONS.forEach { (k, v) -> existing.putIfAbsent(k, v) }
    file.writeText(
        buildString {
            existing.toSortedMap().forEach { (k, v) -> appendLine("$k:$v") }
        },
    )
}

private fun seedServersDat(file: File) {
    // `servers.dat` is uncompressed NBT. Only seed when missing — if the user
    // already curated a server list we don't want to clobber it. Delete the
    // file and rerun `prepareClientDev` to reseed.
    if (file.exists()) return
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use { out ->
        // Root TAG_Compound (no name).
        out.writeByte(NBT_COMPOUND)
        out.writeUTF("")
        // "servers": TAG_List<TAG_Compound>
        out.writeByte(NBT_LIST)
        out.writeUTF("servers")
        out.writeByte(NBT_COMPOUND)
        out.writeInt(DEV_CLIENT_SERVERS.size)
        DEV_CLIENT_SERVERS.forEach { entry ->
            // Each list element: a TAG_Compound (no name) with "name" and "ip"
            // string entries, terminated by TAG_End.
            out.writeNbtString("name", entry.name)
            out.writeNbtString("ip", entry.ip)
            out.writeByte(NBT_END)
        }
        // Close the root compound.
        out.writeByte(NBT_END)
    }
    file.writeBytes(baos.toByteArray())
}

private val NBT_END: Int = 0
private val NBT_STRING: Int = 8
private val NBT_LIST: Int = 9
private val NBT_COMPOUND: Int = 10

private fun DataOutputStream.writeNbtString(
    name: String,
    value: String,
) {
    writeByte(NBT_STRING)
    writeUTF(name)
    writeUTF(value)
}

private fun seedOpsJson(file: File) {
    // Generate UUIDs the same way Mojang's offline-mode flow does:
    // UUIDv3 derived from the bytes "OfflinePlayer:<name>".
    val entries =
        DEV_CLIENT_USERNAMES.joinToString(",\n") { name ->
            val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            """  {
    "uuid": "$uuid",
    "name": "$name",
    "level": 4,
    "bypassesPlayerLimit": false
  }"""
        }
    file.writeText("[\n$entries\n]\n")
}

private fun commandAvailable(command: String): Boolean =
    runCatching {
        ProcessBuilder(command, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)
