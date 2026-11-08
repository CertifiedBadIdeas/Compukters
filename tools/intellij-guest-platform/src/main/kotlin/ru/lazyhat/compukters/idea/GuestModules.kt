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

// IDEA 261 requires its internal entity-backed base for every KaSourceModule.
// This adapter is intentionally restricted to that IDE line in plugin.xml.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS")

package ru.lazyhat.compukters.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.idea.base.facet.platform.TargetPlatformDetector
import org.jetbrains.kotlin.idea.base.fir.projectStructure.FirKaModuleFactory
import org.jetbrains.kotlin.idea.base.fir.projectStructure.modules.source.KaSourceModuleBase
import org.jetbrains.kotlin.idea.base.projectStructure.KaSourceModuleKind
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Explicit per-module opt-in. Host Kotlin modules retain their original model. */
class GuestModuleFactory : FirKaModuleFactory {
    override fun createKaModuleByPsiFile(psiFile: PsiFile): KaModule? {
        val module = ModuleUtilCore.findModuleForPsiElement(psiFile) ?: return null
        return GuestSourceModule.forModule(module)
    }
}

class GuestPlatformDetector : TargetPlatformDetector {
    override fun detectPlatform(file: KtFile): TargetPlatform? {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
        return GuestSourceModule.forModule(module)?.targetPlatform
    }
}

class GuestSourceModule internal constructor(
    private val hostModule: Module,
    val bundle: PlatformBundle?,
    val generation: Long = 0,
) : KaSourceModuleBase() {
    internal val navigationFiles = ConcurrentHashMap<String, VirtualFile>()
    override val entityId get() = ModuleId(hostModule.name)
    override val kind get() = KaSourceModuleKind.PRODUCTION
    override val name get() = hostModule.name
    override val project: Project get() = hostModule.project

    override fun equals(other: Any?) = other is GuestSourceModule && other.hostModule == hostModule && other.generation == generation

    override fun hashCode() = 31 * hostModule.hashCode() + generation.hashCode()

    override val targetPlatform get() = CompuktersPlatforms.default
    override val languageVersionSettings =
        LanguageVersionSettingsImpl(
            LanguageVersionSettingsImpl.DEFAULT.languageVersion,
            LanguageVersionSettingsImpl.DEFAULT.apiVersion,
            specificFeatures = mapOf(LanguageFeature.ValueClasses to LanguageFeature.State.ENABLED),
        )
    override val baseContentScope get() = GlobalSearchScope.moduleScope(hostModule)
    override val directRegularDependencies: List<KaModule> = bundle?.let { listOf(GuestLibraryModule(project, it)) }.orEmpty()
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val transitiveDependsOnDependencies: List<KaModule> = emptyList()
    override val directFriendDependencies: List<KaModule> = emptyList()

    companion object {
        private val KEY = Key.create<GuestSourceModule>("compukters.guest.source.module")

        @Synchronized
        fun forModule(module: Module): GuestSourceModule? {
            module.project
                .getService(GuestProjectRegistry::class.java)
                .sourceFor(module)
                ?.let { return it }
            module.getUserData(KEY)?.let { return it }
            val root =
                ModuleRootManager.getInstance(module).contentRoots.firstOrNull {
                    it.findChild("compukters-guest.properties") != null
                } ?: return null
            val properties =
                Properties().apply {
                    root.findChild("compukters-guest.properties")!!.inputStream.use(::load)
                }
            val path = Path.of(root.path).resolve(requireNotNull(properties.getProperty("platform"))).normalize()
            val bundle = PlatformBundleCodec.decode(Files.readAllBytes(path))
            return GuestSourceModule(module, bundle).also { module.putUserData(KEY, it) }
        }
    }
}

class GuestLibraryModule(
    override val project: Project,
    val bundle: PlatformBundle,
) : KaLibraryModule {
    override val libraryName = "compukters-platform"
    override val targetPlatform get() = CompuktersPlatforms.default
    override val baseContentScope get() = GlobalSearchScope.EMPTY_SCOPE
    override val contentScope get() = baseContentScope
    override val binaryRoots: Collection<Path> = emptyList()
    override val binaryVirtualFiles: Collection<VirtualFile> = emptyList()
    override val librarySources: KaLibrarySourceModule? = null
    override val isSdk = false
    override val directRegularDependencies: List<KaModule> = emptyList()
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val transitiveDependsOnDependencies: List<KaModule> = emptyList()
    override val directFriendDependencies: List<KaModule> = emptyList()
}
