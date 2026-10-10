/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

val createSources = rootProject.layout.projectDirectory.dir("../addons/create/src/compuktersAddon/kotlin")
val addonRevision = providers.exec {
    commandLine("git", "-C", rootProject.file("../addons/create"), "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val addonVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../addons/create/gradle.properties")).asText.map { properties ->
    Regex("(?m)^addonVersion\\s*=\\s*(\\S+)").find(properties)?.groupValues?.get(1)
        ?: error("Missing addonVersion in addons/create/gradle.properties")
}

dokka {
    modulePath.set("create")
    dokkaSourceSets.create("create") {
        displayName.set("Compukters")
        sourceRoots.from(createSources)
        classpath.setFrom(files())
        enableKotlinStdLibDocumentationLink.set(false)
        enableJdkDocumentationLink.set(false)
        sourceLink {
            localDirectory.set(createSources.asFile)
            remoteUrl("https://github.com/CertifiedBadIdeas/Compukers-create/blob/${addonRevision.get()}/src/compuktersAddon/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
    dokkaPublications.html {
        moduleName.set("Create addon")
        moduleVersion.set(addonVersion)
    }
}
