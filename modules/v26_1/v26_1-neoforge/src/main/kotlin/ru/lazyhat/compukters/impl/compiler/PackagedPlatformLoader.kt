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

package ru.lazyhat.compukters.impl.compiler

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Path
import java.util.zip.ZipFile

internal object PackagedPlatformLoader {
    fun load(
        classpath: List<Path>,
        compilerIdentity: WorkerIdentity,
    ): PlatformBundle {
        val candidates =
            classpath.mapNotNull { path ->
                if (!path.fileName.toString().endsWith(".jar")) return@mapNotNull null
                ZipFile(path.toFile()).use { archive ->
                    val entry = archive.getEntry(PLATFORM_ENTRY) ?: return@use null
                    check(entry.size in 0..MAX_PLATFORM_BYTES) { "packaged Compukters platform exceeds its byte limit" }
                    archive.getInputStream(entry).use { input ->
                        val bytes = input.readNBytes((MAX_PLATFORM_BYTES + 1).toInt())
                        check(bytes.size.toLong() <= MAX_PLATFORM_BYTES) { "packaged Compukters platform exceeds its byte limit" }
                        PlatformBundleCodec.decode(bytes)
                    }
                }
            }
        check(candidates.size == 1) { "compiler payload must contain exactly one Compukters platform bundle" }
        return admit(candidates.single(), compilerIdentity)
    }

    fun admit(
        platform: PlatformBundle,
        compilerIdentity: WorkerIdentity,
    ): PlatformBundle {
        check(platform.identity.languageVersion == compilerIdentity.languageVersion) {
            "Compukters platform language identity does not match compiler worker"
        }
        check(Hash256.of(platform.identity.contentHash.toByteArray()) == compilerIdentity.platformAbi) {
            "Compukters platform content identity does not match compiler worker"
        }
        return platform
    }

    private const val PLATFORM_ENTRY = "compukters-platform/compukters-platform.cpb"
    private const val MAX_PLATFORM_BYTES = 128L * 1024 * 1024
}
