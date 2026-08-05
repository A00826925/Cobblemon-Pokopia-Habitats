pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net/") { name = "Fabric" }
        maven(url = "https://maven.architectury.dev/") { name = "Architectury" }
        maven(url = "https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "cobblemon-pokopia"

include("common")
include("fabric")
include("neoforge")
