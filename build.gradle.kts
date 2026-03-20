@file:Suppress("PropertyName")

import org.slf4j.event.Level
import kotlin.reflect.KProperty

fun File.parseProperties(): Map<String, String> =
    readLines()
        .mapNotNull { it.indexOf('=').takeIf { it != -1 }?.let { v -> v to it } }
        .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }

val modPropertiesFile =
    file("mod.properties")

val modPropertiesDelegate = modPropertiesFile.parseProperties()

val kotlin_version by modPropertiesDelegate
val mod_id by modPropertiesDelegate
val mod_name by modPropertiesDelegate
val mod_license by modPropertiesDelegate
val mod_authors by modPropertiesDelegate
val mod_description by modPropertiesDelegate
val mod_version by modPropertiesDelegate
val mod_group_id by modPropertiesDelegate
val minecraft_version by modPropertiesDelegate
val minecraft_version_range by modPropertiesDelegate
val forge_version by modPropertiesDelegate
val kotlin_for_forge_version by modPropertiesDelegate
val forge_version_range by modPropertiesDelegate
val loader_version_range by modPropertiesDelegate
val parchment_mappings_version by modPropertiesDelegate
val parchment_minecraft_version by modPropertiesDelegate

plugins {
    idea
    alias(libs.plugins.kotlin)
    alias(libs.plugins.neoforge)
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

dependencies {
    modImplementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("stdlib-jdk8"))
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
            // jvmArgument("--")
            logLevel = Level.DEBUG
        }
    }

    mods {
        create(mod_id) {
            sourceSet(sourceSets.main.orNull)
        }
    }
}

var generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        inputs.file(modPropertiesFile)

        val replaceProperties = modPropertiesFile.parseProperties()

        inputs.properties(replaceProperties)

        expand(replaceProperties)

        val from = file("src/main/resources")

        inputs.dir(from)

        from(from) {
            exclude {
                it.name.contains(".png")
            }
        }

        val intoDir = file("build/generated/resources")

        outputs.dir(intoDir)

        into(intoDir)
    }

tasks.named("processResources") {
    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.setSrcDirs(listOf("build/generated/resources"))
}
