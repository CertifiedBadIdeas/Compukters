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

val propulsionSources = rootProject.layout.projectDirectory.dir("../addons/propulsion/src/compuktersAddon/kotlin")
val addonRevision = providers.exec {
    commandLine("git", "-C", rootProject.file("../addons/propulsion"), "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val addonVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../addons/propulsion/gradle.properties")).asText.map { properties ->
    Regex("(?m)^addonVersion\\s*=\\s*(\\S+)").find(properties)?.groupValues?.get(1)
        ?: error("Missing addonVersion in addons/propulsion/gradle.properties")
}

dokka {
    modulePath.set("propulsion")
    dokkaSourceSets.create("propulsion") {
        displayName.set("Compukters")
        sourceRoots.from(propulsionSources)
        classpath.setFrom(files())
        enableKotlinStdLibDocumentationLink.set(false)
        enableJdkDocumentationLink.set(false)
        sourceLink {
            localDirectory.set(propulsionSources.asFile)
            remoteUrl("https://github.com/CertifiedBadIdeas/Compukers-propulsion/blob/${addonRevision.get()}/src/compuktersAddon/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
    dokkaPublications.html {
        moduleName.set("Propulsion addon")
        moduleVersion.set(addonVersion)
    }
}
