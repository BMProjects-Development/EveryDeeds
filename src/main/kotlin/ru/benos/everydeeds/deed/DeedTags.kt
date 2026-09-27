package ru.benos.everydeeds.deed

import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.Structure
import ru.benos.everydeeds.EveryDeeds

/**
 * Tags the built-in deed sets use to keep technical and survival-unobtainable content out of
 * `all` rules. They are ordinary datapack tags: packs can extend or replace them.
 */
object DeedTags {
    /** Blocks that are never a meaningful thing to see or mine (air, technical blocks). */
    val TECHNICAL_BLOCKS: TagKey<Block> = tag(Registries.BLOCK, "technical")

    /** Blocks a survival player cannot get as an item, so cannot place. */
    val UNOBTAINABLE_BLOCKS: TagKey<Block> = tag(Registries.BLOCK, "unobtainable")

    /** Items a survival player cannot get (spawn eggs, operator items...). */
    val UNOBTAINABLE_ITEMS: TagKey<Item> = tag(Registries.ITEM, "unobtainable")

    /** Items that accept a dye colour. */
    val DYEABLE_ITEMS: TagKey<Item> = tag(Registries.ITEM, "dyeable")

    /** Blocks that deal damage by themselves: touched, stood in or on, fallen on (cactus, magma, fire), or falling (anvils). */
    val DAMAGING_BLOCKS: TagKey<Block> = tag(Registries.BLOCK, "damaging")

    /** Plants a player can make grow: crops (harvested mature) and bone-mealable saplings, fungi, berries. */
    val GROWABLE_BLOCKS: TagKey<Block> = tag(Registries.BLOCK, "growable")

    /** Structures without loot containers or suspicious blocks: nothing to loot. */
    val LOOTLESS_STRUCTURES: TagKey<Structure> = tag(Registries.STRUCTURE, "lootless")

    /** Items a fishing rod can bring up (fish, treasure, junk). */
    val FISHABLE_ITEMS: TagKey<Item> = tag(Registries.ITEM, "fishable")

    /** Items that come out of a brewing stand. */
    val BREWABLE_ITEMS: TagKey<Item> = tag(Registries.ITEM, "brewable")

    /** Animals a player can tame. */
    val TAMEABLE: TagKey<EntityType<*>> = tag(Registries.ENTITY_TYPE, "tameable")

    /** Animals a player can breed. */
    val BREEDABLE: TagKey<EntityType<*>> = tag(Registries.ENTITY_TYPE, "breedable")

    /** Entity types a player can mount. */
    val RIDEABLE: TagKey<EntityType<*>> = tag(Registries.ENTITY_TYPE, "rideable")

    /** Status effects a survival player can never get (only through commands). */
    val UNOBTAINABLE_EFFECTS: TagKey<MobEffect> = tag(Registries.MOB_EFFECT, "unobtainable")

    /** Entity types that are not regular mobs (players, unused mobs, technical entities). */
    val TECHNICAL_ENTITIES: TagKey<EntityType<*>> = tag(Registries.ENTITY_TYPE, "technical")

    /** Entity types that can deal damage to a player. */
    val ATTACKERS: TagKey<EntityType<*>> = tag(Registries.ENTITY_TYPE, "attackers")

    private fun <T : Any> tag(registry: ResourceKey<out Registry<T>>, path: String): TagKey<T> =
        TagKey.create(registry, Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, path))
}
