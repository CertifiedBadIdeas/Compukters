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

package ru.lazyhat.compukters.gradle.addon;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

public final class CompuktersAddonPlugin implements Plugin<Project> {
    private static final Pattern IDENTITY = Pattern.compile("[a-z][a-z0-9_-]*");

    @Override
    public void apply(Project project) {
        String implementationVersion = getClass().getPackage().getImplementationVersion();
        String sdkVersion = implementationVersion != null ? implementationVersion : project.getRootProject().getVersion().toString();
        CompuktersAddonExtension extension =
                project.getExtensions().create("compuktersAddon", CompuktersAddonExtension.class);
        extension.getToolingCoordinate().convention("ru.lazyhat.compukters:compukters-addon-tooling:" + sdkVersion);
        extension.getPlatformCoordinate().convention("ru.lazyhat.compukters:compukters-guest-platform:" + sdkVersion + "@cpb");
        extension.getApiCoordinate().convention("ru.lazyhat.compukters:compukters-addon-api-neoforge-1.21.1:" + sdkVersion);

        Configuration tooling = project.getConfigurations().create("compuktersAddonTooling", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
        });
        Configuration platform = project.getConfigurations().create("compuktersAddonPlatform", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
        });
        var sourceRoot = project.getLayout().getProjectDirectory().dir("src/compuktersAddon/kotlin");
        var abiLock = project.getLayout().getProjectDirectory().file("src/compuktersAddon/addon.lock");
        var descriptor = project.getLayout().getBuildDirectory().file("compukters-addon/addon.api");
        var bundle = project.getLayout().getBuildDirectory().file("compukters-addon/" + project.getName() + ".cagb");
        var generatedHostRoot = project.getLayout().getBuildDirectory().dir("generated/sources/compuktersAddon/kotlin");
        var generatedHost = generatedHostRoot.map(root -> root.file("GeneratedAddonHostContract.kt"));

        TaskProvider<?> generateDescriptor = project.getTasks().register("generateCompuktersAddonDescriptor", task -> {
            task.setGroup("build");
            task.setDescription("Generates the internal Compukters addon descriptor from the public Gradle DSL.");
            task.getInputs().property("addon", extension.getAddon());
            task.getInputs().property("module", extension.getModule());
            task.getInputs().property("moduleVersion", extension.getModuleVersion());
            task.getInputs().property("dependencies", extension.getDependencies());
            task.getInputs().property("capabilities", project.provider(() -> extension.getCapabilities().stream()
                    .map(capability -> capability.getName() + ":" + capability.getAbiMajor().get() + ":"
                            + capability.getAbiMinor().get() + ":" + capability.getBindingOwner().getOrElse(""))
                    .sorted()
                    .toList()));
            task.getOutputs().file(descriptor);
            task.doLast(ignored -> {
                String addon = validatedIdentity(extension.getAddon().get(), "addon");
                String module = validatedIdentity(extension.getModule().get(), "module");
                if (extension.getCapabilities().isEmpty()) {
                    throw new IllegalArgumentException("compuktersAddon must declare at least one capability");
                }
                StringBuilder content = new StringBuilder();
                content.append("addon ").append(addon).append('\n');
                content.append("module ").append(addon).append(':').append(module).append('\n');
                content.append("version ").append(extension.getModuleVersion().get()).append('\n');
                content.append("dependencies ").append(String.join(" ", extension.getDependencies().get())).append('\n');
                extension.getCapabilities().forEach(capability -> {
                    content.append("capability ").append(addon).append(' ')
                            .append(validatedIdentity(capability.getName(), "capability")).append(' ')
                            .append(capability.getAbiMajor().get()).append(' ')
                            .append(capability.getAbiMinor().get());
                    if (capability.getBindingOwner().isPresent()) {
                        content.append(' ').append(capability.getBindingOwner().get());
                    }
                    content.append('\n');
                });
                var output = descriptor.get().getAsFile();
                if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) {
                    throw new IllegalStateException("Cannot create " + output.getParent());
                }
                try {
                    java.nio.file.Files.writeString(output.toPath(), content);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        });

        TaskProvider<JavaExec> assemble = project.getTasks().register("assembleCompuktersAddon", JavaExec.class, task -> {
            task.setGroup("build");
            task.setDescription("Builds the deterministic Compukters addon Guest Kotlin bundle.");
            task.dependsOn(generateDescriptor);
            task.setClasspath(tooling);
            task.getMainClass().set("ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt");
            task.getInputs().dir(sourceRoot);
            task.getInputs().file(descriptor);
            task.getInputs().file(abiLock);
            task.getInputs().files(platform);
            task.getOutputs().file(bundle);
            task.getOutputs().file(generatedHost);
            task.doFirst(ignored -> task.setArgs(builderArguments(platform, sourceRoot.getAsFile().getAbsolutePath(),
                    descriptor.get().getAsFile().getAbsolutePath(), abiLock.getAsFile().getAbsolutePath(),
                    bundle.get().getAsFile().getAbsolutePath(), generatedHost.get().getAsFile().getAbsolutePath(), false)));
        });
        project.getTasks().register("updateCompuktersAddonAbiLock", JavaExec.class, task -> {
            task.setGroup("build setup");
            task.setDescription("Updates the checked-in Compukters addon ABI lock after an intentional API change.");
            task.dependsOn(generateDescriptor);
            task.setClasspath(tooling);
            task.getMainClass().set("ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt");
            task.getInputs().dir(sourceRoot);
            task.getInputs().file(descriptor);
            task.getInputs().files(platform);
            task.getOutputs().file(abiLock);
            var validationBundle = project.getLayout().getBuildDirectory()
                    .file("compukters-addon/" + project.getName() + "-abi-lock-update.cagb");
            task.getOutputs().file(validationBundle);
            task.doFirst(ignored -> task.setArgs(builderArguments(platform, sourceRoot.getAsFile().getAbsolutePath(),
                    descriptor.get().getAsFile().getAbsolutePath(), abiLock.getAsFile().getAbsolutePath(),
                    validationBundle.get().getAsFile().getAbsolutePath(), null, true)));
        });

        Configuration consumableBundle = project.getConfigurations().create("compuktersAddonBundle", configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
        });
        project.getArtifacts().add(consumableBundle.getName(), bundle, artifact -> {
            artifact.builtBy(assemble);
            artifact.setType("cagb");
        });

        project.afterEvaluate(ignored -> {
            project.getDependencies().add(tooling.getName(), extension.getToolingCoordinate().get());
            project.getDependencies().add(platform.getName(), extension.getPlatformCoordinate().get());
            Configuration apiClasspath = project.getConfigurations().findByName("compileOnly");
            if (apiClasspath != null) {
                project.getDependencies().add(apiClasspath.getName(), extension.getApiCoordinate().get());
            }
        });
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> {
            Object kotlin = project.getExtensions().getByName("kotlin");
            Object sourceSets = invoke(kotlin, "getSourceSets");
            Object main = invoke(sourceSets, "getByName", "main");
            Object kotlinSources = invoke(main, "getKotlin");
            invoke(kotlinSources, "srcDir", generatedHostRoot);
            project.getTasks().named("compileKotlin").configure(task -> task.dependsOn(assemble));
        });
        project.getTasks().matching(task -> task.getName().equals("processResources") && task instanceof AbstractCopyTask)
                .configureEach(task -> {
                    AbstractCopyTask copy = (AbstractCopyTask) task;
                    copy.dependsOn(assemble);
                    copy.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                    copy.from(bundle, spec -> {
                        spec.into("META-INF/compukters/addons");
                        spec.rename(ignored -> extension.getAddon().get() + "-" + extension.getModule().get() + ".cagb");
                    });
                });
        project.getTasks().matching(task -> task.getName().equals("check"))
                .configureEach(task -> task.dependsOn(assemble));
    }

    private static List<String> builderArguments(
            Configuration platform,
            String sources,
            String descriptor,
            String lock,
            String output,
            String hostOutput,
            boolean updateLock) {
        List<String> arguments = new ArrayList<>(List.of(
                "--platform-input", platform.getSingleFile().getAbsolutePath(),
                "--sources", sources,
                "--descriptor", descriptor,
                "--abi-lock", lock,
                "--output", output));
        if (hostOutput != null) {
            arguments.addAll(List.of("--host-output", hostOutput));
        }
        if (updateLock) {
            arguments.addAll(List.of("--update-abi-lock", "true"));
        }
        return arguments;
    }

    private static String validatedIdentity(String value, String kind) {
        if (!IDENTITY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Compukters addon " + kind + " must match [a-z][a-z0-9_-]*: " + value);
        }
        return value;
    }

    private static Object invoke(Object receiver, String name, Object... arguments) {
        var method = java.util.Arrays.stream(receiver.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot call " + name + " on " + receiver.getClass().getName()));
        try {
            return method.invoke(receiver, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot call " + name + " on " + receiver.getClass().getName(), exception);
        }
    }
}
