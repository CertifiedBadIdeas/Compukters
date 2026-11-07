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
val declarativeHostRoot = layout.buildDirectory.dir("generated/sources/declarativeHost/kotlin")
val declarativeHostSource = declarativeHostRoot.map { it.file("DeclarativeHost.kt") }
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
    outputs.file(declarativeHostSource)
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
            "--host-output" to declarativeHostSource,
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

kotlin.sourceSets.named("main") { kotlin.srcDir(declarativeHostRoot) }
tasks.named("compileKotlin") { dependsOn(assembleDeclarativeAddonGuestApiFixture) }

// FFM is a Java 25 test leaf; the reusable fixture and ordinary tests stay Java 21.
val nativeTest = sourceSets.create("nativeTest")
nativeTest.compileClasspath += sourceSets.main.get().output
nativeTest.runtimeClasspath += sourceSets.main.get().output
configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
dependencies {
    add(nativeTest.implementationConfigurationName, projects.compilerK2)
    add(nativeTest.implementationConfigurationName, projects.compilerClient)
    add(nativeTest.implementationConfigurationName, projects.workerClient)
    add(nativeTest.implementationConfigurationName, projects.projectFiles)
    add(nativeTest.implementationConfigurationName, projects.nativeRuntimeFfm)
}
listOf(nativeTest.compileClasspathConfigurationName, nativeTest.runtimeClasspathConfigurationName).forEach { name ->
    configurations[name].attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
val nativeJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>(nativeTest.getCompileTaskName("kotlin")) {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    kotlinJavaToolchain.toolchain.use(nativeJava)
}
tasks.named<JavaCompile>(nativeTest.compileJavaTaskName) {
    sourceCompatibility = "25"
    targetCompatibility = "25"
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
val nativeLibrary = rootProject.layout.projectDirectory.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
tasks.register<Test>("nativeIntegrationTest") {
    val workerJar = project(":compiler-k2").tasks.named<Jar>("jar")
    group = "verification"
    description = "Executes generated addon arguments, records and batches through the pinned native VM."
    dependsOn(rootProject.tasks.named("cargoBuildCompukterFfi"), workerJar, assembleDeclarativeAddonGuestApiFixture)
    testClassesDirs = nativeTest.output.classesDirs
    classpath = nativeTest.runtimeClasspath
    javaLauncher.set(nativeJava)
    useJUnitPlatform()
    filter.includeTestsMatching("*DeclarativeNativeIntegrationTest")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.file(nativeLibrary)
    inputs.file(declarativeFixtureBundle)
    val platformInput = configurations["addonGuestApiBasePlatform"].singleFile
    inputs.file(platformInput)
    systemProperty("compukters.platform.bundle", platformInput.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    systemProperty("compukters.worker.jar", workerJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("compukters.declarative.bundle", declarativeFixtureBundle.get().asFile.absolutePath)
    systemProperty("compukter.ffi.library", nativeLibrary.asFile.absolutePath)
}
