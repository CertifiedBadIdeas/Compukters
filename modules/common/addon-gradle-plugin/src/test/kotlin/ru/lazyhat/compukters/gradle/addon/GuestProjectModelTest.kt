/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.gradle.addon

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.UnknownModelException
import ru.lazyhat.compukters.gradle.model.GuestProjectModel
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestProjectModelTest {
    @Test
    fun `tooling import discovers native inputs without compiling or changing the ABI lock`() {
        val root = Files.createTempDirectory("compukters-guest-model-")
        try {
            val metadata = requireNotNull(javaClass.getResourceAsStream("/plugin-under-test-metadata.properties"))
            val pluginClasspath =
                Properties()
                    .apply {
                        metadata.use(::load)
                    }.getProperty("implementation-classpath")
                    .split(java.io.File.pathSeparator)
            root.resolve("settings.gradle").writeText("rootProject.name = 'model-fixture'")
            root.resolve("platform.cpb").writeText("discovery does not decode or execute artifacts")
            root.resolve("dependency.cagb").writeText("dependency")
            root.resolve("src/contract").createDirectories()
            val lock = root.resolve("src/contract/addon.lock")
            lock.writeText("existing ABI lock remains untouched")
            root.resolve("build.gradle").writeText(
                """
                buildscript {
                    dependencies { classpath files(${pluginClasspath.joinToString { it.groovyQuoted() }}) }
                }
                apply plugin: 'ru.lazyhat.compukters.addon'
                version = '1.0.0'
                compuktersAddon {
                    register('fixture', '1.0.0')
                    platformCoordinate.set('missing:platform:1@cpb')
                }
                configurations.compuktersAddonPlatform.dependencies.clear()
                afterEvaluate {
                    configurations.compuktersAddonPlatform.dependencies.clear()
                    dependencies.add('compuktersAddonPlatform', files('platform.cpb'))
                }
                dependencies.add('compuktersGuestDependencies', files('dependency.cagb'))
                tasks.configureEach {
                    doFirst { throw new GradleException('Model import must not execute tasks') }
                }
                """.trimIndent(),
            )
            val connection =
                GradleConnector
                    .newConnector()
                    .forProjectDirectory(root.toFile())
                    .useGradleUserHomeDir(java.io.File(requireNotNull(System.getProperty("compukters.test.gradle.user.home"))))
                    .useInstallation(java.io.File(requireNotNull(System.getProperty("compukters.test.gradle.home"))))
                    .connect()
            connection.use {
                val model = it.getModel(GuestProjectModel::class.java)
                assertEquals(GuestProjectModel.CURRENT_VERSION, model.modelVersion)
                assertEquals("fixture", model.addonId)
                assertEquals(":", model.projectPath)
                assertEquals(listOf(root.resolve("src/contract/kotlin").toFile()), model.sourceDirectories)
                assertEquals(listOf(root.resolve("platform.cpb").toFile()), model.platformFiles)
                assertEquals(listOf(root.resolve("dependency.cagb").toFile()), model.dependencyBundles)
                assertEquals(":assembleCompuktersAddon", model.compileTaskPath)
                assertEquals(emptyList(), model.problems)
                assertFalse(model.bundleFile.exists(), "Import must not compile the addon")
                assertEquals("existing ABI lock remains untouched", Files.readString(lock))
            }
            root.resolve("build.gradle").writeText("")
            GradleConnector
                .newConnector()
                .forProjectDirectory(root.toFile())
                .useGradleUserHomeDir(java.io.File(requireNotNull(System.getProperty("compukters.test.gradle.user.home"))))
                .useInstallation(java.io.File(requireNotNull(System.getProperty("compukters.test.gradle.home"))))
                .connect()
                .use {
                    assertFailsWith<UnknownModelException> { it.getModel(GuestProjectModel::class.java) }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing Guest platform remains an explicit import problem`() {
        val project =
            org.gradle.testfixtures.ProjectBuilder
                .builder()
                .build()
        project.pluginManager.apply(CompuktersAddonPlugin::class.java)
        project.extensions.getByType(CompuktersAddonExtension::class.java).register("fixture")
        project.dependencies.add("compuktersAddonPlatform", project.files("missing.cpb"))
        val model = GuestProjectModelBuilder().buildAll(GuestProjectModel::class.java.name, project) as GuestProjectModel
        assertTrue(model.platformFiles.single().name == "missing.cpb")
        assertTrue(model.problems.single().contains("artifact is missing"))
        assertFalse(model.bundleFile.exists())
    }

    private fun String.groovyQuoted(): String = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
