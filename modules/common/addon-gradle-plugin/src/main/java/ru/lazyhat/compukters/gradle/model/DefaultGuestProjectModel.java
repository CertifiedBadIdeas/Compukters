/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.gradle.model;

import java.io.File;
import java.io.Serializable;
import java.util.List;

/** Immutable, compiler-independent Tooling API payload. Discovery does not compile source. */
public final class DefaultGuestProjectModel implements GuestProjectModel, Serializable {
    private static final long serialVersionUID = 1L;

    private final String addonId;
    private final String projectPath;
    private final File projectDirectory;
    private final List<File> sourceDirectories;
    private final List<File> platformFiles;
    private final List<File> dependencyBundles;
    private final List<File> generatedSourceDirectories;
    private final File bundleFile;
    private final String compileTaskPath;
    private final List<String> problems;

    public DefaultGuestProjectModel(
            String addonId,
            String projectPath,
            File projectDirectory,
            List<File> sourceDirectories,
            List<File> platformFiles,
            List<File> dependencyBundles,
            List<File> generatedSourceDirectories,
            File bundleFile,
            String compileTaskPath,
            List<String> problems) {
        this.addonId = addonId;
        this.projectPath = projectPath;
        this.projectDirectory = projectDirectory;
        this.sourceDirectories = List.copyOf(sourceDirectories);
        this.platformFiles = List.copyOf(platformFiles);
        this.dependencyBundles = List.copyOf(dependencyBundles);
        this.generatedSourceDirectories = List.copyOf(generatedSourceDirectories);
        this.bundleFile = bundleFile;
        this.compileTaskPath = compileTaskPath;
        this.problems = List.copyOf(problems);
    }

    @Override public int getModelVersion() { return CURRENT_VERSION; }
    @Override public String getAddonId() { return addonId; }
    @Override public String getProjectPath() { return projectPath; }
    @Override public File getProjectDirectory() { return projectDirectory; }
    @Override public List<File> getSourceDirectories() { return sourceDirectories; }
    @Override public List<File> getPlatformFiles() { return platformFiles; }
    @Override public List<File> getDependencyBundles() { return dependencyBundles; }
    @Override public List<File> getGeneratedSourceDirectories() { return generatedSourceDirectories; }
    @Override public File getBundleFile() { return bundleFile; }
    @Override public String getCompileTaskPath() { return compileTaskPath; }
    @Override public List<String> getProblems() { return problems; }
}
