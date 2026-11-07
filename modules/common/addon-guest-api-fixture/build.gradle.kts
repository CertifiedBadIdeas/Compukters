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
    id("addon-guest-api-convention")
}

dependencies {
    implementation(projects.addonApi)
    testImplementation(kotlin("test"))
}

tasks.processResources {
    dependsOn("assembleAddonGuestApiBundle")
    from(layout.buildDirectory.file("addon-guest-api/${project.name}.cagb")) {
        into("META-INF/compukters/addons")
        rename { "fixture.cagb" }
    }
}

// A separate artifact exercises the public declarative builder through IDE metadata queries.
val declarativeFixtureRoot = layout.projectDirectory.dir("src/declarativeGuestApi")
val declarativeFixtureBundle = layout.buildDirectory.file("declarative-addon/declarative.cagb")
val assembleDeclarativeAddonGuestApiFixture = tasks.register<JavaExec>("assembleDeclarativeAddonGuestApiFixture") {
    dependsOn(tasks.named("fixtureBuilderClasses"))
    classpath = sourceSets["fixtureBuilder"].runtimeClasspath
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt"
    val platform = configurations["addonGuestApiBasePlatform"].elements.map { it.single().asFile }
    val guestOutput = layout.buildDirectory.dir("declarative-addon/guest")
    val lock = layout.buildDirectory.file("declarative-addon/addon.lock")
    inputs.dir(declarativeFixtureRoot)
    inputs.file(platform)
    outputs.file(declarativeFixtureBundle)
    outputs.dir(guestOutput)
    outputs.file(lock)
    args("--update-abi-lock", "true")
    argumentProviders.add(
        fileOptionArguments(
            "--platform-input" to platform,
            "--contracts" to declarativeFixtureRoot.dir("contracts"),
            "--descriptor" to declarativeFixtureRoot.file("addon.api"),
            "--guest-output" to guestOutput,
            "--abi-lock" to lock,
            "--output" to declarativeFixtureBundle,
        ),
    )
}
val declarativeAddonGuestApiBundle = configurations.create("declarativeAddonGuestApiBundle") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(declarativeAddonGuestApiBundle.name, declarativeFixtureBundle) {
        builtBy(assembleDeclarativeAddonGuestApiFixture)
        type = "cagb"
    }
}
