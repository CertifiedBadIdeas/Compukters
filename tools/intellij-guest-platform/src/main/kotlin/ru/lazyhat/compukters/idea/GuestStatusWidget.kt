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

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import kotlinx.coroutines.CoroutineScope
import java.awt.event.MouseEvent

class GuestStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = GuestStatusWidget.ID

    override fun getDisplayName(): String = "Compukters Guest Kotlin"

    override fun createWidget(
        project: Project,
        scope: CoroutineScope,
    ): StatusBarWidget = GuestStatusWidget(project)
}

class GuestStatusWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    statusBar.updateWidget(ID)
                }
            },
        )
    }

    override fun getText(): String {
        val registry = project.getService(GuestProjectRegistry::class.java)
        val module = selectedModule()
        if (module != null) {
            val snapshot = registry.snapshot(module.name)
            if (snapshot?.loading == true || (module.bundle == null && snapshot == null)) return "Guest: loading"
            if (snapshot?.problems?.isNotEmpty() == true) return "Guest: error"
            return "Guest: active"
        }
        val names = registry.configurations().keys
        if (names.isEmpty()) return "Guest: inactive"
        if (names.any { registry.snapshot(it)?.problems?.isNotEmpty() == true }) return "Guest: error"
        if (names.any { registry.snapshot(it)?.loading != false }) return "Guest: loading"
        return "Guest: ${names.size} modules"
    }

    override fun getAlignment(): Float = 0.5f

    override fun getTooltipText(): String =
        selectedModule()?.let { module ->
            val snapshot = project.getService(GuestProjectRegistry::class.java).snapshot(module.name)
            snapshot?.problems?.joinToString("\n")?.takeIf { it.isNotEmpty() }
                ?: "Native Compukters Guest analysis: ${module.name}. Click for module inputs."
        } ?: "Compukters Guest Kotlin. Import an addon project with Gradle; click for module status."

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer {
            val registry = project.getService(GuestProjectRegistry::class.java)
            val details =
                registry
                    .configurations()
                    .entries
                    .joinToString("\n\n") { (name, config) ->
                        val snapshot = registry.snapshot(name)
                        buildString {
                            appendLine("$name (${config.addonId})")
                            appendLine(
                                if (snapshot?.loading !=
                                    false
                                ) {
                                    "Loading native metadata"
                                } else if (snapshot.problems.isEmpty()) {
                                    "Active"
                                } else {
                                    "Error"
                                },
                            )
                            snapshot?.problems?.forEach { appendLine(it) }
                            appendLine("Sources: ${config.sourceDirectories.joinToString()}")
                            appendLine("Platform: ${config.platformFiles.joinToString()}")
                            appendLine("Dependencies: ${config.dependencyBundles.joinToString()}")
                            appendLine("Generated artifacts: ${config.generatedSources.joinToString()}")
                            append("Build: ${config.compileTaskPath}")
                        }
                    }.ifEmpty { "No imported Guest modules. Reload an addon Gradle project to activate native analysis." }
            Messages.showInfoMessage(project, details, "Compukters Guest Kotlin")
        }

    private fun selectedModule(): GuestSourceModule? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { file ->
            ModuleUtilCore.findModuleForFile(file, project)?.let(GuestSourceModule::forModule)
        }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "CompuktersGuest"
    }
}
