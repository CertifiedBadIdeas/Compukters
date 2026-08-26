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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Path

internal class K2ProjectEnvironment private constructor(
    private val disposable: Disposable,
    val session: StandaloneAnalysisAPISession,
) : AutoCloseable {
    override fun close() {
        session.application.runWriteAction { Disposer.dispose(disposable) }
    }

    companion object {
        fun create(
            sourceRoot: Path,
            standardLibrary: Path,
            binaryRoots: List<Path>,
            jdkHome: Path,
        ): K2ProjectEnvironment {
            val disposable = Disposer.newDisposable("Compukters analysis snapshot")
            return try {
                val platform = JvmPlatforms.defaultJvmPlatform
                val session =
                    buildStandaloneAnalysisAPISession(disposable, unitTestMode = false) {
                        StandaloneProgressManager.register(this)
                        registerProjectService(
                            ModuleVisibilityManager::class.java,
                            CliModuleVisibilityManagerImpl(enabled = true),
                        )
                        buildKtModuleProvider {
                            this.platform = platform
                            addModule(
                                buildKtSourceModule {
                                    this.platform = platform
                                    moduleName = "compukters-snapshot"
                                    addSourceRoot(sourceRoot)
                                    addRegularDependency(
                                        buildKtLibraryModule {
                                            this.platform = platform
                                            libraryName = "kotlin-stdlib"
                                            addBinaryRoot(standardLibrary)
                                        },
                                    )
                                    binaryRoots.forEachIndexed { index, root ->
                                        addRegularDependency(
                                            buildKtLibraryModule {
                                                this.platform = platform
                                                libraryName = "guest-bundle-$index"
                                                addBinaryRoot(root)
                                            },
                                        )
                                    }
                                    addRegularDependency(
                                        buildKtSdkModule {
                                            this.platform = platform
                                            libraryName = "jdk"
                                            addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                                        },
                                    )
                                },
                            )
                        }
                    }
                K2ProjectEnvironment(disposable, session)
            } catch (exception: Exception) {
                Disposer.dispose(disposable)
                throw exception
            }
        }
    }
}
