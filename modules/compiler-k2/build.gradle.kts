/*
 * The Compukters Developers
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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

plugins {
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compilerArtifact)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.scripting.compiler.embeddable)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val workerRuntimeClasspath = configurations.create("workerRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
}

val workerPayloadDirectory = layout.buildDirectory.dir("worker-payload/content")
val pinnedKotlinVersion = libs.versions.kotlin.asProvider().get()

val prepareCompilerWorkerPayload = tasks.register<Sync>("prepareCompilerWorkerPayload") {
    dependsOn(tasks.jar)
    into(workerPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar) {
        into("lib")
    }
    from(workerRuntimeClasspath) {
        into("lib")
    }

    doLast {
        val root = workerPayloadDirectory.get().asFile.toPath()
        val libraryDirectory = root.resolve("lib")
        val files =
            Files.list(libraryDirectory).use { paths ->
                paths.sorted().map { path ->
                    val bytes = Files.readAllBytes(path)
                    Triple("lib/${path.fileName}", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
                }.toList()
            }
        val payloadDigest = MessageDigest.getInstance("SHA-256")
        payloadDigest.update("Compukter compiler worker payload v1\u0000".toByteArray())
        listOf(pinnedKotlinVersion, "2.4", application.mainClass.get()).forEach { value ->
            payloadDigest.update(value.toByteArray())
            payloadDigest.update(0)
        }
        payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        payloadDigest.update(ByteArray(32))
        files.forEach { (path, size, hash) ->
            payloadDigest.update(path.toByteArray())
            payloadDigest.update(0)
            payloadDigest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
            payloadDigest.update(hash)
        }
        val payloadHash = payloadDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest =
            buildString {
                appendLine("format=1")
                appendLine("compiler=$pinnedKotlinVersion")
                appendLine("language=2.4")
                appendLine("codegenAbi=1")
                appendLine("artifactWriter=1")
                appendLine("mainClass=${application.mainClass.get()}")
                appendLine("payloadSha256=$payloadHash")
                files.forEach { (path, size, hash) ->
                    append("file=").append(path).append('\t').append(size).append('\t')
                    appendLine(hash.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
            }
        Files.writeString(root.resolve("worker.payload"), manifest)
    }
}

tasks.register<Zip>("compilerWorkerPayload") {
    dependsOn(prepareCompilerWorkerPayload)
    from(workerPayloadDirectory)
    archiveFileName = "compiler-k2-worker.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val workerJar = tasks.jar.flatMap { it.archiveFile }

tasks.test {
    dependsOn(tasks.jar)
    inputs.file(workerJar)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
    }
}
