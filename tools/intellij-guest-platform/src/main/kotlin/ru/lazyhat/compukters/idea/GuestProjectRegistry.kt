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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.analysis.api.platform.modification.publishGlobalModuleStateModificationEvent
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.gradle.model.GuestProjectModel
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class GuestRegistryState {
    var modules: MutableMap<String, GuestModuleConfiguration> = linkedMapOf()
}

class GuestModuleConfiguration {
    var addonId: String = ""
    var projectDirectory: String = ""
    var externalRoot: String = ""
    var sourceDirectories: MutableList<String> = mutableListOf()
    var platformFiles: MutableList<String> = mutableListOf()
    var dependencyBundles: MutableList<String> = mutableListOf()
    var generatedSources: MutableList<String> = mutableListOf()
    var compileTaskPath: String = ""
    var problems: MutableList<String> = mutableListOf()

    companion object {
        fun from(model: GuestProjectModel) =
            GuestModuleConfiguration().apply {
                addonId = model.addonId
                projectDirectory = model.projectDirectory.absolutePath
                sourceDirectories = model.sourceDirectories.map { it.absolutePath }.toMutableList()
                platformFiles = model.platformFiles.map { it.absolutePath }.toMutableList()
                dependencyBundles = model.dependencyBundles.map { it.absolutePath }.toMutableList()
                generatedSources = model.generatedSourceDirectories.map { it.absolutePath }.toMutableList()
                compileTaskPath = model.compileTaskPath
                problems = model.problems.toMutableList()
                if (model.modelVersion !=
                    GuestProjectModel.CURRENT_VERSION
                ) {
                    problems += "Unsupported Guest model version ${model.modelVersion}"
                }
            }
    }
}

data class GuestModuleSnapshot(
    val configuration: GuestModuleConfiguration,
    val bundle: PlatformBundle?,
    val problems: List<String>,
    val loading: Boolean,
    val generation: Long,
)

