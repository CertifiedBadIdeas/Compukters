@file:Suppress("PropertyName")

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
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("net.neoforged.moddev.legacyforge") version "2.0.120"
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

            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        create(mod_id) {
            sourceSet(sourceSets.main.orNull)
        }
    }
}

sourceSets.main {
    resources.srcDir("src/generated/resources")
}

configurations {
    val localRuntime by creating

    runtimeClasspath {
        extendsFrom(localRuntime)
    }
}

obfuscation {
    createRemappingConfiguration(configurations["localRuntime"])
}

dependencies {
    implementation("thedarkcolour:kotlinforforge:$kotlin_for_forge_version")
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

sourceSets.main {
    resources.srcDir(generateModMetadata.get().destinationDir)
}

legacyForge {
    ideSyncTask(generateModMetadata)
}

tasks.withType(JavaCompile::class).configureEach {
    options.encoding = "UTF-8" // Use the UTF-8 charset for Java compilation
}

idea {
    module.isDownloadSources = true
    module.isDownloadJavadoc = true
}
