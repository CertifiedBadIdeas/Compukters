import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinConvention)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    val rootSourceSets = rootProject.extensions.getByType(SourceSetContainer::class.java)
    compileOnly(rootSourceSets.named("main").get().output)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.dependencies)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvmHost)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(17)
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(rootProject.tasks.named("compileKotlin"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveFileName.set("CompukterCraftScripting.jar")
    mergeServiceFiles()

    dependencies {
        exclude {
            it.moduleGroup == rootProject.group.toString() || it.name == rootProject.name
        }
    }
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
