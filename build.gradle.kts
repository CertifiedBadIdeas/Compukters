@file:Suppress("PropertyName")

import org.gradle.kotlin.dsl.provideDelegate
import kotlin.reflect.KProperty

//operator fun Provider<String>.getValue(ref: Any?, prop: KProperty<*>): String = this.get()
//
//fun File.parseProperties(): Map<String, String> =
//    readLines()
//        .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
//        .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }
//
//val modPropertiesFile = file("config/mod.properties")
//val modPropertiesDelegate = extra.properties.mapValues { (_, v) -> v.toString() }
//
//val mod_id by modPropertiesDelegate
//val mod_name by modPropertiesDelegate
//val mod_license by modPropertiesDelegate
//val mod_authors by modPropertiesDelegate
//val mod_description by modPropertiesDelegate
//val mod_version by modPropertiesDelegate
//val mod_group_id by modPropertiesDelegate
//val minecraft_version by libs.versions.minecraft
//val minecraft_version_range by modPropertiesDelegate
//val forge_version by modPropertiesDelegate
//val forge_version_range by modPropertiesDelegate
//val loader_version_range by modPropertiesDelegate
//val parchment_mappings_version by modPropertiesDelegate
//val parchment_minecraft_version by modPropertiesDelegate

plugins {
    idea
    alias(libs.plugins.kotlin)
    alias(libs.plugins.architectury.loom)
    alias(libs.plugins.architectury.plugin)
}

repositories {
    maven("https://maven.parchmentmc.org/")
}

//loom {
//    forge {
//        mixinConfig("compuktercraft.mixins.json")
//    }
//}


architectury {
    minecraft = libs.versions.minecraft.get()

    platformSetupLoomIde()
    forge()
}

dependencies {
    minecraft(libs.minecraft)

    mappings(loom.layered { officialMojangMappings(); parchment(libs.parchment.for1v20v1) })

    modImplementation(libs.architectury.forge)

    forge(libs.forge)
}

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val modPropertiesFile = file("config/mod.properties")
        val replaceProperties = modPropertiesFile.readLines()
            .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
            .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }
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

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.setSrcDirs(generateModMetadata.get().outputs.files)
}