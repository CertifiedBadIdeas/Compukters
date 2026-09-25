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

val guestSources = rootProject.layout.projectDirectory.dir("../../modules/common/guest-platform/src/platform")
val modVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../../gradle.properties")).asText.map { properties ->
    Regex("(?m)^version\\s*=\\s*(\\S+)").find(properties)?.groupValues?.get(1)
        ?: error("Missing version in gradle.properties")
}

dokka {
    modulePath.set("guest")
    dokkaGeneratorIsolation.set(
        ProcessIsolation {
            systemProperties.put("org.jetbrains.dokka.analysis.allowKotlinPackage", "true")
        },
    )
    dokkaSourceSets.create("guest") {
        sourceRoots.from(guestSources)
        classpath.setFrom(files())
        enableKotlinStdLibDocumentationLink.set(false)
        enableJdkDocumentationLink.set(false)
        sourceLink {
            localDirectory.set(guestSources.asFile)
            remoteUrl("https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform")
            remoteLineSuffix.set("#L")
        }
    }
    dokkaPublications.html {
        moduleName.set("Guest Kotlin")
        moduleVersion.set(modVersion)
    }
}
