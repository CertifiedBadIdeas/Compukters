
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    maven("https://maven.parchmentmc.org/") {
        name = "Parchment MC"
    }
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/") {
        name = "IntelliJ Dependencies"
    }
    maven("https://www.jetbrains.com/intellij-repository/releases") {
        name = "IntelliJ Releases"
    }
}

kotlin {
    jvmToolchain(17)
}
