package ru.benos.everydeeds.client.datagen

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import ru.benos.everydeeds.client.datagen.DeedSetProvider.Companion.EXTENDED
import ru.benos.everydeeds.client.datagen.DeedSetProvider.Companion.HOUR
import ru.benos.everydeeds.client.datagen.DeedSetProvider.Companion.INSANE
import ru.benos.everydeeds.client.datagen.DeedSetProvider.Companion.MANIAC
import ru.benos.everydeeds.client.datagen.DeedSetProvider.Companion.SHORT
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.DeedDefinition
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.TargetSelector
import java.util.Optional

/**
 * One kind of milestone in tiers: a deed for a total over the whole category ("break 10 000 blocks")
 * for each amount. Harder sets multiply the amounts by their scale.
 */
internal class MilestoneTiers(
    private val name: String,
    private val category: DeedCategory,
    private val action: DeedAction,
    private val icon: Item,
    private val amounts: List<Int>,
    private val targets: TargetSelector
) {
    /** The deeds of one set, keyed by their path inside the set ("milestones/broken_10000"). */
    fun definitions(scale: Int): List<Pair<String, DeedDefinition>> =
        amounts.map { amount ->
            val total = amount * scale
            "milestones/${name}_$total" to DeedDefinition(
                category = category,
                action = action,
                targets = targets,
                predicate = Optional.empty(),
                goal = DeedGoal.Count(total),
                reward = Optional.empty(),
                title = Optional.empty(),
                aggregate = true,
                icon = Optional.of(BuiltInRegistries.ITEM.getKey(icon))
            )
        }
}

/**
 * Milestones of the built-in sets. A deed's path is its identity across sets (the same path shares
 * progress), and a milestone's path ends with its total, so equal paths always mean equal goals.
 */
internal object Milestones {
    /** Built-in set -> multiplier of the milestone amounts. */
    val SCALES: Map<String, Int> = linkedMapOf(SHORT to 1, EXTENDED to 2, INSANE to 5, MANIAC to 10)

    /** Part of the mod's own data. */
    val CORE: List<MilestoneTiers> = listOf(
        tiers("broken", DeedCategory.BLOCKS, DeedAction.BROKEN, Items.DIAMOND_PICKAXE, 1_000, 10_000, 100_000),
        tiers("placed", DeedCategory.BLOCKS, DeedAction.PLACED, Items.BRICKS, 1_000, 10_000, 100_000),
        tiers("crafted", DeedCategory.ITEMS, DeedAction.CRAFTED, Items.CRAFTING_TABLE, 500, 5_000),
        tiers("eaten", DeedCategory.ITEMS, DeedAction.EATEN, Items.COOKED_BEEF, 100, 1_000),
        tiers("fished", DeedCategory.ITEMS, DeedAction.FISHED, Items.FISHING_ROD, 50, 500),
        tiers("killed", DeedCategory.ENTITIES, DeedAction.KILLED, Items.IRON_SWORD, 100, 1_000, 10_000),
        tiers("bred", DeedCategory.ENTITIES, DeedAction.BRED, Items.WHEAT, 50, 500),
        tiers("traded", DeedCategory.ENTITIES, DeedAction.TRADED, Items.EMERALD, 100, 1_000),
        tiers("traveled", DeedCategory.BIOMES, DeedAction.TRAVELED, Items.LEATHER_BOOTS, 10_000, 100_000, 1_000_000),
        tiers("swum", DeedCategory.BLOCKS, DeedAction.TRAVELED, Items.WATER_BUCKET, 1_000, 10_000, targets = liquids()),
        tiers("ridden_distance", DeedCategory.ENTITIES, DeedAction.TRAVELED, Items.SADDLE, 1_000, 10_000, 100_000, targets = rideable()),
        tiers("time_spent", DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, Items.CLOCK, 10 * HOUR, 100 * HOUR),
        tiers("looted", DeedCategory.STRUCTURES, DeedAction.LOOTED, Items.CHEST, 25, 250)
    )

