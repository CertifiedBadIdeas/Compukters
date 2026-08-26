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

package ru.lazyhat.compukters.ide.analysis.k2

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import java.nio.file.Files
import java.nio.file.Path

internal class StandaloneFixture private constructor(
    private val sourceRoot: Path,
    private val disposable: Disposable,
    private val session: StandaloneAnalysisAPISession,
) : AutoCloseable {
    @OptIn(KaExperimentalApi::class)
    fun analyzeInitializerType(propertyName: String): String =
        ReadAction.compute<String, RuntimeException> {
            val file =
                session.modulesWithFiles.values
                    .flatten()
                    .single() as KtFile
            val property = file.declarations.filterIsInstance<KtProperty>().single { it.name == propertyName }
            val initializer = requireNotNull(property.initializer)

            analyze(initializer) {
                requireNotNull(initializer.expressionType).render(position = Variance.INVARIANT)
            }
        }

    override fun close() {
        session.application.runWriteAction { Disposer.dispose(disposable) }
        sourceRoot.toFile().deleteRecursively()
    }

    companion object {
        fun source(
            fileName: String,
            source: String,
        ): StandaloneFixture {
            val sourceRoot = Files.createTempDirectory("compukters-k2-smoke-")
            Files.writeString(sourceRoot.resolve(fileName), source)
            val disposable = Disposer.newDisposable("Compukters standalone K2 smoke fixture")
            val platform = JvmPlatforms.defaultJvmPlatform
            val standardLibraryPath =
                Path.of(
                    Unit::class.java
                        .protectionDomain
                        .codeSource
                        .location
                        .toURI(),
                )
            val session =
                buildStandaloneAnalysisAPISession(
                    projectDisposable = disposable,
                    unitTestMode = true,
                ) {
                    registerProjectService(
                        ModuleVisibilityManager::class.java,
                        CliModuleVisibilityManagerImpl(enabled = true),
                    )
                    buildKtModuleProvider {
                        this.platform = platform
                        addModule(
                            buildKtSourceModule {
                                this.platform = platform
                                moduleName = "smoke"
                                addSourceRoot(sourceRoot)
                                addRegularDependency(
                                    buildKtLibraryModule {
                                        this.platform = platform
                                        libraryName = "kotlin-stdlib"
                                        addBinaryRoot(standardLibraryPath)
                                    },
                                )
                                addRegularDependency(
                                    buildKtSdkModule {
                                        this.platform = platform
                                        libraryName = "jdk"
                                        addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                                    },
                                )
                            },
                        )
                    }
                }

            return StandaloneFixture(sourceRoot, disposable, session)
        }
    }
}
