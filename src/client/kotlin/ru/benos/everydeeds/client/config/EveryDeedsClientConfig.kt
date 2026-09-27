package ru.benos.everydeeds.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.loader.api.FabricLoader
import ru.benos.everydeeds.EveryDeeds
import java.nio.file.Files
import java.nio.file.Path

/**
 * Colours of the progress UI, stored as `#RRGGBB` or `#AARRGGBB` strings.
 * Defaults match the built-in dark theme.
 */
data class DeedPalette(
    val accent: Int = 0xFF70B7A8.toInt(),
    val complete: Int = 0xFF78C679.toInt(),
    val inProgress: Int = 0xFFE0B15B.toInt(),
    val remaining: Int = 0xFFD05A5A.toInt(),
    val text: Int = 0xFFE9ECEF.toInt(),
    val textMuted: Int = 0xFF9EA6AE.toInt(),
    val panel: Int = 0xE01B1D20.toInt(),
    val slot: Int = 0xFF3D4248.toInt(),
    val slotInner: Int = 0xFF2A2E33.toInt(),
    val border: Int = 0xFF555C65.toInt(),
    /** Toast of a reached milestone. */
    val milestone: Int = 0xFFFFD54A.toInt(),
    /** Everything done: the final toast and the full overall bar. */
    val finale: Int = 0xFFB77BFF.toInt()
) {
    companion object {
        private val DEFAULT = DeedPalette()

        /** `#RGB` hex without alpha means fully opaque. */
        val COLOR_CODEC: Codec<Int> = Codec.STRING.comapFlatMap(
            { text ->
                val hex = text.removePrefix("#")
                val parsed = hex.toLongOrNull(16)
                when {
                    parsed == null -> DataResult.error { "Invalid colour '$text', expected #RRGGBB or #AARRGGBB" }
                    hex.length == 6 -> DataResult.success((0xFF000000L or parsed).toInt())
                    hex.length == 8 -> DataResult.success(parsed.toInt())
                    else -> DataResult.error { "Invalid colour '$text', expected #RRGGBB or #AARRGGBB" }
                }
            },
            { color -> "#%08X".format(color) }
        )

        val CODEC: Codec<DeedPalette> = RecordCodecBuilder.create { instance ->
            instance.group(
                COLOR_CODEC.optionalFieldOf("accent", DEFAULT.accent).forGetter(DeedPalette::accent),
                COLOR_CODEC.optionalFieldOf("complete", DEFAULT.complete).forGetter(DeedPalette::complete),
                COLOR_CODEC.optionalFieldOf("in_progress", DEFAULT.inProgress).forGetter(DeedPalette::inProgress),
                COLOR_CODEC.optionalFieldOf("remaining", DEFAULT.remaining).forGetter(DeedPalette::remaining),
                COLOR_CODEC.optionalFieldOf("text", DEFAULT.text).forGetter(DeedPalette::text),
                COLOR_CODEC.optionalFieldOf("text_muted", DEFAULT.textMuted).forGetter(DeedPalette::textMuted),
                COLOR_CODEC.optionalFieldOf("panel", DEFAULT.panel).forGetter(DeedPalette::panel),
                COLOR_CODEC.optionalFieldOf("slot", DEFAULT.slot).forGetter(DeedPalette::slot),
                COLOR_CODEC.optionalFieldOf("slot_inner", DEFAULT.slotInner).forGetter(DeedPalette::slotInner),
                COLOR_CODEC.optionalFieldOf("border", DEFAULT.border).forGetter(DeedPalette::border),
                COLOR_CODEC.optionalFieldOf("milestone", DEFAULT.milestone).forGetter(DeedPalette::milestone),
                COLOR_CODEC.optionalFieldOf("finale", DEFAULT.finale).forGetter(DeedPalette::finale)
            ).apply(instance, ::DeedPalette)
        }
    }
}

/**
 * Client-only presentation settings, in `config/everydeeds-client.json`. Missing keys fall back to
 * defaults and out-of-range values are clamped, so a hand-edited file can never break the screen.
 */
data class EveryDeedsClientConfig(
    /** Grid cell size in GUI pixels. */
    val cellSize: Int = 64,
    /** Hover focus card size as a multiple of the cell size. */
    val focusZoom: Float = 1.75f,
    /** How long the cursor must rest on a cell before the focus card opens. */
    val hoverDelayMs: Int = 350,
    /** Whether not-yet-started objects are listed (in red) after the explored ones. */
    val showRemaining: Boolean = false,
    val colors: DeedPalette = DeedPalette()
) {
    fun clamped(): EveryDeedsClientConfig = copy(
        cellSize = cellSize.coerceIn(MIN_CELL_SIZE, MAX_CELL_SIZE),
        focusZoom = focusZoom.coerceIn(1f, 4f),
        hoverDelayMs = hoverDelayMs.coerceIn(0, 3000)
    )

    companion object {
        const val MIN_CELL_SIZE = 32
        const val MAX_CELL_SIZE = 128
        private val DEFAULT = EveryDeedsClientConfig()

        val CODEC: Codec<EveryDeedsClientConfig> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("cell_size", DEFAULT.cellSize).forGetter(EveryDeedsClientConfig::cellSize),
                Codec.FLOAT.optionalFieldOf("focus_zoom", DEFAULT.focusZoom).forGetter(EveryDeedsClientConfig::focusZoom),
                Codec.INT.optionalFieldOf("hover_delay_ms", DEFAULT.hoverDelayMs).forGetter(EveryDeedsClientConfig::hoverDelayMs),
                Codec.BOOL.optionalFieldOf("show_remaining", DEFAULT.showRemaining).forGetter(EveryDeedsClientConfig::showRemaining),
                DeedPalette.CODEC.optionalFieldOf("colors", DEFAULT.colors).forGetter(EveryDeedsClientConfig::colors)
            ).apply(instance, ::EveryDeedsClientConfig)
        }
    }
}

/** Loads, holds and saves [EveryDeedsClientConfig]. */
object ClientConfigStore {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val path: Path by lazy { FabricLoader.getInstance().configDir.resolve("${EveryDeeds.MOD_ID}-client.json") }

    var current: EveryDeedsClientConfig = EveryDeedsClientConfig()
        private set

    fun load() {
        current = if (Files.exists(path)) {
            runCatching {
                val json = JsonParser.parseString(Files.readString(path))
                EveryDeedsClientConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().clamped()
            }.getOrElse { failure ->
                EveryDeeds.LOGGER.warn("Could not read {}, using defaults", path, failure)
                EveryDeedsClientConfig()
            }
        } else {
            EveryDeedsClientConfig()
        }
        // Write back so new keys appear in the file with their defaults.
        save()
    }

    fun update(transform: (EveryDeedsClientConfig) -> EveryDeedsClientConfig) {
        current = transform(current).clamped()
        save()
    }

    private fun save() {
        runCatching {
            val json = EveryDeedsClientConfig.CODEC.encodeStart(JsonOps.INSTANCE, current).getOrThrow()
            Files.createDirectories(path.parent)
            Files.writeString(path, GSON.toJson(json))
        }.onFailure { failure -> EveryDeeds.LOGGER.warn("Could not write {}", path, failure) }
    }
}
