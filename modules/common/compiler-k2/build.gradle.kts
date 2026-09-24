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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.addonGuestApi)
    implementation(projects.compilerK2Engine)
    implementation(projects.compilerClient)
    implementation(projects.compilerArtifact)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler)
    testImplementation(projects.ideCore)
    testImplementation(kotlin("test"))
}

val compuktersPlatformBundle = configurations.create("compuktersPlatformBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val guestPlatformSources = configurations.create("guestPlatformSources") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val addonGuestApiFixtureBundle = configurations.create("addonGuestApiFixtureBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(
        compuktersPlatformBundle.name,
        project(path = ":guest-platform", configuration = "compuktersPlatformBundle"),
    )
    add(guestPlatformSources.name, project(path = ":guest-platform")) {
        isTransitive = false
    }
    add(
        addonGuestApiFixtureBundle.name,
        project(path = ":addon-guest-api-fixture", configuration = "addonGuestApiBundle"),
    )
}

tasks.processResources {
    from(compuktersPlatformBundle) {
        into("compukters-platform")
        rename { "compukters-platform.cpb" }
    }
}

application {
    mainClass = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val workerRuntimeClasspath = configurations.create("workerRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
    listOf(
        "org.jetbrains" to "annotations",
        "org.jetbrains.kotlin" to "kotlin-build-tools-api",
        "org.jetbrains.kotlin" to "kotlin-reflect",
        "org.jetbrains.kotlin" to "kotlin-script-runtime",
        "org.checkerframework" to "checker-qual",
        "com.google.errorprone" to "error_prone_annotations",
    ).forEach { (group, module) -> exclude(group = group, module = module) }
}

val workerPayloadDirectory = layout.buildDirectory.dir("worker-payload/content")
val pinnedKotlinVersion = libs.versions.kotlin.asProvider().get()
val platformBundleFile = compuktersPlatformBundle.elements.map { files -> files.single().asFile }

val prepareCompilerWorkerPayload = tasks.register<Sync>("prepareCompilerWorkerPayload") {
    dependsOn(tasks.jar)
    inputs.file(platformBundleFile)
    into(workerPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar) {
        into("lib")
    }
    from(workerRuntimeClasspath) {
        into("lib")
    }
    from(guestPlatformSources) {
        into("lib")
    }
    from(rootProject.layout.projectDirectory.file("licenses/project/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "Compukters-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.dir("licenses/kotlin/v2.4.10")) {
        into("META-INF/licenses/kotlin/v2.4.10")
    }
    from(rootProject.layout.projectDirectory.file("licenses/rust/generic-array-0.14.7-LICENSE.txt")) {
        into("META-INF/licenses/rust")
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }

    doLast {
        val root = workerPayloadDirectory.get().asFile.toPath()
        val libraryDirectory = root.resolve("lib")
        val files =
            Files.list(libraryDirectory).use { paths ->
                paths.sorted().map { path ->
                    val bytes = Files.readAllBytes(path)
                    Triple("lib/${path.fileName}", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
                }.toList()
            }
        val payloadDigest = MessageDigest.getInstance("SHA-256")
        fun digestField(value: String) {
            val bytes = value.toByteArray()
            payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            payloadDigest.update(bytes)
        }
        val platformBytes = Files.readAllBytes(platformBundleFile.get().toPath())
        check(platformBytes.size >= 40) { "Compukters platform bundle is truncated" }
        check(platformBytes.copyOfRange(0, 4).contentEquals("CPBF".encodeToByteArray())) {
            "invalid Compukters platform bundle magic"
        }
        val platformAbi =
            platformBytes
                .copyOfRange(8, 40)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val identityProperties =
            sortedMapOf(
                "artifactWriter" to "1",
                "codegenAbi" to "1",
                "compiler" to pinnedKotlinVersion,
                "language" to "2.4",
                "platformAbi" to platformAbi,
            )
        payloadDigest.update("Compukters worker payload v1\u0000".toByteArray())
        payloadDigest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        digestField("compiler")
        identityProperties.forEach { (name, value) ->
            digestField(name)
            digestField(value)
        }
        digestField(application.mainClass.get())
        files.forEach { (path, size, hash) ->
            digestField(path)
            payloadDigest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
            payloadDigest.update(hash)
        }
        val payloadHash = payloadDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest =
            buildString {
                appendLine("format=1")
                appendLine("kind=compiler")
                identityProperties.forEach { (name, value) -> appendLine("identity.$name=$value") }
                appendLine("mainClass=${application.mainClass.get()}")
                appendLine("payloadSha256=$payloadHash")
                files.forEach { (path, size, hash) ->
                    append("file=").append(path).append('\t').append(size).append('\t')
                    appendLine(hash.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
            }
        Files.writeString(root.resolve("worker.payload"), manifest)
    }
}

val compilerWorkerPayloadContent = configurations.create("compilerWorkerPayloadContent") {
    isCanBeConsumed = true
    isCanBeResolved = false
    description = "Prepared compiler worker payload directory for tooling bundle assembly."
}
artifacts.add(compilerWorkerPayloadContent.name, workerPayloadDirectory) {
    builtBy(prepareCompilerWorkerPayload)
}

val compilerWorkerPayload = tasks.register<Zip>("compilerWorkerPayload") {
    dependsOn(prepareCompilerWorkerPayload)
    from(workerPayloadDirectory)
    archiveFileName = "compiler-k2-worker.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyCompilerWorkerLicenses =
    tasks.register("verifyCompilerWorkerLicenses") {
        description = "Checks licenses and the library inventory in the packaged compiler worker."
        group = "verification"
        dependsOn(compilerWorkerPayload)
        inputs.file(compilerWorkerPayload.flatMap { it.archiveFile })
        inputs.file(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv"))
        doLast {
            val archive = compilerWorkerPayload.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                }
            listOf(
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "expected exactly one $required in ${archive.name}"
                }
            }

            val inventory = rootProject.file("licenses/distribution-components.tsv")
            check(inventory.isFile) { "distribution component inventory is missing: $inventory" }
            val expectedExternal =
                inventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-worker" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .sorted()
            val projectPrefixes =
                listOf(
                    "addon-guest-api-",
                    "compiler-artifact-",
                    "compiler-client-",
                    "compiler-k2-",
                    "compiler-k2-engine-",
                    "guest-platform-",
                    "platform-bundle-",
                    "platform-k2-",
                    "worker-client-",
                )
            val actualExternal =
                entries
                    .filter { it.startsWith("lib/") && it.endsWith(".jar") }
                    .map { it.removePrefix("lib/") }
                    .filterNot { name -> projectPrefixes.any(name::startsWith) }
                    .sorted()
            check(actualExternal == expectedExternal) {
                "compiler worker library inventory mismatch: expected $expectedExternal, found $actualExternal"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyCompilerWorkerLicenses)
}

val workerJar = tasks.jar.flatMap { it.archiveFile }

tasks.withType<Test>().configureEach {
    systemProperty("compukters.repository.root", rootProject.projectDir.absolutePath)
}

tasks.test {
    dependsOn(tasks.jar)
    filter.excludeTestsMatching("ru.lazyhat.compukters.compiler.worker.integration.*")
    inputs.file(workerJar)
    inputs.files(addonGuestApiFixtureBundle)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.addonGuestApiFixture", addonGuestApiFixtureBundle.singleFile.absolutePath)
    }
}

val kotlinSubsetConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt")
val blockingCallConformanceArtifact = layout.buildDirectory.file("generated/conformance/blocking-call.cpkt")
val transparentCallConformanceArtifact = layout.buildDirectory.file("generated/conformance/transparent-call.cpkt")
val tasksConformanceArtifact = layout.buildDirectory.file("generated/conformance/tasks.cpkt")
val channelConformanceArtifact = layout.buildDirectory.file("generated/conformance/channel.cpkt")
val timerConformanceArtifact = layout.buildDirectory.file("generated/conformance/timer.cpkt")
val whenConformanceArtifact = layout.buildDirectory.file("generated/conformance/when.cpkt")
val argvConformanceArtifact = layout.buildDirectory.file("generated/conformance/argv.cpkt")
val platformScalarConformanceArtifact = layout.buildDirectory.file("generated/conformance/platform-scalar.cpkt")
val redstoneConformanceArtifact = layout.buildDirectory.file("generated/conformance/redstone.cpkt")
val soundConformanceArtifact = layout.buildDirectory.file("generated/conformance/sound.cpkt")
val displayConformanceArtifact = layout.buildDirectory.file("generated/conformance/display.cpkt")
val intLoopsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-int-loops.cpkt")
val intArrayConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-int-array.cpkt")
val longConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-long.cpkt")
val genericFunctionsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-generic-functions.cpkt")
val genericCellConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-generic-cell.cpkt")
val floatConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-float.cpkt")
val dispatchConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-dispatch.cpkt")
val objectModelConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-object-model.cpkt")
val propertyAccessorsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-property-accessors.cpkt")
val abstractPropertiesConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-abstract-properties.cpkt")
val interfaceDefaultsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-interface-defaults.cpkt")
val interfaceSuperConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-interface-super.cpkt")
val classInitializationConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-class-initialization.cpkt")
val constructorDefaultsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-constructor-defaults.cpkt")
val adaptedConstructorsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-adapted-constructors.cpkt")
val mutableFieldsConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-mutable-fields.cpkt")
val functionValuesConformanceArtifact = layout.buildDirectory.file("generated/conformance/kotlin-function-values.cpkt")
val bootArtifact = layout.buildDirectory.file("generated/system/boot.cpkt")
val shellArtifact = layout.buildDirectory.file("generated/system/shell.cpkt")
val kotlincArtifact = layout.buildDirectory.file("generated/system/kotlinc.cpkt")
val editArtifact = layout.buildDirectory.file("generated/system/edit.cpkt")
val vmbenchArtifact = layout.buildDirectory.file("generated/system/vmbench.cpkt")
val vmbenchAgentArtifact = layout.buildDirectory.file("generated/system/vmbench-agent.cpkt")

val generateKotlinSubsetConformanceArtifact = tasks.register<Test>("generateKotlinSubsetConformanceArtifact") {
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*primitive char array lowers deterministically for exact utf16 materialization*")
    inputs.file(workerJar)
    outputs.file(kotlinSubsetConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.kotlinSubsetArtifact", kotlinSubsetConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateDispatchConformanceArtifact = tasks.register<Test>("generateDispatchConformanceArtifact") {
    description = "Compiles Guest class and interface dispatch for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*guest instance methods lower with deterministic owners flags and method ranges*")
    inputs.file(workerJar)
    outputs.file(dispatchConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.dispatchArtifact", dispatchConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateObjectModelConformanceArtifact = tasks.register<Test>("generateObjectModelConformanceArtifact") {
    description = "Compiles sealed, data, and enum Guest objects for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*guest object subset lowers sealed results data values enum identity and type branches*")
    inputs.file(workerJar)
    outputs.file(objectModelConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.objectArtifact", objectModelConformanceArtifact.get().asFile.absolutePath)
    }
}

val generatePropertyAccessorsConformanceArtifact = tasks.register<Test>("generatePropertyAccessorsConformanceArtifact") {
    description = "Compiles Guest class property accessors for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*computed and custom class accessors lower with backing fields and override dispatch*")
    inputs.file(workerJar)
    outputs.file(propertyAccessorsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.propertyAccessorsArtifact", propertyAccessorsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateAbstractPropertiesConformanceArtifact = tasks.register<Test>("generateAbstractPropertiesConformanceArtifact") {
    description = "Compiles abstract Guest class and interface properties for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*abstract class and interface properties lower to dispatched accessors without fields*")
    inputs.file(workerJar)
    outputs.file(abstractPropertiesConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.abstractPropertiesArtifact", abstractPropertiesConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateInterfaceDefaultsConformanceArtifact = tasks.register<Test>("generateInterfaceDefaultsConformanceArtifact") {
    description = "Compiles Guest interface default methods and accessors for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*interface defaults lower to methods and computed accessors*")
    inputs.file(workerJar)
    outputs.file(interfaceDefaultsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.interfaceDefaultsArtifact", interfaceDefaultsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateInterfaceSuperConformanceArtifact = tasks.register<Test>("generateInterfaceSuperConformanceArtifact") {
    description = "Compiles qualified Guest interface super calls for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*qualified interface super calls use direct default bodies*")
    inputs.file(workerJar)
    outputs.file(interfaceSuperConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.interfaceSuperArtifact", interfaceSuperConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateClassInitializationConformanceArtifact = tasks.register<Test>("generateClassInitializationConformanceArtifact") {
    description = "Compiles Guest class initialization for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*class body properties and init blocks lower in construction order*")
    inputs.file(workerJar)
    outputs.file(classInitializationConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.classInitializationArtifact", classInitializationConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateConstructorDefaultsConformanceArtifact = tasks.register<Test>("generateConstructorDefaultsConformanceArtifact") {
    description = "Compiles Guest primary constructor defaults for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*primary constructor defaults preserve argument order and earlier parameters*")
    inputs.file(workerJar)
    outputs.file(constructorDefaultsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.constructorDefaultsArtifact", constructorDefaultsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateAdaptedConstructorsConformanceArtifact = tasks.register<Test>("generateAdaptedConstructorsConformanceArtifact") {
    description = "Compiles adapted Guest constructor references for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*default adapted constructor references lower to managed function values*")
    inputs.file(workerJar)
    outputs.file(adaptedConstructorsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.adaptedConstructorsArtifact", adaptedConstructorsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateMutableFieldsConformanceArtifact = tasks.register<Test>("generateMutableFieldsConformanceArtifact") {
    description = "Compiles mutable Guest class fields for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*mutable constructor properties lower to instance field writes*")
    inputs.file(workerJar)
    outputs.file(mutableFieldsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.mutableFieldsArtifact", mutableFieldsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateFunctionValuesConformanceArtifact = tasks.register<Test>("generateFunctionValuesConformanceArtifact") {
    description = "Compiles Guest zero-argument function values for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*supported function values lower to managed closures and shared capture cells*")
    inputs.file(workerJar)
    outputs.file(functionValuesConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.functionValuesArtifact", functionValuesConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateTransparentCallConformanceArtifact = tasks.register<Test>("generateTransparentCallConformanceArtifact") {
    description = "Compiles an ordinary project call across VM-task blocking for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*ordinary project call resumes transparently across host blocking*")
    inputs.file(workerJar)
    outputs.file(transparentCallConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.transparentCallArtifact", transparentCallConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateTasksConformanceArtifact = tasks.register<Test>("generateTasksConformanceArtifact") {
    description = "Compiles cooperative Guest tasks for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*direct top level ordinary task lowers to spawn and join*")
    inputs.file(workerJar)
    outputs.file(tasksConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.tasksArtifact", tasksConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateChannelConformanceArtifact = tasks.register<Test>("generateChannelConformanceArtifact") {
    description = "Compiles VM-owned Guest channel handoff for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*top level IntChannel lowers to VM owned bounded handoff*")
    inputs.file(workerJar)
    outputs.file(channelConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.channelArtifact", channelConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateTimerConformanceArtifact = tasks.register<Test>("generateTimerConformanceArtifact") {
    description = "Compiles a server-tick Guest task delay for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*task tick sleep lowers to one asynchronous timer request*")
    inputs.file(workerJar)
    outputs.file(timerConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.timerArtifact", timerConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateBlockingCallConformanceArtifact = tasks.register<Test>("generateBlockingCallConformanceArtifact") {
    description = "Compiles an ordinary Kotlin main with a VM-blocking call for native runtime conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*ordinary main lowers trusted terminal wait as vm blocking*")
    inputs.file(workerJar)
    outputs.file(blockingCallConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.blockingCallArtifact", blockingCallConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateWhenConformanceArtifact = tasks.register<Test>("generateWhenConformanceArtifact") {
    description = "Compiles a bounded Kotlin when program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*bounded when lowers deterministically for vm execution*")
    inputs.file(workerJar)
    outputs.file(whenConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.whenArtifact", whenConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateArgvConformanceArtifact = tasks.register<Test>("generateArgvConformanceArtifact") {
    description = "Compiles a Kotlin Array<String> entry program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*string array entry lowers deterministically for vm argv conformance*")
    inputs.file(workerJar)
    outputs.file(argvConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.argvArtifact", argvConformanceArtifact.get().asFile.absolutePath)
    }
}

val generatePlatformScalarConformanceArtifact = tasks.register<Test>("generatePlatformScalarConformanceArtifact") {
    description = "Compiles a bounded native platform-scalar program for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*redstone side set preserves its bounded Int precondition*")
    inputs.file(workerJar)
    outputs.file(platformScalarConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.platformScalarArtifact", platformScalarConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateRedstoneConformanceArtifact = tasks.register<Test>("generateRedstoneConformanceArtifact") {
    description = "Compiles the deterministic redstone GPIO program for GameTest conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*redstone program lowers deterministically for vm conformance*")
    inputs.file(workerJar)
    outputs.file(redstoneConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.redstoneArtifact", redstoneConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateSoundConformanceArtifact = tasks.register<Test>("generateSoundConformanceArtifact") {
    description = "Compiles the deterministic sound program for GameTest conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*sound beep lowers deterministically to a blocking Boolean capability operation*")
    inputs.file(workerJar)
    outputs.file(soundConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.soundArtifact", soundConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateDisplayConformanceArtifact = tasks.register<Test>("generateDisplayConformanceArtifact") {
    description = "Compiles the deterministic text display program for GameTest conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*text display program lowers deterministically for GameTest*")
    inputs.file(workerJar)
    outputs.file(displayConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.displayArtifact", displayConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateIntLoopsConformanceArtifact = tasks.register<Test>("generateIntLoopsConformanceArtifact") {
    description = "Compiles allocation-free Kotlin Int loops for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*allocation free Int loops lower deterministically for vm execution*")
    inputs.file(workerJar)
    outputs.file(intLoopsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.intLoopsArtifact", intLoopsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateIntArrayConformanceArtifact = tasks.register<Test>("generateIntArrayConformanceArtifact") {
    description = "Compiles specialized Kotlin IntArray operations for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*specialized IntArray lowers deterministically for vm conformance*")
    inputs.file(workerJar)
    outputs.file(intArrayConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.intArrayArtifact", intArrayConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateLongConformanceArtifact = tasks.register<Test>("generateLongConformanceArtifact") {
    description = "Compiles Guest Kotlin Long operations and text output for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*Long arithmetic conversions comparisons and text lower for vm conformance*")
    inputs.file(workerJar)
    outputs.file(longConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.longArtifact", longConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateGenericFunctionsConformanceArtifact = tasks.register<Test>("generateGenericFunctionsConformanceArtifact") {
    description = "Compiles specialized Guest generic functions for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*generic identity specializes primitive and reference calls*")
    inputs.file(workerJar)
    outputs.file(genericFunctionsConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.genericFunctionsArtifact", genericFunctionsConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateGenericCellConformanceArtifact = tasks.register<Test>("generateGenericCellConformanceArtifact") {
    description = "Compiles a specialized Guest generic class for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*generic cell specializes field layout and preserves aliases*")
    inputs.file(workerJar)
    outputs.file(genericCellConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.genericCellArtifact", genericCellConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateFloatConformanceArtifact = tasks.register<Test>("generateFloatConformanceArtifact") {
    description = "Compiles Guest Kotlin Float operations and text output for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*Float arithmetic conversions comparisons and text lower for vm conformance*")
    inputs.file(workerJar)
    outputs.file(floatConformanceArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukter.vm.floatArtifact", floatConformanceArtifact.get().asFile.absolutePath)
    }
}

val generateShellArtifact = tasks.register<Test>("generateShellArtifact") {
    description = "Compiles the checked-in no-std Kotlin shell into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in shell compiles deterministically*")
    inputs.file(rootProject.file("system/programs/shell.kt"))
    inputs.file(rootProject.file("system/programs/shell/Lexer.kt"))
    inputs.file(workerJar)
    outputs.file(shellArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
    }
}

val generateBootArtifact = tasks.register<Test>("generateBootArtifact") {
    description = "Compiles the checked-in no-std Kotlin boot program into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in boot compiles deterministically with process intrinsic*")
    inputs.file(rootProject.file("system/programs/boot.kt"))
    inputs.file(workerJar)
    outputs.file(bootArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.boot.artifact", bootArtifact.get().asFile.absolutePath)
    }
}

val generateKotlincArtifact = tasks.register<Test>("generateKotlincArtifact") {
    description = "Compiles the checked-in no-std Kotlin compiler CLI into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in kotlinc compiles deterministically*")
    inputs.file(rootProject.file("system/programs/kotlinc.kt"))
    inputs.file(workerJar)
    outputs.file(kotlincArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.kotlinc.artifact", kotlincArtifact.get().asFile.absolutePath)
    }
}

