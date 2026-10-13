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

// Optional development mods. Comment out a dependency to remove that mod.
// Voxy needs Connector, Forgified Fabric API and Sodium; disable Voxy first
// when disabling any of those dependencies. Comment out the apply(from = ...)
// in build.gradle.kts to disable the entire group.
repositories {
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    maven("https://maven.sinytra.org/") {
        content { includeGroupByRegex("org\\.sinytra.*") }
    }
    ivy {
        name = "VoxyNeoForgeReleases"
        url = uri("https://github.com/realBritakee/voxy-neoforge/releases/download")
        patternLayout { artifact("[revision]/[artifact]-[revision]-NeoForge-1.21.1.[ext]") }
        metadataSources { artifact() }
        content { includeModule("me.cortex.voxy.neoforge", "voxy") }
    }
}

dependencies {
    // These jars contain early ModLauncher services, so runtimeOnly is insufficient.
    "forgeRuntimeLibrary"("org.sinytra:connector:2.0.0-beta.17+1.21.1:full") { isTransitive = false }
    "forgeRuntimeLibrary"("maven.modrinth:sodium:uMOpc5uV") { isTransitive = false }

    // Use Maven metadata to include all API modules, remapped by Loom.
    "modRuntimeOnly"("org.sinytra.forgified-fabric-api:forgified-fabric-api:0.116.15+2.3.5+1.21.1")

    // This fork is still a Fabric jar: Connector must receive its intermediary names.
    "runtimeOnly"("me.cortex.voxy.neoforge:voxy:0.2.15") { isTransitive = false }

    // NeoForge Worldgen and its configuration UI dependencies (Modrinth version IDs).
    "modRuntimeOnly"("maven.modrinth:voxy-worldgen:PzNUZlWw") { isTransitive = false } // 2.4.2
    "modRuntimeOnly"("maven.modrinth:cloth-config:izKINKFg") { isTransitive = false } // 15.0.140
    "modRuntimeOnly"("maven.modrinth:better-modlist:ZID4kkto") { isTransitive = false } // 21.1.4
}
