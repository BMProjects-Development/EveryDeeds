package ru.benos.everydeeds.client.datagen

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.BlockTags
import net.minecraft.tags.EntityTypeTags
import net.minecraft.tags.TagKey
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.DefaultAttributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.Structure
import ru.benos.everydeeds.deed.DeedTags
import java.util.concurrent.CompletableFuture

/*
 * Best-effort vanilla lists. Entries are added as optional, so a list mentioning content that a
 * given version does not have still loads.
 */

private val TECHNICAL_BLOCK_IDS = listOf(
    "air", "cave_air", "void_air", "moving_piston", "barrier", "light", "structure_void",
    "jigsaw", "structure_block", "command_block", "chain_command_block", "repeating_command_block",
    "test_block", "test_instance_block", "bubble_column",
    // Parts of other blocks, or never present in a survival world.
    "piston_head", "petrified_oak_slab", "player_head", "player_wall_head"
)

private val UNOBTAINABLE_BLOCK_IDS = TECHNICAL_BLOCK_IDS + listOf(
    "bedrock", "spawner", "trial_spawner", "vault", "budding_amethyst", "reinforced_deepslate",
    "end_portal_frame", "petrified_oak_slab", "player_head", "player_wall_head", "suspicious_sand",
    "suspicious_gravel", "dirt_path", "farmland", "chorus_plant", "frogspawn",
    "infested_stone", "infested_cobblestone", "infested_stone_bricks", "infested_mossy_stone_bricks",
    "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks", "infested_deepslate"
)

private val UNOBTAINABLE_ITEM_IDS = UNOBTAINABLE_BLOCK_IDS + listOf(
    "command_block_minecart", "debug_stick", "knowledge_book"
)

/** Growable plants outside the crop and sapling tags. */
private val GROWABLE_BLOCK_IDS = listOf(
    "cocoa", "nether_wart", "sweet_berry_bush", "cave_vines", "bamboo_sapling",
    "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus"
)

private val DYEABLE_ITEM_IDS = listOf(
    "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
    "leather_horse_armor", "wolf_armor"
)

private val TECHNICAL_ENTITY_IDS = listOf("player", "giant", "illusioner", "mannequin")

/** Mobs outside the MONSTER category that still hurt players. */
private val NEUTRAL_ATTACKER_IDS = listOf(
    "wolf", "bee", "iron_golem", "polar_bear", "llama", "trader_llama", "panda", "goat",
    "dolphin", "pufferfish"
)

/** Fishing loot (fish, junk and treasure tables). */
private val FISHABLE_ITEM_IDS = listOf(
    "cod", "salmon", "tropical_fish", "pufferfish",
    "lily_pad", "leather_boots", "leather", "bone", "potion", "string", "fishing_rod", "bowl", "stick", "ink_sac",
    "tripwire_hook", "rotten_flesh", "bamboo",
    "bow", "enchanted_book", "name_tag", "nautilus_shell", "saddle"
)

private val TAMEABLE_ENTITY_IDS = listOf(
    "wolf", "cat", "parrot", "horse", "donkey", "mule", "llama", "trader_llama", "camel", "skeleton_horse", "zombie_horse"
)

private val BREEDABLE_ENTITY_IDS = listOf(
    "cow", "mooshroom", "sheep", "pig", "chicken", "rabbit", "wolf", "cat", "ocelot", "horse", "donkey", "llama",
    "trader_llama", "fox", "panda", "bee", "turtle", "strider", "hoglin", "goat", "axolotl", "frog", "camel", "sniffer",
    "armadillo", "mule"
)

/** Blocks that hurt the player by themselves: see [DeedTags.DAMAGING_BLOCKS]. */
private val DAMAGING_BLOCK_IDS = listOf(
    "cactus", "sweet_berry_bush", "magma_block", "fire", "soul_fire", "campfire", "soul_campfire", "lava", "lava_cauldron",
    "powder_snow", "pointed_dripstone", "sulfur_spike", "anvil", "chipped_anvil", "damaged_anvil"
)

/**
 * Everything a player can sit on; boats come from the vanilla boat tag. Of the minecarts only the plain
 * one takes a passenger: the others open a chest or hopper, take fuel or get lit when clicked.
 */
private val RIDEABLE_ENTITY_IDS = listOf(
    "horse", "donkey", "mule", "skeleton_horse", "zombie_horse", "camel", "camel_husk", "llama", "trader_llama",
    "pig", "strider", "happy_ghast", "nautilus", "zombie_nautilus", "minecart"
)

/** MONSTER-category mounts that never attack by themselves. */
private val NON_ATTACKING_MONSTER_IDS = listOf("zombie_horse", "camel_husk")

private fun <T : Any> key(registry: ResourceKey<out Registry<T>>, path: String): ResourceKey<T> =
    ResourceKey.create(registry, Identifier.withDefaultNamespace(path))

class DeedBlockTagProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricTagsProvider<Block>(output, Registries.BLOCK, registries) {
    override fun addTags(registries: HolderLookup.Provider) {
        val technical = builder(DeedTags.TECHNICAL_BLOCKS)
        TECHNICAL_BLOCK_IDS.forEach { id -> technical.addOptional(key(Registries.BLOCK, id)) }

        val unobtainable = builder(DeedTags.UNOBTAINABLE_BLOCKS)
        UNOBTAINABLE_BLOCK_IDS.forEach { id -> unobtainable.addOptional(key(Registries.BLOCK, id)) }

        val growable = builder(DeedTags.GROWABLE_BLOCKS)
        growable.addOptionalTag(BlockTags.CROPS)
        growable.addOptionalTag(BlockTags.SAPLINGS)
        // Convention tag: modded crops join automatically.
        growable.addOptionalTag(TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "crops")))
        GROWABLE_BLOCK_IDS.forEach { id -> growable.addOptional(key(Registries.BLOCK, id)) }

        val damaging = builder(DeedTags.DAMAGING_BLOCKS)
        DAMAGING_BLOCK_IDS.forEach { id -> damaging.addOptional(key(Registries.BLOCK, id)) }
    }
}

class DeedItemTagProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricTagsProvider<Item>(output, Registries.ITEM, registries) {
    override fun addTags(registries: HolderLookup.Provider) {
        val unobtainable = builder(DeedTags.UNOBTAINABLE_ITEMS)
        UNOBTAINABLE_ITEM_IDS.forEach { id -> unobtainable.addOptional(key(Registries.ITEM, id)) }
        // Spawn eggs cannot be obtained in survival.
        BuiltInRegistries.ITEM.entrySet()
            .filter { (_, item) -> item is SpawnEggItem }
            .sortedBy { (itemKey, _) -> itemKey.identifier().toString() }
            .forEach { (itemKey, _) -> unobtainable.add(itemKey) }

        val fishable = builder(DeedTags.FISHABLE_ITEMS)
        FISHABLE_ITEM_IDS.forEach { id -> fishable.addOptional(key(Registries.ITEM, id)) }

        val brewable = builder(DeedTags.BREWABLE_ITEMS)
        listOf("potion", "splash_potion", "lingering_potion").forEach { id -> brewable.addOptional(key(Registries.ITEM, id)) }

        val dyeable = builder(DeedTags.DYEABLE_ITEMS)
        DYEABLE_ITEM_IDS.forEach { id -> dyeable.addOptional(key(Registries.ITEM, id)) }
    }
}

class DeedEntityTypeTagProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricTagsProvider<EntityType<*>>(output, Registries.ENTITY_TYPE, registries) {
    override fun addTags(registries: HolderLookup.Provider) {
        val technical = builder(DeedTags.TECHNICAL_ENTITIES)
        TECHNICAL_ENTITY_IDS.forEach { id -> technical.addOptional(key(Registries.ENTITY_TYPE, id)) }

        val attackers = builder(DeedTags.ATTACKERS)
        BuiltInRegistries.ENTITY_TYPE.entrySet()
            .filter { (typeKey, type) ->
                type.category == MobCategory.MONSTER &&
                    DefaultAttributes.hasSupplier(type) &&
                    typeKey.identifier().path !in TECHNICAL_ENTITY_IDS &&
                    typeKey.identifier().path !in NON_ATTACKING_MONSTER_IDS
            }
            .sortedBy { (typeKey, _) -> typeKey.identifier().toString() }
            .forEach { (typeKey, _) -> attackers.add(typeKey) }
        NEUTRAL_ATTACKER_IDS.forEach { id -> attackers.addOptional(key(Registries.ENTITY_TYPE, id)) }

        val tameable = builder(DeedTags.TAMEABLE)
        TAMEABLE_ENTITY_IDS.forEach { id -> tameable.addOptional(key(Registries.ENTITY_TYPE, id)) }

        val breedable = builder(DeedTags.BREEDABLE)
        BREEDABLE_ENTITY_IDS.forEach { id -> breedable.addOptional(key(Registries.ENTITY_TYPE, id)) }

        val rideable = builder(DeedTags.RIDEABLE)
        RIDEABLE_ENTITY_IDS.forEach { id -> rideable.addOptional(key(Registries.ENTITY_TYPE, id)) }
        rideable.addOptionalTag(EntityTypeTags.BOAT)
    }
}

/** Vanilla structures without loot containers or suspicious blocks. */
private val LOOTLESS_STRUCTURE_IDS = listOf("monument", "swamp_hut", "nether_fossil")

class DeedStructureTagProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricTagsProvider<Structure>(output, Registries.STRUCTURE, registries) {
    override fun addTags(registries: HolderLookup.Provider) {
        val lootless = builder(DeedTags.LOOTLESS_STRUCTURES)
        LOOTLESS_STRUCTURE_IDS.forEach { id -> lootless.addOptional(key(Registries.STRUCTURE, id)) }
    }
}

/** Effects only commands can give. */
private val UNOBTAINABLE_EFFECT_IDS = listOf("luck", "unluck", "health_boost")

class DeedEffectTagProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricTagsProvider<MobEffect>(output, Registries.MOB_EFFECT, registries) {
    override fun addTags(registries: HolderLookup.Provider) {
        val unobtainable = builder(DeedTags.UNOBTAINABLE_EFFECTS)
        UNOBTAINABLE_EFFECT_IDS.forEach { id -> unobtainable.addOptional(key(Registries.MOB_EFFECT, id)) }
    }
}
