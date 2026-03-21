pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../config/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-scripts"
