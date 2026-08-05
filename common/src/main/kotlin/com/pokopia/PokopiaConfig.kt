package com.pokopia

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.architectury.platform.Platform
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Common (server-authoritative) config. Everything the user may want to tweak
 * lives here: scanner box size, chunk-scan cooldown, spawn pacing, etc.
 *
 * Stored as a small JSON file in the platform config folder so the exact same
 * implementation works on Fabric and NeoForge (no loader-specific config spec).
 * Unknown/missing keys fall back to defaults; out-of-range values are clamped.
 */
object PokopiaConfig {
    private const val FILE_NAME = "pokopia.json"
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    // Backing values (defaults match the original NeoForge ModConfigSpec).
    @Volatile private var disableNaturalCobblemonSpawnsValue: Boolean = false
    @Volatile private var scannerBoxWidthValue: Int = 5
    @Volatile private var scannerBoxHeightValue: Int = 5
    @Volatile private var enableAutomaticHabitatDetectionValue: Boolean = false
    @Volatile private var chunkScanCooldownSecondsValue: Int = 10
    @Volatile private var chunkScanRadiusValue: Int = 0
    @Volatile private var chunkScanVerticalRangeValue: Int = 20
    @Volatile private var maxPokemonPerHabitatValue: Int = 3
    @Volatile private var spawnIntervalSecondsValue: Int = 20
    @Volatile private var maxNaturalHabitatsPerChunkValue: Int = 3

    val disableNaturalCobblemonSpawns: Boolean get() = disableNaturalCobblemonSpawnsValue
    val scannerBoxWidth: Int get() = scannerBoxWidthValue
    val scannerBoxHeight: Int get() = scannerBoxHeightValue
    val enableAutomaticHabitatDetection: Boolean get() = enableAutomaticHabitatDetectionValue
    val chunkScanCooldownSeconds: Int get() = chunkScanCooldownSecondsValue
    val chunkScanRadius: Int get() = chunkScanRadiusValue
    val chunkScanVerticalRange: Int get() = chunkScanVerticalRangeValue
    val maxPokemonPerHabitat: Int get() = maxPokemonPerHabitatValue
    val spawnIntervalSeconds: Int get() = spawnIntervalSecondsValue
    val maxNaturalHabitatsPerChunk: Int get() = maxNaturalHabitatsPerChunkValue

    /** Loads the config file, creating it with defaults if it does not exist. */
    fun load() {
        try {
            val dir = Platform.getConfigFolder()
            val path = dir.resolve(FILE_NAME)
            if (path.exists()) {
                val json = GSON.fromJson(path.readText(), JsonObject::class.java) ?: JsonObject()
                readFrom(json)
                // Rewrite so newly added keys / clamped values are persisted.
                save(path)
            } else {
                dir.createDirectories()
                save(path)
            }
        } catch (e: Exception) {
            Pokopia.LOGGER.error("Failed to load Pokopia config; using defaults", e)
        }
    }

    private fun readFrom(json: JsonObject) {
        disableNaturalCobblemonSpawnsValue = json.bool("disableNaturalCobblemonSpawns", disableNaturalCobblemonSpawnsValue)
        scannerBoxWidthValue = json.int("scannerBoxWidth", scannerBoxWidthValue, 1, 33)
        scannerBoxHeightValue = json.int("scannerBoxHeight", scannerBoxHeightValue, 1, 33)
        enableAutomaticHabitatDetectionValue = json.bool("enableAutomaticHabitatDetection", enableAutomaticHabitatDetectionValue)
        chunkScanCooldownSecondsValue = json.int("chunkScanCooldownSeconds", chunkScanCooldownSecondsValue, 0, 3600)
        chunkScanRadiusValue = json.int("chunkScanRadius", chunkScanRadiusValue, 0, 4)
        chunkScanVerticalRangeValue = json.int("chunkScanVerticalRange", chunkScanVerticalRangeValue, 2, 64)
        maxPokemonPerHabitatValue = json.int("maxPokemonPerHabitat", maxPokemonPerHabitatValue, 1, 3)
        spawnIntervalSecondsValue = json.int("habitatSpawnIntervalSeconds", spawnIntervalSecondsValue, 1, 3600)
        maxNaturalHabitatsPerChunkValue = json.int("maxNaturalHabitatsPerChunk", maxNaturalHabitatsPerChunkValue, 1, 16)
    }

    private fun save(path: java.nio.file.Path) {
        val json = JsonObject()
        json.addProperty("disableNaturalCobblemonSpawns", disableNaturalCobblemonSpawnsValue)
        json.addProperty("scannerBoxWidth", scannerBoxWidthValue)
        json.addProperty("scannerBoxHeight", scannerBoxHeightValue)
        json.addProperty("enableAutomaticHabitatDetection", enableAutomaticHabitatDetectionValue)
        json.addProperty("chunkScanCooldownSeconds", chunkScanCooldownSecondsValue)
        json.addProperty("chunkScanRadius", chunkScanRadiusValue)
        json.addProperty("chunkScanVerticalRange", chunkScanVerticalRangeValue)
        json.addProperty("maxPokemonPerHabitat", maxPokemonPerHabitatValue)
        json.addProperty("habitatSpawnIntervalSeconds", spawnIntervalSecondsValue)
        json.addProperty("maxNaturalHabitatsPerChunk", maxNaturalHabitatsPerChunkValue)
        path.writeText(GSON.toJson(json))
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        if (has(key)) runCatching { get(key).asBoolean }.getOrDefault(default) else default

    private fun JsonObject.int(key: String, default: Int, min: Int, max: Int): Int =
        if (has(key)) runCatching { get(key).asInt }.getOrDefault(default).coerceIn(min, max) else default
}
