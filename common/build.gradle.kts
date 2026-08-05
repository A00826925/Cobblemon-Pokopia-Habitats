architectury {
    common(rootProject.property("enabled_platforms").toString().split(","))
}

dependencies {
    // Architectury API (common)
    modImplementation("dev.architectury:architectury:${rootProject.property("architectury_api_version")}")

    // Cobblemon: no `common` artifact is published, so compile the common module
    // against the Fabric jar (all references are the platform-neutral
    // com.cobblemon.mod.common.* package). Loom remaps it to Mojang mappings.
    modCompileOnly("com.cobblemon:fabric:${rootProject.property("cobblemon_version")}")

    // Kotlin stdlib (provided at runtime by FLK / KotlinForForge on each platform)
    compileOnly(kotlin("stdlib"))
}
