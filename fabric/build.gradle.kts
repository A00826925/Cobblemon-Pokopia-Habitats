plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

architectury {
    platformSetupLoomIde()
    fabric()
}

val common: Configuration by configurations.creating {
    configurations["compileClasspath"].extendsFrom(this)
    configurations["runtimeClasspath"].extendsFrom(this)
    configurations["developmentFabric"].extendsFrom(this)
}

val shadowCommon: Configuration by configurations.creating

configurations {
    named("developmentFabric") { extendsFrom(common) }
}

loom {
    runs {
        named("client") { ideConfigGenerated(true) }
        named("server") { ideConfigGenerated(true) }
    }
}

dependencies {
    modImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${rootProject.property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${rootProject.property("fabric_kotlin_version")}")
    modImplementation("dev.architectury:architectury-fabric:${rootProject.property("architectury_api_version")}")

    // Cobblemon (Fabric) — required at runtime, provided by the user's install.
    modImplementation("com.cobblemon:fabric:${rootProject.property("cobblemon_version")}")

    common(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    shadowCommon(project(path = ":common", configuration = "transformProductionFabric")) { isTransitive = false }
}

tasks {
    named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        injectAccessWidener.set(true)
        inputFile.set(named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
        dependsOn("shadowJar")
    }

    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        exclude("architectury.common.json")
        configurations = listOf(shadowCommon)
        archiveClassifier.set("dev-shadow")
    }

    named<Jar>("jar") {
        archiveClassifier.set("dev")
    }
}
