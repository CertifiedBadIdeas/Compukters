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
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class GuestProjectViewDecoratorTest : BasePlatformTestCase() {
    fun testRenderedGuestNameOverridesAnExplicitFragmentColor() {
        val registry = project.getService(GuestProjectRegistry::class.java)
        registry.loadState(
            GuestRegistryState().apply {
                modules[module.name] = GuestModuleConfiguration()
            },
        )
        for (foreground in listOf(Color(0x4B72B8), SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor)) {
            val node = ProjectViewModuleNode(project, module, ViewSettings.DEFAULT)
            val presentation = node.presentation
            presentation.presentableText = "contract"
            presentation.addText("contract", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, foreground))
            presentation.addText(" details", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            decorator().decorate(node, presentation)
            val renderer = NodeRenderer()
            renderer.getTreeCellRendererComponent(JTree(), DefaultMutableTreeNode(node), false, true, true, 0, false)
            val text = renderer.iterator()
            assertEquals("contract", text.next())
            assertEquals(GuestProjectViewDecorator.FOREGROUND, text.textAttributes.fgColor)
            assertEquals(SimpleTextAttributes.STYLE_BOLD, text.textAttributes.style)
            assertEquals(" details", text.next())
            assertEquals(SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor, text.textAttributes.fgColor)
            val size = renderer.preferredSize
            renderer.setSize(size)
            val preview = BufferedImage(size.width * 2, size.height * 2, BufferedImage.TYPE_INT_ARGB)
            val graphics = preview.createGraphics()
            try {
                graphics.scale(2.0, 2.0)
                graphics.color = UIUtil.getTreeBackground()
                graphics.fillRect(0, 0, size.width, size.height)
                renderer.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val output = Path.of("build/reports/guest-project-tree.png")
            Files.createDirectories(output.parent)
            ImageIO.write(preview, "png", output.toFile())
        }
    }

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

    private fun decorate(node: ProjectViewNode<*>): PresentationData =
        PresentationData().apply {
            presentableText = "contract"
            decorator().decorate(node, this)
        }

    private fun decorator(): GuestProjectViewDecorator =
        project.extensionArea
            .getExtensionPoint<ProjectViewNodeDecorator>("com.intellij.projectViewNodeDecorator")
            .extensionList
            .filterIsInstance<GuestProjectViewDecorator>()
            .single()
}
