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

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ProjectViewModuleNode
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class GuestProjectViewDecoratorTest : BasePlatformTestCase() {
    fun testImportedModuleAndRootsAreMarkedAndRemovalClearsThem() {
        val file = myFixture.addFileToProject("src/contract/kotlin/fixture/Engine.kt", "package fixture\ninterface Engine")
        val root = requireNotNull(file.containingDirectory.parentDirectory)
        val contract = requireNotNull(root.parentDirectory)
        val registry = project.getService(GuestProjectRegistry::class.java)
        registry.loadState(
            GuestRegistryState().apply {
                modules[module.name] = GuestModuleConfiguration().apply { sourceDirectories.add(root.virtualFile.path) }
            },
        )
        val moduleNode = ProjectViewModuleNode(project, module, ViewSettings.DEFAULT)
        val sourceNode = PsiDirectoryNode(project, root, ViewSettings.DEFAULT)
        val contractNode = PsiDirectoryNode(project, contract, ViewSettings.DEFAULT)
        for (node in listOf(moduleNode, sourceNode, contractNode)) {
            val presentation = decorate(node)
            assertEquals(GuestProjectViewDecorator.FOREGROUND, presentation.forcedTextForeground)
            assertEquals("Guest", presentation.locationString)
            assertEquals("contract", presentation.presentableText)
        }
        assertNull(decorate(PsiDirectoryNode(project, file.containingDirectory, ViewSettings.DEFAULT)).forcedTextForeground)
        registry.removeModules(listOf(module.name))
        UIUtil.dispatchAllInvocationEvents()
        assertNull(decorate(moduleNode).forcedTextForeground)
        assertNull(decorate(sourceNode).locationString)
    }

    fun testOrdinaryDirectoriesAndHostModulesKeepTheirPresentation() {
        val file = myFixture.addFileToProject("notes/contract/kotlin/Notes.kt", "class Notes")
        assertNull(decorate(PsiDirectoryNode(project, file.containingDirectory, ViewSettings.DEFAULT)).forcedTextForeground)
        assertNull(decorate(ProjectViewModuleNode(project, module, ViewSettings.DEFAULT)).locationString)
    }

    private fun decorate(node: ProjectViewNode<*>): PresentationData {
        val decorator =
            project.extensionArea
                .getExtensionPoint<ProjectViewNodeDecorator>("com.intellij.projectViewNodeDecorator")
                .extensionList
                .filterIsInstance<GuestProjectViewDecorator>()
                .single()
        return PresentationData().apply {
            presentableText = "contract"
            decorator.decorate(node, this)
        }
    }
}
