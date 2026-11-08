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

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.base.projectStructure.ModuleSourceRootGroup
import org.jetbrains.kotlin.idea.configuration.ConfigureKotlinStatus
import org.jetbrains.kotlin.idea.configuration.isModuleConfigured
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Uses IDEA's Gradle resolver and project data import, including the out-of-process tooling model. */
@OptIn(org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class)
class GuestGradleImportTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private var registeredSdk: Sdk? = null

    override fun tearDown() {
        try {
            registeredSdk?.let { sdk -> WriteAction.run<RuntimeException> { ProjectJdkTable.getInstance().removeJdk(sdk) } }
        } finally {
            super.tearDown()
        }
    }

    fun testNativeModuleImportCompletionAndArtifactReload() {
        val root = Files.createTempDirectory("compukters-idea-import-")
        val platform = root.resolve("platform.cpb")
        Files.copy(propertyPath("guestBasePlatform"), platform)
        val dependency = root.resolve("prototype.cagb")
        Files.copy(propertyPath("guestDependency"), dependency)
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'native-import'\n")
        val plugin = propertyPath("guestGradlePluginJar").toString().replace("\\", "/").replace("'", "\\'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            buildscript { dependencies { classpath files('$plugin') } }
            apply plugin: 'java'
            apply plugin: 'ru.lazyhat.compukters.addon'
            compuktersAddon { id = 'fixture'; version = '1.0.0' }
            configurations.compileOnly.withDependencies { deps -> deps.clear() }
            configurations.compuktersAddonPlatform.withDependencies { deps ->
                deps.clear()
                deps.add(project.dependencies.create(files('platform.cpb')))
            }
            dependencies { compuktersGuestDependencies files('prototype.cagb') }
            """.trimIndent(),
        )
        val source = root.resolve("src/contract/kotlin/fixture/Engine.kt")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            """
            package fixture
            import ru.lazyhat.compukters.api.contracts.*
            import kotlin.math.Vector3d
            import prototype.flight.Velocity
            @GuestDevice("fixture:engine") interface Engine {
                @GuestCall fun aim(direction: Vector3d): Unit
                fun direction(v: Velocity): Vector3d = v.direction()
            }
            fun use(engine: Engine) { engine.aimCommand(Vector3d(1.0, 0.0, 0.0)) }
            """.trimIndent(),
        )
        val settings = GradleSettings.getInstance(project)
        val gradleSdk = JavaSdk.getInstance().createJdk("Guest Gradle Test JDK", System.getProperty("java.home"), false)
        registeredSdk = gradleSdk
        WriteAction.run<RuntimeException> { ProjectJdkTable.getInstance().addJdk(gradleSdk) }
        settings.serviceDirectoryPath = root.resolve("gradle-home").toString()
        settings.isOfflineWork = true
        settings.linkProject(
            GradleProjectSettings().apply {
                externalProjectPath = root.toString()
                distributionType = DistributionType.LOCAL
                gradleHome = propertyPath("guestGradleHome").toString()
                gradleJvm = gradleSdk.name
                isResolveModulePerSourceSet = true
            },
        )
        importProject(root)
        val registry = project.getService(GuestProjectRegistry::class.java)
        waitUntil("native module imported") {
            registry.configurations().isNotEmpty() &&
                registry.configurations().keys.all { registry.snapshot(it)?.loading == false }
        }
        val name = registry.configurations().keys.single()
        val snapshot = requireNotNull(registry.snapshot(name))
        assertEquals(snapshot.problems.toString(), emptyList<String>(), snapshot.problems)
        assertNotNull(snapshot.bundle)
        assertFalse("Import must not allocate ABI IDs", Files.exists(root.resolve("src/contract/addon.lock")))
        assertFalse("Import must not compile the addon", Files.exists(root.resolve("build/compukters-addon")))

        importProject(root)
        waitUntil("Gradle reimport refreshes the native module") {
            registry.snapshot(name)?.let { !it.loading && it.bundle != null && it.generation > snapshot.generation } == true
        }
        assertEquals(listOf(name), registry.configurations().keys.toList())

        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        myFixture.configureFromExistingVirtualFile(file)
        val ktFile = myFixture.file as KtFile
        val nativeModule = requireNotNull(ModuleUtilCore.findModuleForFile(file, project))
        assertEquals(name, nativeModule.name)
        assertEquals("Guest: active", GuestStatusWidget(project).getText())
        assertTrue(
            "Native Gradle import configures Kotlin without a JVM stdlib",
            isModuleConfigured(ModuleSourceRootGroup(nativeModule, emptyList())),
        )
        assertEquals(
            ConfigureKotlinStatus.NON_APPLICABLE,
            GuestKotlinProjectConfigurator().getStatus(ModuleSourceRootGroup(myModule, emptyList())),
        )
        allowAnalysisOnEdt {
            runReadActionBlocking {
                assertEquals(CompuktersPlatforms.default, KaModuleProvider.getModule(project, ktFile, null).targetPlatform)
                analyze(ktFile) {
                    val errors =
                        ktFile
                            .collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                            .filter { it.severity.name == "ERROR" }
                    assertTrue(errors.joinToString { "${it.factoryName}: ${it.defaultMessage}" }, errors.isEmpty())
                }
            }
        }
        myFixture.editor.caretModel.moveToOffset(ktFile.text.indexOf("aimCommand") + 3)
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("Command completion: $items", "aimCommand" in items)
        assertNull("Host module must keep its JVM identity", registry.sourceFor(myModule))

        // Real VFS artifact change must invalidate native analysis without a Gradle reimport.
        Files.writeString(platform, "invalid platform")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(platform)?.refresh(false, false)
        waitUntil("invalid artifact reported") {
            registry.snapshot(name)?.let { !it.loading && it.problems.isNotEmpty() && it.bundle == null } == true
        }
        assertEquals(CompuktersPlatforms.default, registry.sourceFor(nativeModule)!!.targetPlatform)
        assertEquals("Guest: error", GuestStatusWidget(project).getText())
        Files.write(platform, Files.readAllBytes(propertyPath("guestBasePlatform")))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(platform)?.refresh(false, false)
        waitUntil("artifact restored") {
            registry.snapshot(name)?.let { !it.loading && it.problems.isEmpty() && it.bundle != null } == true
        }
        assertTrue(registry.snapshot(name)!!.generation > snapshot.generation)
        assertEquals("Guest: active", GuestStatusWidget(project).getText())

        // Removing the plugin on reload removes stale native module state.
        // A persisted entry may also outlive its module, with no removal event to deliver.
        registry.loadState(
            GuestRegistryState().apply {
                modules.putAll(registry.configurations())
                modules["stale-native-module"] =
                    GuestModuleConfiguration().apply {
                        externalRoot = root.toString()
                        projectDirectory = root.resolve("removed-addon").toString()
                    }
            },
        )
        Files.writeString(root.resolve("build.gradle"), "apply plugin: 'java'\n")
        importProject(root)
        waitUntil("removed native module reconciled") { registry.configurations().isEmpty() }
        assertEquals("Guest: inactive", GuestStatusWidget(project).getText())
        settings.unlinkExternalProject(root.toString())
    }

    private fun importProject(root: Path) {
        val result = CompletableFuture<DataNode<ProjectData>>()
        ExternalSystemUtil.refreshProject(
            root.toString(),
            ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withImportProjectData(false)
                .callback(
                    object : ExternalProjectRefreshCallback {
                        override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                            if (externalProject == null) {
                                result.completeExceptionally(IllegalStateException("No Gradle model"))
                            } else {
                                result.complete(externalProject)
                            }
                        }

                        override fun onFailure(
                            errorMessage: String,
                            errorDetails: String?,
                        ) {
                            result.completeExceptionally(IllegalStateException("$errorMessage\n$errorDetails"))
                        }
                    },
                ),
        )
        waitUntil("Gradle model resolution") { result.isDone }
        ProjectDataManager.getInstance().importData(result.join(), project)
    }

    private fun waitUntil(
        message: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
        while (!predicate()) {
            assertTrue("Timed out: $message", System.nanoTime() < deadline)
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun propertyPath(name: String): Path = Path.of(requireNotNull(System.getProperty("compukters.test.$name")) { name })
}
