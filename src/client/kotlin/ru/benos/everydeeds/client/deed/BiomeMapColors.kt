package ru.benos.everydeeds.client.deed

/**
 * Biome colours as players know them from biome maps (Amidst, Chunkbase): the game itself has no
 * per-biome map colour. Biomes missing here (modded ones) fall back to their grass colour.
 */
object BiomeMapColors {
    fun of(biomeId: String): Int? = COLORS[biomeId]

    private val COLORS: Map<String, Int> = mapOf(
        // Overworld: land.
        "minecraft:plains" to 0x8DB360,
        "minecraft:sunflower_plains" to 0xB5DB88,
        "minecraft:snowy_plains" to 0xFFFFFF,
        "minecraft:ice_spikes" to 0xB4DCDC,
        "minecraft:desert" to 0xFA9418,
        "minecraft:swamp" to 0x07F9B2,
        "minecraft:mangrove_swamp" to 0x2CCC8E,
        "minecraft:forest" to 0x056621,
        "minecraft:flower_forest" to 0x2D8E49,
        "minecraft:birch_forest" to 0x307444,
        "minecraft:old_growth_birch_forest" to 0x589C6C,
        "minecraft:dark_forest" to 0x40511A,
        "minecraft:pale_garden" to 0x8A8F85,
        "minecraft:taiga" to 0x0B6659,
        "minecraft:snowy_taiga" to 0x31554A,
        "minecraft:old_growth_pine_taiga" to 0x596651,
        "minecraft:old_growth_spruce_taiga" to 0x818E79,
        "minecraft:savanna" to 0xBDB25F,
        "minecraft:savanna_plateau" to 0xA79D64,
        "minecraft:windswept_savanna" to 0xE5DA87,
        "minecraft:jungle" to 0x537B09,
        "minecraft:sparse_jungle" to 0x628B17,
        "minecraft:bamboo_jungle" to 0x768E14,
        "minecraft:badlands" to 0xD94515,
        "minecraft:wooded_badlands" to 0xB09765,
        "minecraft:eroded_badlands" to 0xFF6D3D,
        "minecraft:meadow" to 0x83BB6D,
        "minecraft:cherry_grove" to 0xFFB7D5,
        "minecraft:grove" to 0x6A8E7F,
        "minecraft:snowy_slopes" to 0xD6E6EC,
        "minecraft:frozen_peaks" to 0xA0C8E6,
        "minecraft:jagged_peaks" to 0xDCDCDC,
        "minecraft:stony_peaks" to 0x8C8C8C,
        "minecraft:windswept_hills" to 0x606060,
        "minecraft:windswept_gravelly_hills" to 0x888888,
        "minecraft:windswept_forest" to 0x507050,
        "minecraft:mushroom_fields" to 0xFF00FF,
        // Overworld: water and shores.
        "minecraft:river" to 0x0000FF,
        "minecraft:frozen_river" to 0xA0A0FF,
        "minecraft:beach" to 0xFADE55,
        "minecraft:snowy_beach" to 0xFAF0C0,
        "minecraft:stony_shore" to 0xA2A284,
        "minecraft:warm_ocean" to 0x0000AC,
        "minecraft:lukewarm_ocean" to 0x000090,
        "minecraft:deep_lukewarm_ocean" to 0x000040,
        "minecraft:ocean" to 0x000070,
        "minecraft:deep_ocean" to 0x000030,
        "minecraft:cold_ocean" to 0x202070,
        "minecraft:deep_cold_ocean" to 0x202038,
        "minecraft:frozen_ocean" to 0x7070D6,
        "minecraft:deep_frozen_ocean" to 0x404090,
        // Overworld: underground.
        "minecraft:dripstone_caves" to 0x7B5A3A,
        "minecraft:lush_caves" to 0x7BA331,
        "minecraft:deep_dark" to 0x0A2A2E,
        // Nether.
        "minecraft:nether_wastes" to 0xBF3B3B,
        "minecraft:soul_sand_valley" to 0x5E3830,
        "minecraft:crimson_forest" to 0xDD0808,
        "minecraft:warped_forest" to 0x49907B,
        "minecraft:basalt_deltas" to 0x403636,
        // End.
        "minecraft:the_end" to 0x8080FF,
        "minecraft:end_highlands" to 0xB5B5FF,
        "minecraft:end_midlands" to 0x9C9CE6,
        "minecraft:small_end_islands" to 0x6464C8,
        "minecraft:end_barrens" to 0x7474C0,
        "minecraft:the_void" to 0x000000
    )
}