    /**
     * The "More milestones" built-in data pack: first steps and in-between tiers of the core milestones
     * (never beyond their top tier), and milestones for the actions the core ones leave out.
     */
    val MORE: List<MilestoneTiers> = listOf(
        tiers("broken", DeedCategory.BLOCKS, DeedAction.BROKEN, Items.DIAMOND_PICKAXE, 100, 5_000, 50_000),
        tiers("placed", DeedCategory.BLOCKS, DeedAction.PLACED, Items.BRICKS, 100, 5_000, 50_000),
        tiers("swum", DeedCategory.BLOCKS, DeedAction.TRAVELED, Items.WATER_BUCKET, 100, 5_000, targets = liquids()),
        tiers("crafted", DeedCategory.ITEMS, DeedAction.CRAFTED, Items.CRAFTING_TABLE, 50, 2_000),
        tiers("eaten", DeedCategory.ITEMS, DeedAction.EATEN, Items.COOKED_BEEF, 10, 500),
        tiers("fished", DeedCategory.ITEMS, DeedAction.FISHED, Items.FISHING_ROD, 10, 200),
        tiers("killed", DeedCategory.ENTITIES, DeedAction.KILLED, Items.IRON_SWORD, 10, 500, 5_000),
        tiers("bred", DeedCategory.ENTITIES, DeedAction.BRED, Items.WHEAT, 10, 200),
        tiers("traded", DeedCategory.ENTITIES, DeedAction.TRADED, Items.EMERALD, 10, 500),
        tiers("ridden_distance", DeedCategory.ENTITIES, DeedAction.TRAVELED, Items.SADDLE, 100, 5_000, 50_000, targets = rideable()),
        tiers("traveled", DeedCategory.BIOMES, DeedAction.TRAVELED, Items.LEATHER_BOOTS, 1_000, 50_000, 500_000),
        tiers("time_spent", DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, Items.CLOCK, HOUR, 50 * HOUR),
        tiers("looted", DeedCategory.STRUCTURES, DeedAction.LOOTED, Items.CHEST, 5, 100),

        tiers("used_blocks", DeedCategory.BLOCKS, DeedAction.USED, Items.LEVER, 100, 1_000, 10_000),
        tiers("grown", DeedCategory.BLOCKS, DeedAction.GROWN, Items.BONE_MEAL, 50, 500, 5_000),
        tiers("hurt_by_blocks", DeedCategory.BLOCKS, DeedAction.DAMAGED_BY, Items.CACTUS, 10, 100),
        tiers("obtained", DeedCategory.ITEMS, DeedAction.OBTAINED, Items.BUNDLE, 1_000, 10_000, 100_000),
        tiers("used_items", DeedCategory.ITEMS, DeedAction.USED, Items.BOW, 1_000, 10_000, 100_000),
        tiers("brewed", DeedCategory.ITEMS, DeedAction.BREWED, Items.BREWING_STAND, 10, 100, 1_000),
        tiers("enchanted", DeedCategory.ITEMS, DeedAction.ENCHANTED, Items.ENCHANTING_TABLE, 10, 100, 500),
        tiers("hurt_by_mobs", DeedCategory.ENTITIES, DeedAction.DAMAGED_BY, Items.SHIELD, 100, 1_000, 10_000),
        tiers("tamed", DeedCategory.ENTITIES, DeedAction.TAMED, Items.BONE, 5, 25, 100),
        tiers("mounted", DeedCategory.ENTITIES, DeedAction.RIDDEN, Items.LEAD, 10, 100, 1_000),
        tiers("biomes_visited", DeedCategory.BIOMES, DeedAction.VISITED, Items.COMPASS, 100, 1_000),
        tiers("dimensions_visited", DeedCategory.DIMENSIONS, DeedAction.VISITED, Items.ENDER_EYE, 10, 100),
        tiers("structures_seen", DeedCategory.STRUCTURES, DeedAction.SEEN, Items.SPYGLASS, 10, 100, 1_000),
        tiers("structures_visited", DeedCategory.STRUCTURES, DeedAction.VISITED, Items.FILLED_MAP, 10, 100, 1_000),
        tiers("effects", DeedCategory.EFFECTS, DeedAction.OBTAINED, Items.BEACON, 10, 100, 1_000)
    )

    private fun tiers(
        name: String, category: DeedCategory, action: DeedAction, icon: Item,
        vararg amounts: Int, targets: TargetSelector = everything()
    ): MilestoneTiers = MilestoneTiers(name, category, action, icon, amounts.toList(), targets)
}
