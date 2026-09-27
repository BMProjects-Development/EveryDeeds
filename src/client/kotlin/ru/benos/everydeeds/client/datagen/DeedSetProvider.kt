package ru.benos.everydeeds.client.datagen

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput
import net.fabricmc.fabric.api.datagen.v1.provider.FabricCodecDataProvider
import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.DeedDefinition
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.DeedTags
import ru.benos.everydeeds.deed.DeedVariant
import ru.benos.everydeeds.deed.TargetSelector
import ru.benos.everydeeds.deed.TargetTrait
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer

/**
 * Generates the built-in deed sets as a handful of rules each (not one file per object):
 *
 * 1. `short`    - one generic goal per action and object, no variations.
 * 2. `extended` - adds some variety: a few enchantments, block states, mob variants, dye colours.
 * 3. `insane`   - every variant counted separately (enchantment levels, property values), no combinations.
 * 4. `maniac`   - every combination and state: enchantment sets, full block states, durability, all RGB colours.
 *
 * Milestones come from [Milestones.CORE]; more of them ship as the built-in "More milestones" data pack
 * ([MoreMilestonesProvider]). A hand-written custom set showing predicates and rewards lives in `docs/datapack-example`.
 */
class DeedSetProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricCodecDataProvider<DeedDefinition>(
    output, registries, PackOutput.Target.DATA_PACK, "${EveryDeeds.MOD_ID}/deeds", DeedDefinition.CODEC
) {
    override fun getName(): String = "EveryDeeds deed sets"

    override fun configure(provider: BiConsumer<Identifier, DeedDefinition>, registries: HolderLookup.Provider) {
        fun emit(set: String, name: String, definition: DeedDefinition) =
            provider.accept(Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, "$set/$name"), definition)

        for (set in listOf(SHORT, EXTENDED, INSANE, MANIAC)) {
            baseRules().forEach { (name, definition) -> emit(set, name, definition) }
        }
        for ((set, scale) in Milestones.SCALES) {
            Milestones.CORE.flatMap { tiers -> tiers.definitions(scale) }.forEach { (name, definition) -> emit(set, name, definition) }
        }
        extendedRules().forEach { (name, definition) -> emit(EXTENDED, name, definition) }
        insaneRules().forEach { (name, definition) -> emit(INSANE, name, definition) }
        maniacRules().forEach { (name, definition) -> emit(MANIAC, name, definition) }
    }

    /** One of each action for every applicable object: the core of every built-in set. */
    private fun baseRules(): List<Pair<String, DeedDefinition>> = listOf(
        "blocks/seen" to rule(DeedCategory.BLOCKS, DeedAction.SEEN, all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.TARGETABLE)),
        "blocks/broken" to rule(DeedCategory.BLOCKS, DeedAction.BROKEN, all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.MINEABLE)),
        "blocks/placed" to rule(DeedCategory.BLOCKS, DeedAction.PLACED, all(DeedTags.UNOBTAINABLE_BLOCKS, TargetTrait.PLACEABLE)),
        "blocks/used" to rule(DeedCategory.BLOCKS, DeedAction.USED, all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.TARGETABLE, TargetTrait.USABLE)),
        "blocks/crafted" to rule(DeedCategory.BLOCKS, DeedAction.CRAFTED, all(DeedTags.UNOBTAINABLE_BLOCKS, TargetTrait.CRAFTABLE)),
        "blocks/grown" to rule(DeedCategory.BLOCKS, DeedAction.GROWN, TargetSelector.Tag(DeedTags.GROWABLE_BLOCKS.location())),
        "blocks/damaged_by" to rule(DeedCategory.BLOCKS, DeedAction.DAMAGED_BY, TargetSelector.Tag(DeedTags.DAMAGING_BLOCKS.location())),
        "items/obtained" to rule(DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS)),
        "items/used" to rule(DeedCategory.ITEMS, DeedAction.USED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.USABLE)),
        "items/fished" to rule(DeedCategory.ITEMS, DeedAction.FISHED, TargetSelector.Tag(DeedTags.FISHABLE_ITEMS.location())),
        "items/brewed" to rule(DeedCategory.ITEMS, DeedAction.BREWED, TargetSelector.Tag(DeedTags.BREWABLE_ITEMS.location())),
        "items/enchanted" to rule(DeedCategory.ITEMS, DeedAction.ENCHANTED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.TABLE_ENCHANTABLE)),
        "items/eaten" to rule(DeedCategory.ITEMS, DeedAction.EATEN, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.EDIBLE)),
        "items/crafted" to rule(DeedCategory.ITEMS, DeedAction.CRAFTED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.CRAFTABLE)),
        "entities/seen" to rule(DeedCategory.ENTITIES, DeedAction.SEEN, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING)),
        "entities/killed" to rule(DeedCategory.ENTITIES, DeedAction.KILLED, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING)),
        "entities/damaged_by" to rule(DeedCategory.ENTITIES, DeedAction.DAMAGED_BY, TargetSelector.Tag(DeedTags.ATTACKERS.location())),
        "entities/tamed" to rule(DeedCategory.ENTITIES, DeedAction.TAMED, TargetSelector.Tag(DeedTags.TAMEABLE.location())),
        "entities/bred" to rule(DeedCategory.ENTITIES, DeedAction.BRED, TargetSelector.Tag(DeedTags.BREEDABLE.location())),
        "entities/ridden" to rule(DeedCategory.ENTITIES, DeedAction.RIDDEN, rideable()),
        "entities/traded" to rule(DeedCategory.ENTITIES, DeedAction.TRADED, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.MERCHANT)),
        "biomes/visited" to rule(DeedCategory.BIOMES, DeedAction.VISITED, everything()),
        "dimensions/visited" to rule(DeedCategory.DIMENSIONS, DeedAction.VISITED, everything()),
        "effects/obtained" to rule(
            DeedCategory.EFFECTS, DeedAction.OBTAINED, TargetSelector.All(emptyList(), Optional.of(DeedTags.UNOBTAINABLE_EFFECTS.location()))
        ),
        "structures/seen" to rule(DeedCategory.STRUCTURES, DeedAction.SEEN, everything()),
        "structures/visited" to rule(DeedCategory.STRUCTURES, DeedAction.VISITED, everything()),
        "structures/looted" to rule(
            DeedCategory.STRUCTURES, DeedAction.LOOTED, TargetSelector.All(emptyList(), Optional.of(DeedTags.LOOTLESS_STRUCTURES.location()))
        )
    )

    private fun extendedRules(): List<Pair<String, DeedDefinition>> = listOf(
        "effects/levels" to rule(
            DeedCategory.EFFECTS, DeedAction.OBTAINED,
            TargetSelector.All(listOf(TargetTrait.LEVELED), Optional.of(DeedTags.UNOBTAINABLE_EFFECTS.location())),
            uniqueAll(DeedVariant.EachEffectLevel)
        ),
        "items/brewed_potions" to rule(
            DeedCategory.ITEMS, DeedAction.BREWED, TargetSelector.Tag(DeedTags.BREWABLE_ITEMS.location()), unique(DeedVariant.EachPotion, 10)
        ),
        "biomes/traveled" to rule(DeedCategory.BIOMES, DeedAction.TRAVELED, everything(), DeedGoal.Count(256)),
        "blocks/swum" to rule(DeedCategory.BLOCKS, DeedAction.TRAVELED, liquids(), DeedGoal.Count(256)),
        "entities/ridden_distance" to rule(DeedCategory.ENTITIES, DeedAction.TRAVELED, rideable(), DeedGoal.Count(256)),
        "dimensions/time_spent" to rule(DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, everything(), DeedGoal.Count(HOUR)),
        "items/obtained_enchantments" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.ENCHANTABLE),
            unique(DeedVariant.EachEnchantment, 3)
        ),
        "items/obtained_dyed" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, TargetSelector.Tag(DeedTags.DYEABLE_ITEMS.location()),
            unique(DeedVariant.DyedColor, 4)
        ),
        "blocks/placed_states" to rule(
            DeedCategory.BLOCKS, DeedAction.PLACED, all(DeedTags.UNOBTAINABLE_BLOCKS, TargetTrait.PLACEABLE, TargetTrait.HAS_STATES),
            unique(DeedVariant.FullBlockState, 4)
        ),
        "entities/killed_variants" to rule(
            DeedCategory.ENTITIES, DeedAction.KILLED, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING, TargetTrait.HAS_VARIANTS),
            unique(DeedVariant.EachEntityVariant, 3)
        )
    )

    private fun insaneRules(): List<Pair<String, DeedDefinition>> = listOf(
        "effects/levels" to rule(
            DeedCategory.EFFECTS, DeedAction.OBTAINED,
            TargetSelector.All(listOf(TargetTrait.LEVELED), Optional.of(DeedTags.UNOBTAINABLE_EFFECTS.location())),
            uniqueAll(DeedVariant.EachEffectLevel)
        ),
        "items/brewed_potions" to rule(
            DeedCategory.ITEMS, DeedAction.BREWED, TargetSelector.Tag(DeedTags.BREWABLE_ITEMS.location()), uniqueAll(DeedVariant.EachPotion)
        ),
        "biomes/traveled" to rule(DeedCategory.BIOMES, DeedAction.TRAVELED, everything(), DeedGoal.Count(1_000)),
        "blocks/swum" to rule(DeedCategory.BLOCKS, DeedAction.TRAVELED, liquids(), DeedGoal.Count(1_000)),
        "entities/ridden_distance" to rule(DeedCategory.ENTITIES, DeedAction.TRAVELED, rideable(), DeedGoal.Count(1_000)),
        "dimensions/time_spent" to rule(DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, everything(), DeedGoal.Count(10 * HOUR)),
        "items/obtained_enchantment_levels" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.ENCHANTABLE),
            uniqueAll(DeedVariant.EachEnchantmentLevel)
        ),
        "items/obtained_dyed" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, TargetSelector.Tag(DeedTags.DYEABLE_ITEMS.location()),
            unique(DeedVariant.DyedColor, 16)
        ),
        "blocks/placed_properties" to rule(
            DeedCategory.BLOCKS, DeedAction.PLACED, all(DeedTags.UNOBTAINABLE_BLOCKS, TargetTrait.PLACEABLE, TargetTrait.HAS_STATES),
            uniqueAll(DeedVariant.EachBlockProperty)
        ),
        "blocks/seen_properties" to rule(
            DeedCategory.BLOCKS, DeedAction.SEEN, all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.TARGETABLE, TargetTrait.HAS_STATES),
            uniqueAll(DeedVariant.EachBlockProperty)
        ),
        "entities/killed_variants" to rule(
            DeedCategory.ENTITIES, DeedAction.KILLED, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING, TargetTrait.HAS_VARIANTS),
            uniqueAll(DeedVariant.EachEntityVariant)
        )
    )

    private fun maniacRules(): List<Pair<String, DeedDefinition>> = listOf(
        "effects/levels" to rule(
            DeedCategory.EFFECTS, DeedAction.OBTAINED,
            TargetSelector.All(listOf(TargetTrait.LEVELED), Optional.of(DeedTags.UNOBTAINABLE_EFFECTS.location())),
            uniqueAll(DeedVariant.EachEffectLevel)
        ),
        "biomes/traveled" to rule(DeedCategory.BIOMES, DeedAction.TRAVELED, everything(), DeedGoal.Count(10_000)),
        "blocks/swum" to rule(DeedCategory.BLOCKS, DeedAction.TRAVELED, liquids(), DeedGoal.Count(10_000)),
        "entities/ridden_distance" to rule(DeedCategory.ENTITIES, DeedAction.TRAVELED, rideable(), DeedGoal.Count(10_000)),
        "dimensions/time_spent" to rule(DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, everything(), DeedGoal.Count(100 * HOUR)),
        "items/obtained_enchantment_levels" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.ENCHANTABLE),
            uniqueAll(DeedVariant.EachEnchantmentLevel)
        ),
        "items/obtained_enchantment_combinations" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.ENCHANTABLE),
            uniqueAll(DeedVariant.EnchantmentCombination)
        ),
        "items/obtained_dyed" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, TargetSelector.Tag(DeedTags.DYEABLE_ITEMS.location()),
            uniqueAll(DeedVariant.DyedColor)
        ),
        // Every durability value an item has had in the inventory: wearing a tool down counts.
        "items/obtained_durability" to rule(
            DeedCategory.ITEMS, DeedAction.OBTAINED, all(DeedTags.UNOBTAINABLE_ITEMS, TargetTrait.DAMAGEABLE),
            uniqueAll(DeedVariant.Durability)
        ),
        "blocks/placed_states" to rule(
            DeedCategory.BLOCKS, DeedAction.PLACED, all(DeedTags.UNOBTAINABLE_BLOCKS, TargetTrait.PLACEABLE, TargetTrait.HAS_STATES),
            uniqueAll(DeedVariant.FullBlockState)
        ),
        "blocks/seen_states" to rule(
            DeedCategory.BLOCKS, DeedAction.SEEN, all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.TARGETABLE, TargetTrait.HAS_STATES),
            uniqueAll(DeedVariant.FullBlockState)
        ),
        "entities/seen_variants" to rule(
            DeedCategory.ENTITIES, DeedAction.SEEN, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING, TargetTrait.HAS_VARIANTS),
            uniqueAll(DeedVariant.EachEntityVariant)
        ),
        "entities/killed_variant_combinations" to rule(
            DeedCategory.ENTITIES, DeedAction.KILLED, all(DeedTags.TECHNICAL_ENTITIES, TargetTrait.LIVING, TargetTrait.HAS_VARIANTS),
            uniqueAll(DeedVariant.EntityVariantCombination)
        )
    )

    private fun rule(
        category: DeedCategory,
        action: DeedAction,
        targets: TargetSelector,
        goal: DeedGoal = DeedGoal.Count(1)
    ): DeedDefinition =
        DeedDefinition(category, action, targets, Optional.empty(), goal, Optional.empty(), Optional.empty())

    private fun unique(variant: DeedVariant, amount: Long): DeedGoal =
        DeedGoal.Unique(variant, DeedGoal.Required.Fixed(amount))

    private fun uniqueAll(variant: DeedVariant): DeedGoal =
        DeedGoal.Unique(variant, DeedGoal.Required.All)

    companion object {
        const val SHORT = "short"
        const val EXTENDED = "extended"
        const val INSANE = "insane"
        const val MANIAC = "maniac"

        /** Time goals are in seconds. */
        const val HOUR = 3600
    }
}
