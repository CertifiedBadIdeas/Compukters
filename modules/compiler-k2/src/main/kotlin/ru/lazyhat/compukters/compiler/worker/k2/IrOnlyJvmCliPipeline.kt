/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.FrontendFilesForPluginsGenerationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.PipelineContext
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.config.phaser.CompilerPhase
import org.jetbrains.kotlin.util.PerformanceManager

/** Runs through IR extensions but deliberately has no JVM backend or output-writing phase. */
internal class IrOnlyJvmCliPipeline : AbstractCliPipeline<K2JVMCompilerArguments>() {
    override val defaultPerformanceManager: PerformanceManager = K2JVMCompiler().defaultPerformanceManager

    override fun createCompoundPhase(
        arguments: K2JVMCompilerArguments,
    ): CompilerPhase<PipelineContext, ArgumentsPipelineArtifact<K2JVMCompilerArguments>, *> =
        JvmConfigurationPipelinePhase then
            JvmFrontendPipelinePhase then
            FrontendFilesForPluginsGenerationPipelinePhase() then
            JvmFir2IrPipelinePhase
}
