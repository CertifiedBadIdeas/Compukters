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

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

fun Configuration.runtimeModuleNames(): Provider<List<String>> =
    incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.module }.distinct().sorted()
    }

fun Configuration.runtimeComponentNames(ownerPath: String): Provider<List<String>> =
    incoming.resolutionResult.rootComponent.map { root ->
        val visited = mutableSetOf<ComponentIdentifier>()
        val result = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component.id)) return
            when (val id = component.id) {
                is ModuleComponentIdentifier -> result.add("${id.group}:${id.module}")
                is ProjectComponentIdentifier -> if (id.projectPath != ownerPath) result.add(id.projectPath)
            }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                .forEach { visit(it.selected) }
        }
        visit(root)
        result.sorted()
    }

abstract class AssertForbiddenRuntimeModules : DefaultTask() {
    @get:Input
    abstract val moduleNames: ListProperty<String>

    @get:Input
    abstract val forbiddenNames: SetProperty<String>

    init {
        moduleNames.convention(emptyList())
    }

    @TaskAction
    fun verify() {
        val leaked = moduleNames.get().filter(forbiddenNames.get()::contains)
        check(leaked.isEmpty()) { "$path production runtime leaks compiler artifacts: $leaked" }
    }
}

data class RuntimeModuleCoordinate(val group: String, val name: String, val version: String)

fun Configuration.runtimeModuleCoordinates(): Provider<List<RuntimeModuleCoordinate>> =
    incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
                RuntimeModuleCoordinate(it.group, it.module, it.version)
            }
        }.distinct().sortedWith(compareBy({ it.group }, { it.name }, { it.version }))
    }
