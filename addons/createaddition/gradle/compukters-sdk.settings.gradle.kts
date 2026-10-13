/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * SPDX-License-Identifier: Apache-2.0
 */

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

fun sha256(path: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun download(url: String, path: File, maximum: Long) {
    val connection = java.net.URI(url).toURL().openConnection().apply {
        connectTimeout = 30000
        readTimeout = 30000
    }
    connection.getInputStream().use { input ->
        path.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maximum) { "SDK download exceeds size limit" }
                output.write(buffer, 0, count)
            }
        }
    }
}

val mode = providers.gradleProperty("compuktersDependencyMode").get()
check(mode in setOf("workspace", "release")) { "Select compuktersDependencyMode=workspace or release" }
val workspace = if (mode == "workspace") {
    settings.settingsDir.resolve(providers.gradleProperty("compuktersWorkspace").get()).canonicalFile
} else null
val explicitSdk = providers.gradleProperty("compuktersSdkDirectory").orNull?.let { settings.settingsDir.resolve(it).canonicalFile }
check(workspace == null || explicitSdk == null) { "Select either a workspace or an SDK directory" }
val sdkDirectory = when {
    workspace != null -> workspace.resolve("build/addon-sdk")
    explicitSdk != null -> explicitSdk
    else -> {
        val version = providers.gradleProperty("compuktersRelease").get()
        check(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(version))
        val filename = "compukters-addon-development-$version.zip"
        val base = "https://github.com/CertifiedBadIdeas/Compukters/releases/download/v$version"
        val versionCache = gradle.gradleUserHomeDir.resolve("caches/compukters/addon-sdk/$version")
        versionCache.mkdirs()
        // A file lock also protects simultaneous Gradle imports of the same SDK.
        java.io.RandomAccessFile(versionCache.resolve("download.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                val archive = versionCache.resolve(filename)
                val checksum = versionCache.resolve("checksums.sha256")
                if (!checksum.isFile) {
                    check(!gradle.startParameter.isOffline) { "SDK checksum is not cached; run once without --offline" }
                    val pending = versionCache.resolve("checksums.pending")
                    download("$base/checksums.sha256", pending, 1024 * 1024)
                    Files.move(pending.toPath(), checksum.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                val expected = checksum.readLines().map { it.split(Regex("\\s+"), limit = 2) }
                    .single { it.size == 2 && it[1] == filename }[0]
                check(Regex("[0-9a-f]{64}").matches(expected)) { "Invalid SDK checksum" }
                val pinned = providers.gradleProperty("compuktersSdkSha256").orNull
                check(pinned == null || pinned == expected) { "SDK checksum differs from pinned value" }
                if (!archive.isFile) {
                    check(!gradle.startParameter.isOffline) { "SDK is not cached; run once without --offline" }
                    val pending = versionCache.resolve("sdk.pending")
                    download("$base/$filename", pending, 256L * 1024 * 1024)
                    check(sha256(pending) == expected) { "SDK download checksum differs" }
                    Files.move(pending.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                check(sha256(archive) == expected) { "Cached SDK checksum differs" }
                val unpacked = versionCache.resolve(expected)
                if (!unpacked.resolve("sdk.properties").isFile) {
                    val pending = versionCache.resolve("unpack.pending")
                    pending.deleteRecursively()
                    pending.mkdirs()
                    ZipInputStream(archive.inputStream()).use { zip ->
                        val names = mutableSetOf<String>()
                        var total = 0L
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val target = pending.resolve(entry.name).toPath().normalize()
                            check(!entry.name.contains('\\') && !entry.name.contains(':') &&
                                target.startsWith(pending.toPath()) && names.add(target.toString())) { "Unsafe SDK archive entry" }
                            if (entry.isDirectory) {
                                Files.createDirectories(target)
                            } else {
                                Files.createDirectories(target.parent)
                                Files.newOutputStream(target).use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= 768L * 1024 * 1024) { "SDK unpacked size exceeds limit" }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                    check(!unpacked.exists()) { "Incomplete SDK cache at $unpacked; remove it and retry" }
                    Files.move(pending.toPath(), unpacked.toPath())
                }
                unpacked
            }
        }
    }
}
check(sdkDirectory.resolve("sdk.properties").isFile) {
    "SDK missing in $sdkDirectory. For local development, run stageAddonDevelopmentDependencies in Compukters first. " +
        "The downloadable SDK requires a tagged release containing the addon development archive."
}
val metadata = Properties().apply { sdkDirectory.resolve("sdk.properties").inputStream().use(::load) }
check(metadata.getProperty("format") == "1") { "Unsupported SDK metadata" }
listOf("sdkVersion", "modVersion", "modBuildVersion").forEach { field ->
    check(!metadata.getProperty(field).isNullOrBlank()) { "SDK metadata field missing: $field" }
}

if (workspace == null && explicitSdk == null) {
    val release = providers.gradleProperty("compuktersRelease").get()
    check(metadata.getProperty("modVersion") == release && metadata.getProperty("modBuildVersion") == release) {
        "Downloaded SDK does not belong to the selected stable release"
    }
}
check(sdkDirectory.resolve("repository").isDirectory) { "SDK repository is missing" }
gradle.extra["compuktersSdkDirectory"] = sdkDirectory.absolutePath

settings.pluginManagement.apply {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(sdkDirectory.resolve("repository")) } }
            filter {
                includeGroup("ru.lazyhat.compukters")
                includeGroup("ru.lazyhat.compukters.addon")
            }
        }
        mavenCentral()
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
    plugins { id("ru.lazyhat.compukters.addon") version metadata.getProperty("sdkVersion") }
}

if (workspace != null) {
    includeBuild(workspace) {
        name = "Compukters"
        dependencySubstitution {
            substitute(module("ru.lazyhat.compukters:compukters-addon-api")).using(project(":addon-api"))
            substitute(module("ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1")).using(project(":v1_21_1-addon-neoforge-api"))
        }
    }
}