val generateEditArtifact = tasks.register<Test>("generateEditArtifact") {
    description = "Compiles the checked-in no-std Kotlin editor into a deterministic Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in editor compiles deterministically*")
    inputs.file(rootProject.file("system/programs/edit.kt"))
    inputs.file(workerJar)
    outputs.file(editArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.edit.artifact", editArtifact.get().asFile.absolutePath)
    }
}

val generateVmbenchArtifact = tasks.register<Test>("generateVmbenchArtifact") {
    description = "Compiles the checked-in deterministic VM benchmark into a Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in vm benchmark compiles deterministically*")
    inputs.file(rootProject.file("system/programs/vmbench.kt"))
    inputs.file(rootProject.file("system/programs/vmbench-workload.kt"))
    inputs.file(workerJar)
    outputs.file(vmbenchArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.vmbench.artifact", vmbenchArtifact.get().asFile.absolutePath)
    }
}

val generateVmbenchAgentArtifact = tasks.register<Test>("generateVmbenchAgentArtifact") {
    description = "Compiles the checked-in headless VM benchmark agent into a Compukter Artifact."
    group = "build"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*checked in vm benchmark agent compiles deterministically*")
    inputs.file(rootProject.file("system/programs/vmbench-agent.kt"))
    inputs.file(rootProject.file("system/programs/vmbench-workload.kt"))
    inputs.file(workerJar)
    outputs.file(vmbenchAgentArtifact)
    doFirst {
        systemProperty("compukters.worker.jar", workerJar.get().asFile.absolutePath)
        systemProperty("compukters.vmbenchAgent.artifact", vmbenchAgentArtifact.get().asFile.absolutePath)
    }
}

