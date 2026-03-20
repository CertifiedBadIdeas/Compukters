@file:Suppress("PropertyName")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.slf4j.event.Level


val kotlin_version: String by extra
val mod_id: String by extra
val mod_name: String by extra
val mod_license: String by extra
val mod_authors: String by extra
val mod_description: String by extra
val mod_version: String by extra
val mod_group_id: String by extra
val minecraft_version: String by extra
val minecraft_version_range: String by extra
val forge_version: String by extra
val kotlin_for_forge_version: String by extra
val forge_version_range: String by extra
val loader_version_range: String by extra
val parchment_mappings_version: String by extra
val parchment_minecraft_version: String by extra

plugins {
    idea
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.neoforge)
    alias(libs.plugins.shadow)
}

tasks.withType(Wrapper::class) {
    distributionType = Wrapper.DistributionType.BIN
}

version = mod_version
group = mod_group_id

repositories {
    mavenCentral()
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        name = "Kotlin for Forge"
    }
    maven("https://maven.neoforged.net") {
        name = "NeoForge"
        content {
            includeGroup("net.neoforged")
        }
    }
    maven("https://maven.minecraftforge.net/")
}

base.archivesName = mod_id

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

val shadedDeps: Configuration by configurations.creating

configurations["compileOnly"].extendsFrom(shadedDeps)

dependencies {
    implementation("thedarkcolour:kotlinforforge:${kotlin_for_forge_version}")

    shadedDeps("org.jetbrains.kotlin:kotlin-scripting-common:$kotlin_version")
    shadedDeps("org.jetbrains.kotlin:kotlin-scripting-dependencies:$kotlin_version")
    shadedDeps("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlin_version")
    shadedDeps("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlin_version")
    shadedDeps("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlin_version")
}

var generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        var replaceProperties =
            mapOf(
                "minecraft_version" to minecraft_version,
                "minecraft_version_range" to minecraft_version_range,
                "forge_version" to forge_version,
                "forge_version_range" to forge_version_range,
                "loader_version_range" to loader_version_range,
                "mod_id" to mod_id,
                "mod_name" to mod_name,
                "mod_license" to mod_license,
                "mod_version" to mod_version,
                "mod_authors" to mod_authors,
                "mod_description" to mod_description,
            )
        inputs.properties(replaceProperties)
        expand(replaceProperties)
        from("src/main/resources") {
            exclude {
                it.name.contains(".png")
            }
        }
        into("build/generated/sources/modMetadata")
    }

tasks.withType(ProcessResources::class) {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

val jar =
    tasks.named<Jar>("jar") {
        archiveClassifier.set("lite")
        exclude(
            "LICENSE.txt",
            "META-INF/MANIFSET.MF",
            "META-INF/maven/**",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "META-INF/versions/**",
        )
        finalizedBy("reobfJar")
    }

val shadowJar =
    tasks.named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        configurations = listOf(shadedDeps)

        // Главное: переименовываем проблемный пакет
        // Теперь org.jetbrains.kotlin.native станет org.jetbrains.kotlin.fixednative
        relocate("org.jetbrains.kotlin.native", "org.jetbrains.kotlin.fixednative")
        relocate("org.jetbrains.kotlin.fir.backend.native", "org.jetbrains.kotlin.fir.backend.fixednative")
        relocate("org.jetbrains.kotlin.fir.analysis.native", "org.jetbrains.kotlin.fir.analysis.fixednative")
        relocate("org.jetbrains.kotlin.fir.analysis.diagnostics.native", "org.jetbrains.kotlin.fir.analysis.diagnostics.fixednative")

        // Исключаем метаданные, которые могут конфликтовать
        exclude("META-INF/maven/**")
        exclude("META-INF/LICENSE*")
        exclude("META-INF/NOTICE*")

        // finalizedBy("reobfShadowJar")
    }

// (extensions["reobf"] as NamedDomainObjectContainer<*>).create("shadowJar")
tasks.getByName("build").dependsOn("shadowJar", generateModMetadata.name)

sourceSets.main {
    resources.srcDir("src/generated/resources")
    resources.srcDir(generateModMetadata.get().destinationDir)
}

legacyForge {
    version = "$minecraft_version-$forge_version"

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    runs {
        create("client") {
            client()
            systemProperty("forge.enabledGameTestNamespaces", mod_id)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("forge.enabledGameTestNamespaces", mod_id)
        }

        create("gameTestServer") {
            type = "gameTestServer"
            systemProperty("forge.enabledGameTestNamespaces", mod_id)
        }

        create("data") {
            data()

            programArguments.addAll(
                "--mod",
                mod_id,
                "--all",
                "--output",
                file("src/generated/resources/").absolutePath,
                "--existing",
                file("src/main/resources/").absolutePath,
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = Level.DEBUG
        }
    }

    mods {
        create(mod_id) {
            sourceSet(sourceSets.main.orNull)
        }
    }

    ideSyncTask(generateModMetadata)
}

tasks.withType(JavaCompile::class).configureEach {
    options.encoding = "UTF-8" // Use the UTF-8 charset for Java compilation
}

tasks {
    whenTaskAdded {
        if (name == "prepareRuns") dependsOn(shadowJar)
    }
}

idea {
    module.isDownloadSources = true
    module.isDownloadJavadoc = true
}
