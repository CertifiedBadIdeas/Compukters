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

package ru.lazyhat.compukters.idea

import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirDefaultImportsProviderHolder
import org.jetbrains.kotlin.fir.scopes.FirLookupDefaultStarImportsInSourcesSettingHolder
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.resolve.DefaultImportsProvider
import org.jetbrains.kotlin.resolve.ImportPath
import ru.lazyhat.compukters.platform.k2.CompuktersMetadataSymbolProvider
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms

class GuestSessionConfigurator : LLFirSessionConfigurator {
    @OptIn(SessionConfiguration::class)
    override fun configure(session: LLFirSession) {
        if (session.ktModule.targetPlatform != CompuktersPlatforms.default) return
        val library = session.ktModule as? GuestLibraryModule
        if (library != null) {
            session.register(
                FirSymbolProvider::class,
                CompuktersMetadataSymbolProvider(
                    session,
                    SingleModuleDataProvider(session.moduleData),
                    session.kotlinScopeProvider,
                    listOf(library.bundle.builtins) + library.bundle.modules,
                    session.moduleData,
                ),
            )
        } else {
            session.register(FirDefaultImportsProviderHolder::class, FirDefaultImportsProviderHolder.Single(GuestDefaultImports))
            session.register(
                FirLookupDefaultStarImportsInSourcesSettingHolder::class,
                FirLookupDefaultStarImportsInSourcesSettingHolder(true),
            )
        }
    }
}

private object GuestDefaultImports : DefaultImportsProvider() {
    override val platformSpecificDefaultImports = emptyList<ImportPath>()
}
