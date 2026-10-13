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

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_25)) {
    "Compukters addons require Gradle on JDK 25; their Minecraft target uses Java 21."
}
apply(from = "gradle/compukters-sdk.settings.gradle.kts")
settings.pluginManagement.apply {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("dev.architectury.loom") version "1.17.491"
        id("architectury-plugin") version "3.5.169"
        id("org.jmailen.kotlinter") version "5.7.0"
    }
}
rootProject.name = "compukters-createaddition"
