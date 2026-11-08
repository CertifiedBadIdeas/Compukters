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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.LibraryData
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.model.project.LibraryLevel
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.util.GradleConstants
import ru.lazyhat.compukters.gradle.model.GuestProjectModel

/** Native sources have an IDE module of their own; host JVM source sets remain ordinary Gradle modules. */
class GuestGradleResolver : AbstractProjectResolverExtension() {
    override fun getExtraProjectModelClasses(): Set<Class<*>> = setOf(GuestProjectModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<*>> = setOf(GuestProjectModel::class.java)

    override fun populateModuleExtraModels(
        gradleModule: IdeaModule,
        ideModule: DataNode<ModuleData>,
    ) {
        super.populateModuleExtraModels(gradleModule, ideModule)
        val model = resolverCtx.getExtraProject(gradleModule, GuestProjectModel::class.java) ?: return
        val parent = ideModule.data
        val native =
            GradleSourceSetData(
                "${parent.id}:contract",
                "${parent.externalName}:contract",
                "${parent.internalName}.contract",
                parent.moduleFileDirectoryPath,
                model.projectDirectory.absolutePath,
            )
        val node = ideModule.createChild(GradleSourceSetData.KEY, native)
        model.sourceDirectories.forEach { directory ->
            val root = ContentRootData(GradleConstants.SYSTEM_ID, directory.absolutePath)
            root.storePath(ExternalSystemSourceType.SOURCE, directory.absolutePath)
            node.createChild(ProjectKeys.CONTENT_ROOT, root)
        }
        val library = LibraryData(GradleConstants.SYSTEM_ID, "Compukters Guest ${model.addonId}", false)
        (model.platformFiles + model.dependencyBundles).forEach { library.addPath(LibraryPathType.BINARY, it.absolutePath) }
        node.createChild(ProjectKeys.LIBRARY_DEPENDENCY, LibraryDependencyData(native, library, LibraryLevel.MODULE))
        node.createChild(GuestGradleData.KEY, GuestGradleData(model))
    }
}

class GuestGradleData(
    val model: GuestProjectModel,
) : AbstractExternalEntityData(GradleConstants.SYSTEM_ID) {
    companion object {
        val KEY: Key<GuestGradleData> = Key.create(GuestGradleData::class.java, GradleSourceSetData.KEY.processingWeight + 50)
    }
}

/** A plain Gradle import has no Guest nodes, so it must reconcile at the project boundary. */
class GuestGradleProjectDataService : AbstractProjectDataService<ProjectData, String>() {
    override fun getTargetDataKey(): Key<ProjectData> = ProjectKeys.PROJECT

    override fun importData(
        toImport: Collection<DataNode<ProjectData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        val registry = project.getService(GuestProjectRegistry::class.java)
        toImport.forEach { root ->
            if (root.data.owner != GradleConstants.SYSTEM_ID) return@forEach
            val directories = mutableSetOf<String>()
            val pending = ArrayDeque<DataNode<*>>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                (node.data as? GuestGradleData)?.model?.let { directories.add(it.projectDirectory.absolutePath) }
                pending.addAll(node.children)
            }
            val removed =
                registry
                    .configurations()
                    .filter { (_, config) ->
                        config.externalRoot == root.data.linkedExternalProjectPath && config.projectDirectory !in directories
                    }.keys
            registry.removeModules(removed)
        }
    }
}

class GuestGradleDataService : AbstractProjectDataService<GuestGradleData, String>() {
    override fun getTargetDataKey(): Key<GuestGradleData> = GuestGradleData.KEY

    override fun importData(
        toImport: Collection<DataNode<GuestGradleData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        val registry = project.getService(GuestProjectRegistry::class.java)
        toImport.forEach { node ->
            val data = node.parent?.data as? ModuleData ?: return@forEach
            val module = modelsProvider.findIdeModule(data) ?: return@forEach
            registry.importModule(
                module.name,
                node.data.model,
                projectData?.linkedExternalProjectPath ?: node.data.model.projectDirectory.absolutePath,
            )
        }
    }

    override fun computeOrphanData(
        toImport: Collection<DataNode<GuestGradleData>>,
        projectData: ProjectData,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ): Computable<Collection<String>> {
        val imported =
            toImport.mapNotNull { node -> (node.parent?.data as? ModuleData)?.let(modelsProvider::findIdeModule)?.name }.toSet()
        val removed =
            project
                .getService(GuestProjectRegistry::class.java)
                .configurations()
                .filter { (name, config) ->
                    config.externalRoot == projectData.linkedExternalProjectPath && name !in imported
                }.keys
                .toList()
        return Computable { removed }
    }

    override fun removeData(
        toRemove: Computable<out Collection<String>>,
        toIgnore: Collection<DataNode<GuestGradleData>>,
        projectData: ProjectData,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        project.getService(GuestProjectRegistry::class.java).removeModules(toRemove.compute())
    }
}
