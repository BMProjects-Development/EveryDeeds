package ru.benos.everydeeds.deed

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

/** The kinds of things a deed can be about. */
enum class DeedCategory(private val id: String) : StringRepresentable {
    BLOCKS("blocks"),
    ITEMS("items"),
    ENTITIES("entities"),

    /** Data-driven: the biomes the world's generators can actually produce. */
    BIOMES("biomes"),

    /** Data-driven: the dimensions (levels) of the world. */
    DIMENSIONS("dimensions"),

    /** Data-driven: the structures the world can generate. */
    STRUCTURES("structures"),

    /** Status effects (speed, poison...). */
    EFFECTS("effects");

    override fun getSerializedName(): String = id

    /** Actions tracked for this category, in display order. */
    val actions: List<DeedAction>
        get() = DeedAction.entries.filter { action -> this in action.categories }

    companion object {
        val CODEC: Codec<DeedCategory> = StringRepresentable.fromEnum { entries.toTypedArray() }
    }
}

/**
 * Something a player does with a block, item or entity.
 *
 * The same semantic action is shared between categories instead of being duplicated per category:
 * "seen" is one action that applies to both blocks and entities.
 */
enum class DeedAction(private val id: String, val categories: Set<DeedCategory>) : StringRepresentable {
    SEEN("seen", setOf(DeedCategory.BLOCKS, DeedCategory.ENTITIES, DeedCategory.STRUCTURES)),

    /** The player broke the block (any tool, or none). */
    BROKEN("broken", setOf(DeedCategory.BLOCKS)),
    PLACED("placed", setOf(DeedCategory.BLOCKS)),

    /** The item has been in the player's inventory: picked up, crafted, traded, taken from a chest... */
    OBTAINED("obtained", setOf(DeedCategory.ITEMS, DeedCategory.EFFECTS)),

    /** Blocks: a right click the block reacted to. Items: the vanilla "used" statistic (swing, shot, eaten...). */
    USED("used", setOf(DeedCategory.BLOCKS, DeedCategory.ITEMS)),

    /** Caught with a fishing rod. */
    FISHED("fished", setOf(DeedCategory.ITEMS)),

    /** Taken out of a brewing stand. */
    BREWED("brewed", setOf(DeedCategory.ITEMS)),

    /** Enchanted at an enchanting table (the result item: a book becomes an enchanted book). */
    ENCHANTED("enchanted", setOf(DeedCategory.ITEMS)),

    /** Food the player finished eating (items with a food component). */
    EATEN("eaten", setOf(DeedCategory.ITEMS)),

    /** Produced at a crafting station: crafting grid, furnaces, stonecutter, smithing table, loom... (not trades). */
    CRAFTED("crafted", setOf(DeedCategory.BLOCKS, DeedCategory.ITEMS)),

    /** A plant the player made grow: a mature crop harvested, or bone meal that took effect. */
    GROWN("grown", setOf(DeedCategory.BLOCKS)),
    KILLED("killed", setOf(DeedCategory.ENTITIES)),

    /** The player took damage from an entity of this type, or from the block (cactus, magma, fire, a falling anvil...). */
    DAMAGED_BY("damaged_by", setOf(DeedCategory.BLOCKS, DeedCategory.ENTITIES)),

    /** The player completed a trade with a merchant of this type. */
    TRADED("traded", setOf(DeedCategory.ENTITIES)),

    /** The player tamed an animal of this type (wolves, cats, horses, parrots...). */
    TAMED("tamed", setOf(DeedCategory.ENTITIES)),

    /** The player bred two animals; counted for the baby's type. */
    BRED("bred", setOf(DeedCategory.ENTITIES)),

    /** The player mounted an entity of this type (horse, pig, strider, boat...). */
    RIDDEN("ridden", setOf(DeedCategory.ENTITIES)),

    /** The player entered the biome, dimension or structure (counted per entry). */
    VISITED("visited", setOf(DeedCategory.BIOMES, DeedCategory.DIMENSIONS, DeedCategory.STRUCTURES)),

    /** The player opened a loot container (or brushed a suspicious block) in the structure. */
    LOOTED("looted", setOf(DeedCategory.STRUCTURES)),

    /**
     * A distance in blocks. Biomes: walked, swum, ridden or flown horizontally inside the biome.
     * Blocks: swum in the liquid (water, lava). Entities: ridden on the entity (boat, horse, minecart...).
     */
    TRAVELED("traveled", setOf(DeedCategory.BIOMES, DeedCategory.BLOCKS, DeedCategory.ENTITIES)),

    /** Seconds spent in the dimension. */
    TIME_SPENT("time_spent", setOf(DeedCategory.DIMENSIONS));

    override fun getSerializedName(): String = id

    companion object {
        val CODEC: Codec<DeedAction> = StringRepresentable.fromEnum { entries.toTypedArray() }
    }
}