/** Persistent Gradle identity, asynchronous artifact IO and explicit native loading/error states. */
@Service(Service.Level.PROJECT)
@State(name = "CompuktersGuestModules", storages = [Storage("compukters-guest.xml")])
class GuestProjectRegistry(
    private val project: Project,
    private val scope: CoroutineScope,
) : PersistentStateComponent<GuestRegistryState>,
    Disposable {
    @Volatile private var configuration = GuestRegistryState()
    private val sequence = AtomicLong()
    private val snapshots = ConcurrentHashMap<String, GuestModuleSnapshot>()
    private val sources = ConcurrentHashMap<String, GuestSourceModule>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<GuestModuleSnapshot>>()

    @Volatile private var disposed = false

    init {
        // When a project stops applying the addon plugin there is no custom Gradle
        // node left to import. Module removal is the authoritative lifecycle event.
        project.messageBus.connect(this).subscribe(
            ModuleListener.TOPIC,
            object : ModuleListener {
                override fun moduleRemoved(
                    project: Project,
                    module: Module,
                ) {
                    removeModules(listOf(module.name))
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    configuration.modules.forEach { (name, config) ->
                        val inputs = config.platformFiles + config.dependencyBundles
                        if (events.any { event -> inputs.any { it == event.path || it.startsWith("${event.path}/") } }) {
                            reload(name, config)
                        }
                    }
                }
            },
        )
    }

    override fun getState(): GuestRegistryState = configuration

    override fun loadState(state: GuestRegistryState) {
        configuration = state
        snapshots.clear()
        sources.clear()
        requested.clear()
        state.modules.forEach { (name, config) -> reload(name, config) }
    }

    fun importModule(
        name: String,
        model: GuestProjectModel,
        externalRoot: String = model.projectDirectory.absolutePath,
    ): CompletableFuture<GuestModuleSnapshot> {
        val config = GuestModuleConfiguration.from(model).also { it.externalRoot = externalRoot }
        return reload(name, config, persist = true)
    }

    fun removeModules(names: Collection<String>) {
        if (names.isEmpty()) return
        onWrite {
            project.publishGlobalModuleStateModificationEvent()
            configuration = GuestRegistryState().also { it.modules = (configuration.modules - names.toSet()).toMutableMap() }
            names.forEach {
                snapshots.remove(it)
                sources.remove(it)
                requested.remove(it)
            }
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(GuestStatusWidget.ID)
        }
    }

    fun sourceFor(module: Module): GuestSourceModule? {
        val config = configuration.modules[module.name] ?: return null
        if (requested.add(module.name)) reload(module.name, config)
        val snapshot = snapshots[module.name] ?: GuestModuleSnapshot(config, null, emptyList(), true, 0)
        return sources.compute(module.name) { _, previous ->
            if (previous?.generation == snapshot.generation) previous else GuestSourceModule(module, snapshot.bundle, snapshot.generation)
        }
    }

    fun snapshot(name: String): GuestModuleSnapshot? = snapshots[name]

    fun configurations(): Map<String, GuestModuleConfiguration> = configuration.modules.toMap()

    private fun reload(
        name: String,
        config: GuestModuleConfiguration,
        persist: Boolean = false,
    ): CompletableFuture<GuestModuleSnapshot> {
        val completion = CompletableFuture<GuestModuleSnapshot>()
        pending.add(completion)
        completion.whenComplete { _, _ -> pending.remove(completion) }
        if (disposed || project.isDisposed) {
            completion.cancel(false)
            return completion
        }
        onWrite {
            if (persist) {
                configuration = GuestRegistryState().also { it.modules = (configuration.modules + (name to config)).toMutableMap() }
            }
            requested.add(name)
            val generation = sequence.incrementAndGet()
            project.publishGlobalModuleStateModificationEvent()
            snapshots[name] = GuestModuleSnapshot(config, null, emptyList(), true, generation)
            sources.remove(name)
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(GuestStatusWidget.ID)
            scope
                .launch(Dispatchers.IO) {
                    val result = runCatching { load(config) }
                    onWrite {
                        if (snapshots[name]?.generation != generation) {
                            completion.cancel(false)
                            return@onWrite
                        }
                        project.publishGlobalModuleStateModificationEvent()
                        val snapshot =
                            GuestModuleSnapshot(
                                config,
                                result.getOrNull(),
                                result.exceptionOrNull()?.let { listOf(it.message ?: it.javaClass.simpleName) }.orEmpty(),
                                false,
                                sequence.incrementAndGet(),
                            )
                        snapshots[name] = snapshot
                        sources.remove(name)
                        WindowManager.getInstance().getStatusBar(project)?.updateWidget(GuestStatusWidget.ID)
                        completion.complete(snapshot)
                    }
                }.invokeOnCompletion { cause -> if (cause != null) completion.cancel(false) }
        }
        return completion
    }

    private fun load(config: GuestModuleConfiguration): PlatformBundle {
        require(config.problems.isEmpty()) { config.problems.joinToString("\n") }
        require(config.platformFiles.size == 1) { "Guest analysis requires exactly one platform bundle" }
        val base = PlatformBundleCodec.decode(Files.readAllBytes(Path.of(config.platformFiles.single())))
        val dependencies =
            config.dependencyBundles.map { path ->
                AddonGuestApiBundleCodec
                    .decode(Files.readAllBytes(Path.of(path)))
                    .also {
                        require(it.identity.platformAbi == base.identity.platformAbi) { "Dependency platform ABI mismatch: $path" }
                    }.moduleDescriptor
            }
        require(dependencies.map { it.id }.distinct().size == dependencies.size) { "Duplicate Guest dependency identity" }
        require(dependencies.none { addon -> base.modules.any { it.id == addon.id } }) { "Guest dependency shadows base platform" }
        val combined =
            PlatformBundleCodec.assemble(
                base.identity.languageVersion,
                base.identity.platformAbi,
                base.builtins,
                base.modules + dependencies,
            )
        PlatformModuleGraph(combined).resolve(combined.modules.mapTo(mutableSetOf()) { it.id })
        return combined
    }

    private fun onWrite(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) WriteAction.run<RuntimeException>(action)
        }, project.disposed)
    }

    override fun dispose() {
        disposed = true
        pending.forEach { it.cancel(false) }
        pending.clear()
        snapshots.clear()
        sources.clear()
    }
}
