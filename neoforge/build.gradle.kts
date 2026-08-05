plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

val common: Configuration by configurations.creating {
    configurations["compileClasspath"].extendsFrom(this)
    configurations["runtimeClasspath"].extendsFrom(this)
    configurations["developmentNeoForge"].extendsFrom(this)
}

val shadowCommon: Configuration by configurations.creating

loom {
    runs {
        named("client") { ideConfigGenerated(true) }
        named("server") { ideConfigGenerated(true) }
    }
}

dependencies {
    "neoForge"("net.neoforged:neoforge:${rootProject.property("neoforge_version")}")
    modImplementation("dev.architectury:architectury-neoforge:${rootProject.property("architectury_api_version")}")

    // Kotlin runtime for NeoForge.
    implementation("thedarkcolour:kotlinforforge-neoforge:${rootProject.property("kotlinforforge_version")}")

    // Cobblemon (NeoForge) — required at runtime, provided by the user's install.
    modImplementation("com.cobblemon:neoforge:${rootProject.property("cobblemon_version")}")

    common(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    shadowCommon(project(path = ":common", configuration = "transformProductionNeoForge")) { isTransitive = false }
}

tasks {
    named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        injectAccessWidener.set(true)
        inputFile.set(named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
        dependsOn("shadowJar")
    }

    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        exclude("fabric.mod.json", "architectury.common.json")
        configurations = listOf(shadowCommon)
        archiveClassifier.set("dev-shadow")
    }

    named<Jar>("jar") {
        archiveClassifier.set("dev")
    }
}
