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

plugins {
    alias(libs.plugins.v1211)
    alias(libs.plugins.commonConvention)
}

architectury {
    common("neoforge")
}

repositories {
    maven("https://maven.createmod.net") {
        name = "Create"
    }
    maven("https://maven.ithundxr.dev/snapshots") {
        name = "Registrate"
    }
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven") {
        name = "NeoForgeConfigApiPort"
    }
}

dependencies {
    implementation(projects.addonGuestApi)
    implementation(projects.v1211Common)
    modImplementation(libs.create.v1211)
}

val createKineticsGuestApiBundle = configurations.create("createKineticsGuestApiBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(
        createKineticsGuestApiBundle.name,
        project(path = projects.guestPlatform.path, configuration = "createKineticsGuestApiBundle"),
    )
}

tasks.processResources {
    from(createKineticsGuestApiBundle) {
        into("META-INF/compukters/addons")
    }
}
