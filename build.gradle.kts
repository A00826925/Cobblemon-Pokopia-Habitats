plugins {
    id("architectury-plugin") version "3.4.164"
    id("dev.architectury.loom") version "1.14.476" apply false
    kotlin("jvm") version "2.2.20" apply false
}

architectury {
    minecraft = property("minecraft_version") as String
}

allprojects {
    group = property("mod_group") as String
    version = property("mod_version") as String
}

subprojects {
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "architectury-plugin")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    val loom = project.extensions.getByName<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom")

    configure<BasePluginExtension> {
        archivesName.set("${rootProject.property("archives_base_name")}-${project.name}")
    }

    repositories {
        mavenCentral()
        maven(url = "https://maven.architectury.dev/") { name = "Architectury" }
        maven(url = "https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven(url = "https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
        maven(url = "https://maven.impactdev.net/repository/development/") { name = "ImpactDev (Cobblemon)" }
        maven(url = "https://maven.fabricmc.net/") { name = "Fabric" }
    }

    dependencies {
        "minecraft"("com.mojang:minecraft:${rootProject.property("minecraft_version")}")
        "mappings"(loom.officialMojangMappings())
    }

    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<ProcessResources>().configureEach {
        val replacements = mapOf(
            "mod_id" to rootProject.property("mod_id"),
            "mod_name" to rootProject.property("mod_name"),
            "mod_version" to rootProject.property("mod_version"),
            "mod_authors" to rootProject.property("mod_authors"),
            "mod_description" to rootProject.property("mod_description"),
            "mc_version_range" to rootProject.property("mc_version_range"),
            "neoforge_version_range" to rootProject.property("neoforge_version_range"),
            "kff_version_range" to rootProject.property("kff_version_range"),
            "cobblemon_version_range" to rootProject.property("cobblemon_version_range"),
            "fabric_loader_version" to rootProject.property("fabric_loader_version"),
            "fabric_kotlin_version" to rootProject.property("fabric_kotlin_version")
        )
        inputs.properties(replacements)
        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "pack.mcmeta")) {
            expand(replacements)
        }
    }
}
