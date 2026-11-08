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
import com.intellij.openapi.module.Module
import com.intellij.psi.PsiDirectory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes

/** Marks imported native sources without changing their source-root type or JVM neighbours. */
class GuestProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(
        node: ProjectViewNode<*>,
        data: PresentationData,
    ) {
        val registry = node.project?.getService(GuestProjectRegistry::class.java) ?: return
        val guest =
            when (val value = node.value) {
                is Module -> registry.isGuestModule(value.name)
                is PsiDirectory -> registry.isGuestSourceRoot(value.virtualFile.path)
                else -> false
            }
        if (!guest) return
        data.forcedTextForeground = FOREGROUND
        // NodeRenderer gives explicit fragment colours priority over forcedTextForeground.
        // Like the renderer, keep the name separate from the grey supplementary text.
        val fragments = data.coloredText.toList()
        data.clearText()
        var mainText = true
        fragments.forEachIndexed { index, fragment ->
            val attributes = fragment.attributes
            if (index > 0 && attributes.fgColor == SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor) mainText = false
            data.addText(
                fragment.text,
                if (mainText) attributes.derive(attributes.style, FOREGROUND, null, null) else attributes,
            )
        }
        val location = data.locationString
        if (location != "Guest" && location?.endsWith(" · Guest") != true) {
            data.locationString = if (location.isNullOrBlank()) "Guest" else "$location · Guest"
        }
    }

    companion object {
        val FOREGROUND: JBColor = JBColor.namedColor("Compukters.GuestSource.foreground", JBColor(0x7653B5, 0xB69AEC))
    }
}
