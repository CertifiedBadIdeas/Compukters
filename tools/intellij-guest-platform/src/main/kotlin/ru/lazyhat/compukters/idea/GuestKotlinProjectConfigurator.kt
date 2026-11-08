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

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.base.projectStructure.ModuleSourceRootGroup
import org.jetbrains.kotlin.idea.base.projectStructure.getWholeModuleGroup
import org.jetbrains.kotlin.idea.configuration.ConfigureKotlinStatus
import org.jetbrains.kotlin.idea.configuration.KotlinProjectConfigurator
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms

/** Gradle's native module model is the configuration; a JVM stdlib is not required. */
class GuestKotlinProjectConfigurator : KotlinProjectConfigurator {
    override val name: String = "compukters-guest"
    override val presentableText: String = "Compukters Guest Kotlin"
    override val targetPlatform = CompuktersPlatforms.default

    override fun isApplicable(module: Module): Boolean {
        val registry = module.project.getService(GuestProjectRegistry::class.java)
        if (registry.isGuestModule(module.name)) return true
        if (registry.configurations().isEmpty()) return false
        // Kotlin asks applicability on a Gradle group's owner, even for a single source set.
        return module.getWholeModuleGroup().sourceRootModules.any { registry.isGuestModule(it.name) }
    }

    override fun getStatus(moduleSourceRootGroup: ModuleSourceRootGroup): ConfigureKotlinStatus {
        val registry = moduleSourceRootGroup.baseModule.project.getService(GuestProjectRegistry::class.java)
        val sources = moduleSourceRootGroup.sourceRootModules.ifEmpty { listOf(moduleSourceRootGroup.baseModule) }
        // A Guest source set is configured independently of its JVM neighbours and group owner.
        return if (sources.all {
                registry.isGuestModule(
                    it.name,
                )
            }
        ) {
            ConfigureKotlinStatus.CONFIGURED
        } else {
            ConfigureKotlinStatus.NON_APPLICABLE
        }
    }

    override fun shouldShowNotConfiguredDialog(): Boolean = false

    override fun configure(
        project: Project,
        excludeModules: Collection<Module>,
    ) {
        // Native configuration is supplied by Gradle import, never by adding JVM dependencies.
    }

    override fun updateLanguageVersion(
        module: Module,
        languageVersion: String?,
        apiVersion: String?,
        requiredStdlibVersion: ApiVersion,
        forTests: Boolean,
    ): Unit = throw UnsupportedOperationException("Guest language settings belong to the selected native platform bundle")

    override fun changeGeneralFeatureConfiguration(
        module: Module,
        feature: LanguageFeature,
        state: LanguageFeature.State,
        forTests: Boolean,
    ): Unit = throw UnsupportedOperationException("Guest features belong to the selected native platform bundle")
}
