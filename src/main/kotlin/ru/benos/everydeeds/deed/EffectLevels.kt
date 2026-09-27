package ru.benos.everydeeds.deed

import net.minecraft.resources.Identifier

/**
 * Effect levels a survival player can actually get, per vanilla effect. They are not contiguous:
 * slowness comes as I (arrows, potion), IV (Turtle Master) and VI (strong Turtle Master), never II.
 * Effects missing here (single-level ones, modded ones) have just level I.
 */
object EffectLevels {
    fun obtainable(effect: Identifier): List<Int> = LEVELS[effect.toString()] ?: listOf(1)

    private val LEVELS: Map<String, List<Int>> = mapOf(
        "minecraft:speed" to listOf(1, 2),
        "minecraft:slowness" to listOf(1, 4, 6),
        "minecraft:haste" to listOf(1, 2),
        "minecraft:strength" to listOf(1, 2),
        "minecraft:instant_health" to listOf(1, 2),
        "minecraft:instant_damage" to listOf(1, 2),
        "minecraft:jump_boost" to listOf(1, 2),
        "minecraft:regeneration" to listOf(1, 2),
        "minecraft:resistance" to listOf(1, 3, 4),
        "minecraft:hunger" to listOf(1, 3),
        "minecraft:poison" to listOf(1, 2),
        "minecraft:wither" to listOf(1, 2),
        "minecraft:absorption" to listOf(1, 4),
        "minecraft:bad_omen" to listOf(1, 2, 3, 4, 5),
        "minecraft:hero_of_the_village" to listOf(1, 2, 3, 4, 5)
    )
}
