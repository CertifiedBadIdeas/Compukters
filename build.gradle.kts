@file:Suppress("PropertyName")

import org.slf4j.event.Level
import kotlin.reflect.KProperty

fun File.parseProperties(): Map<String, String> =
    readLines()
        .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
        .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }

val modPropertiesFile = file("mod.properties")
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
    //maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
    maven("https://maven.neoforged.net") {
        name = "NeoForge"
        content {
            includeGroup("net.neoforged")
        }
    }
    maven("https://maven.minecraftforge.net/") {
        name = "Forge"
    }
}

base.archivesName = mod_id

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    runtimeOnly(kotlin("stdlib"))
    runtimeOnly(kotlin("stdlib-jdk8"))
    runtimeOnly(kotlin("reflect"))

//    add("clientAdditionalRuntimeClasspath", kotlin("stdlib"))
//    add("clientAdditionalRuntimeClasspath", kotlin("reflect"))
//
//    add("additionalRuntimeClasspath", kotlin("stdlib"))
//    add("additionalRuntimeClasspath", kotlin("reflect"))

    jarJar(kotlin("stdlib-jdk8"))
    jarJar(kotlin("stdlib"))
    jarJar(kotlin("reflect"))
}

legacyForge {
    version = "$minecraft_version-$forge_version"

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    runs {
        register("client") {
            client()
            systemProperty("forge.enabledGameTestNamespaces", mod_id)
        }

//        register("server") {
//            server()
//            programArgument("--nogui")
//            systemProperty("forge.enabledGameTestNamespaces", mod_id)
//        }
//
//        register("gameTestServer") {
//            type = "gameTestServer"
//            systemProperty("forge.enabledGameTestNamespaces", mod_id)
//        }

//        register("data") {
//            data()
//
//            programArguments.addAll(
//                "--mod", mod_id,
//                "--all",
//                "--output", file("src/generatedCtqgjke/resources/").absolutePath,
//                "--existing", file("src/main/resources/").absolutePath,
//            )
//        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = Level.DEBUG
        }
    }

    mods {
        register(mod_id) {
            sourceSet(sourceSets.main.get())
        }
    }
}

var generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val replaceProperties = modPropertiesFile.parseProperties()
        val from = file("src/main/resources")
        val intoDir = file("build/generated/resources")

        inputs.file(modPropertiesFile)
        inputs.properties(replaceProperties)
        inputs.dir(from)

        outputs.dir(intoDir)

        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
    }

tasks.named("processResources") {
    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.setSrcDirs(listOf("build/generated/resources"))
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    finalizedBy("reobfJar")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