val workerMeasurementReport = layout.buildDirectory.file("reports/worker/measurements.json")
val sharedToolingPayloadDirectory = project(":tooling-runtime").layout.buildDirectory.dir("tooling-bundle/content")

val forkedWorkerTest = tasks.register<Test>("forkedWorkerTest") {
    dependsOn(":tooling-runtime:prepareToolingRuntimeBundle")
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("ru.lazyhat.compukters.compiler.worker.integration.*")
    shouldRunAfter(tasks.test)
    inputs.dir(sharedToolingPayloadDirectory)
    outputs.file(workerMeasurementReport)
    doFirst {
        systemProperty("compukters.worker.payload", sharedToolingPayloadDirectory.get().asFile.absolutePath)
        systemProperty("compukters.worker.java", javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().executablePath)
        systemProperty("compukters.worker.test-classpath", sourceSets.test.get().runtimeClasspath.asPath)
        systemProperty("compukters.worker.measurement-report", workerMeasurementReport.get().asFile.absolutePath)
    }
}

tasks.check {
    dependsOn(forkedWorkerTest)
}

val compilerModuleNames =
    setOf(
        "kotlin-compiler",
        "kotlin-compiler-embeddable",
        "kotlin-scripting-compiler-embeddable",
        "kotlin-scripting-compiler-impl-embeddable",
    )
