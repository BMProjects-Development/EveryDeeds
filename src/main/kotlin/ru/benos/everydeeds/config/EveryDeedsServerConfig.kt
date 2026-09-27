package ru.benos.everydeeds.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.loader.api.FabricLoader
import ru.benos.everydeeds.EveryDeeds
import java.nio.file.Files
import java.nio.file.Path

/** Server-side settings (`config/everydeeds-server.json`). Values are clamped to sane ranges on load. */
data class EveryDeedsServerConfig(
    /** How far along the player's view a structure counts as seen, in blocks. */
    val structureSightDistance: Int = 64
) {
    companion object {
        const val MIN_SIGHT_DISTANCE = 8
        const val MAX_SIGHT_DISTANCE = 256

        val CODEC: Codec<EveryDeedsServerConfig> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("structure_sight_distance", 64).forGetter(EveryDeedsServerConfig::structureSightDistance)
            ).apply(instance) { distance -> EveryDeedsServerConfig(distance.coerceIn(MIN_SIGHT_DISTANCE, MAX_SIGHT_DISTANCE)) }
        }
    }
}

object ServerConfigStore {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val path: Path by lazy { FabricLoader.getInstance().configDir.resolve("${EveryDeeds.MOD_ID}-server.json") }

    @Volatile
    var current: EveryDeedsServerConfig = EveryDeedsServerConfig()
        private set

    /** Reads the file (creating it with defaults on first run). Called when a server starts. */
    fun load() {
        current = runCatching {
            if (Files.exists(path)) {
                EveryDeedsServerConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(path)))
                    .resultOrPartial { error -> EveryDeeds.LOGGER.warn("Invalid {}: {}", path.fileName, error) }
                    .orElse(EveryDeedsServerConfig())
            } else {
                EveryDeedsServerConfig()
            }
        }.getOrElse { failure ->
            EveryDeeds.LOGGER.warn("Could not read {}, using defaults", path.fileName, failure)
            EveryDeedsServerConfig()
        }
        save()
    }

    private fun save() {
        runCatching {
            val json = EveryDeedsServerConfig.CODEC.encodeStart(JsonOps.INSTANCE, current).getOrThrow()
            Files.createDirectories(path.parent)
            Files.writeString(path, GSON.toJson(json))
        }.onFailure { failure -> EveryDeeds.LOGGER.warn("Could not write {}", path.fileName, failure) }
    }
}
