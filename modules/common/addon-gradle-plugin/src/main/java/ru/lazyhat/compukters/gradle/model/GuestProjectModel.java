/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.gradle.model;

import java.io.File;
import java.util.List;

/** Native Guest module description. Shared verbatim by the Gradle producer and IDE importer. */
public interface GuestProjectModel {
    int CURRENT_VERSION = 1;

    int getModelVersion();
    String getAddonId();
    String getProjectPath();
    File getProjectDirectory();
    List<File> getSourceDirectories();
    List<File> getPlatformFiles();
    List<File> getDependencyBundles();
    List<File> getGeneratedSourceDirectories();
    File getBundleFile();
    String getCompileTaskPath();
    List<String> getProblems();
}
