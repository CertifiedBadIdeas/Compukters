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

package ru.lazyhat.compukters.platform.k2

import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.platform.TargetPlatform
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.nio.file.Path
import java.util.Collections

class CompuktersSession internal constructor(
    val targetPlatform: TargetPlatform,
    val metadata: CompuktersMetadataProvider,
) {
    val binaryRoots: List<Path> = emptyList()

    fun resolve(symbol: String): PlatformDeclaration? = metadata.resolve(symbol)
}

object CompuktersSessionConfigurator {
    fun create(
        bundle: PlatformBundle,
        selectedModules: Set<PlatformModuleId>,
    ): CompuktersSession =
        CompuktersSession(
            CompuktersPlatforms.default,
            CompuktersMetadataProvider(bundle, Collections.unmodifiableSet(selectedModules.toSet())),
        )
}

object CompuktersLLFirSessionConfigurator : LLFirSessionConfigurator {
    override fun configure(session: LLFirSession) {
        require(supports(session.ktModule.targetPlatform)) { "Compukters LL FIR configurator received a foreign target" }
    }

    fun supports(platform: TargetPlatform): Boolean = platform == CompuktersPlatforms.default
}
