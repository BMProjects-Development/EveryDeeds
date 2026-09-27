package ru.benos.everydeeds.client.datagen

import net.minecraft.tags.TagKey
import ru.benos.everydeeds.deed.DeedTags
import ru.benos.everydeeds.deed.TargetSelector
import ru.benos.everydeeds.deed.TargetTrait
import java.util.Optional

/*
 * Target selectors shared by the generated deed rules and milestones.
 */

/** Every entry of the category that has all [traits], except those in the [exclude] tag. */
internal fun all(exclude: TagKey<*>, vararg traits: TargetTrait): TargetSelector =
    TargetSelector.All(traits.toList(), Optional.of(exclude.location()))

/** Every target of a data-driven category (the index keeps only reachable biomes and existing dimensions). */
internal fun everything(): TargetSelector = TargetSelector.All(emptyList(), Optional.empty())

/** Liquids a player can swim in: water, lava and modded fluids. */
internal fun liquids(): TargetSelector = all(DeedTags.TECHNICAL_BLOCKS, TargetTrait.LIQUID)

/** Entities a player can ride: mounts, boats, the minecart. */
internal fun rideable(): TargetSelector = TargetSelector.Tag(DeedTags.RIDEABLE.location())
