/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.gradle.addon;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import ru.lazyhat.compukters.gradle.model.DefaultGuestProjectModel;
import ru.lazyhat.compukters.gradle.model.GuestProjectModel;

/** Resolves only declared Guest inputs. Host classpaths never enter the Guest model. */
public final class GuestProjectModelBuilder implements ToolingModelBuilder {
    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals(GuestProjectModel.class.getName());
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        var extension = project.getExtensions().findByType(CompuktersAddonExtension.class);
        if (extension == null) return null;
        List<String> problems = new ArrayList<>();
        String addon = extension.getId().getOrNull();
        if (addon == null) {
            addon = "";
            problems.add("Register the addon identity in compuktersAddon { register(\"addon\") }.");
        }
        var platform = resolve(project.getConfigurations().getByName("compuktersAddonPlatform"), problems);
        if (platform.size() != 1) problems.add("Guest analysis requires exactly one Compukters platform bundle.");
        var dependencies = resolve(project.getConfigurations().getByName("compuktersGuestDependencies"), problems);
        return new DefaultGuestProjectModel(
                addon,
                project.getPath(),
                project.getProjectDir(),
                List.of(project.file("src/contract/kotlin")),
                platform,
                dependencies,
                List.of(project.getLayout().getBuildDirectory().dir("generated/sources/compuktersGuest/kotlin").get().getAsFile()),
                project.getLayout().getBuildDirectory().file("compukters-addon/" + project.getName() + ".cagb").get().getAsFile(),
                (project.getPath().equals(":") ? "" : project.getPath()) + ":assembleCompuktersAddon",
                problems);
    }

    private static List<File> resolve(Configuration configuration, List<String> problems) {
        var artifacts = configuration.getIncoming().artifactView(view -> view.setLenient(true)).getArtifacts();
        artifacts.getFailures().forEach(failure -> problems.add(configuration.getName() + ": " + failure.getMessage()));
        var files = artifacts.getArtifactFiles().getFiles().stream()
                .map(File::getAbsoluteFile)
                .sorted(Comparator.comparing(File::getPath))
                .toList();
        files.stream().filter(file -> !file.isFile()).forEach(file ->
                problems.add(configuration.getName() + ": artifact is missing: " + file));
        return files;
    }
}
