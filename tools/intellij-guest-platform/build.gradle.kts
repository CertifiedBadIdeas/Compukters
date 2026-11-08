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

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jmailen.kotlinter") version "5.7.0"
}

group = "ru.lazyhat.compukters"
version = "0.2.1-dev"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideaHome"))
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// Compile the shared platform against the IDE compiler. Never package the worker's K2 distribution.
val common = file("../../modules/common")
sourceSets.main {
    java.srcDir(common.resolve("addon-gradle-plugin/src/main/java/ru/lazyhat/compukters/gradle/model"))
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        optIn.addAll("org.jetbrains.kotlin.analysis.api.KaPlatformInterface", "org.jetbrains.kotlin.analysis.api.KaExperimentalApi")
    }
    sourceSets.main {
        kotlin.srcDir(common.resolve("platform-bundle/src/main/kotlin"))
        kotlin.srcDir(common.resolve("addon-guest-api/src/main/kotlin"))
        kotlin.srcDir(common.resolve("platform-k2/src/main/kotlin/ru/lazyhat/compukters/platform/k2/contracts"))
    }
}
tasks.compileKotlin {
    source(
        listOf("ImmutableBytes", "Sha256").map {
            common.resolve("worker-client/src/main/kotlin/ru/lazyhat/compukters/worker/value/$it.kt")
        },
        listOf("CompuktersPlatform", "CompuktersMetadataSymbolProvider").map {
            common.resolve("platform-k2/src/main/kotlin/ru/lazyhat/compukters/platform/k2/$it.kt")
        },
    )
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
intellijPlatform {
    pluginConfiguration {
        id = "ru.lazyhat.compukters.guest-platform"
        name = "Compukters Guest Kotlin"
        ideaVersion { sinceBuild = "261.27258.136"; untilBuild = "261.27258.136" }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
tasks.test {
    systemProperty("idea.load.plugins.id", "ru.lazyhat.compukters.guest-platform,org.jetbrains.kotlin,com.intellij.java,com.intellij.gradle")
    systemProperty("compukters.prototype.platform", providers.gradleProperty("guestPlatform").get())
    systemProperty("compukters.prototype.fixture", file("fixture").absolutePath)
    listOf("guestGradlePluginJar", "guestGradleHome", "guestBasePlatform", "guestDependency").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty("compukters.test.$name", it) }
    }
    maxHeapSize = "3g"
}