val nonWorkerIsolationChecks =
    rootProject.allprojects
        .filter {
            it != project &&
                it.path !in
                setOf(
                    ":compiler-client",
                    ":compiler-k2-engine",
                    ":ide-analysis-k2",
                    ":ide-kotlin-formatter",
                    ":platform-k2",
                )
        }
        .map { candidate ->
            candidate.tasks.register("assertNoK2CompilerRuntime") {
                doLast {
                    candidate.configurations.findByName("runtimeClasspath")?.takeIf { it.isCanBeResolved }?.let { runtime ->
                        val leaked = runtime.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.name in compilerModuleNames }
                        check(leaked.isEmpty()) { "${candidate.path} production runtime leaks compiler artifacts: $leaked" }
                    }
                }
            }
        }

val assertCompilerWorkerIsolation = tasks.register("assertCompilerWorkerIsolation") {
    dependsOn(prepareCompilerWorkerPayload, nonWorkerIsolationChecks, ":compiler-client:assertNoK2CompilerRuntime")
    doLast {
        val workerArtifacts = workerRuntimeClasspath.resolvedConfiguration.resolvedArtifacts
        val compilerVersions =
            workerArtifacts
                .filter { it.moduleVersion.id.name == "kotlin-compiler" }
                .map { it.moduleVersion.id.version }
                .toSet()
        check(compilerVersions == setOf(pinnedKotlinVersion)) {
            "kotlin-compiler must resolve exactly to $pinnedKotlinVersion, got $compilerVersions"
        }
        val forbiddenCompilerModules =
            workerArtifacts.filter {
                it.moduleVersion.id.name in
                    setOf(
                        "kotlin-compiler-embeddable",
                        "kotlin-scripting-compiler-embeddable",
                        "kotlin-scripting-compiler-impl-embeddable",
                    )
            }
        check(forbiddenCompilerModules.isEmpty()) {
            "compiler worker contains forbidden embeddable or scripting compiler artifacts: $forbiddenCompilerModules"
        }

        val registrarPath = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val registrars = zipTree(workerJar.get().asFile).matching { include(registrarPath) }.files
        check(registrars.isEmpty()) { "worker adapter jar must not duplicate the compiler engine registrar" }

        val payloadLibraries = workerPayloadDirectory.get().asFile.toPath().resolve("lib")
        val actualNames = Files.list(payloadLibraries).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
        val expectedNames =
            (workerRuntimeClasspath.files + guestPlatformSources.files + workerJar.get().asFile)
                .map { it.name }
                .toSet()
        check(actualNames == expectedNames) { "worker payload differs from its fixed resolved runtime classpath" }
        val forbiddenGroups = listOf("minecraft", "neoforge", "fabric", "architectury")
        val forbidden = workerArtifacts.filter { artifact -> forbiddenGroups.any { it in artifact.moduleVersion.id.group.lowercase() } }
        check(forbidden.isEmpty()) { "worker payload contains game or mod-loader artifacts: $forbidden" }
    }
}

tasks.check {
    dependsOn(assertCompilerWorkerIsolation)
}
