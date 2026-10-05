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

val sableSources = rootProject.layout.projectDirectory.dir("../addons/sable/src/compuktersAddon/kotlin")
val addonVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../addons/sable/gradle.properties")).asText.map { properties ->
    Regex("(?m)^addonVersion\\s*=\\s*(\\S+)").find(properties)?.groupValues?.get(1)
        ?: error("Missing addonVersion in addons/sable/gradle.properties")
}

dokka {
    modulePath.set("sable")
    dokkaSourceSets.create("sable") {
        displayName.set("Compukters")
        sourceRoots.from(sableSources)
        classpath.setFrom(files())
        enableKotlinStdLibDocumentationLink.set(false)
        enableJdkDocumentationLink.set(false)
        sourceLink {
            localDirectory.set(sableSources.asFile)
            remoteUrl("https://github.com/CertifiedBadIdeas/Compukters/blob/dev/addons/sable/src/compuktersAddon/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
    dokkaPublications.html {
        moduleName.set("Sable addon")
        moduleVersion.set(addonVersion)
    }
}
