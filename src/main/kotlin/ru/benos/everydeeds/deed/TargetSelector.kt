package ru.benos.everydeeds.deed

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.Encoder
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * Which registry entries of the definition's category a deed applies to. A selector is expanded
 * against the live registries when definitions are indexed, so `"all"` rules automatically cover
 * content added by other mods.
 *
 * Accepted forms:
 * ```json
 * { "id": "minecraft:stone" }
 * { "tag": "minecraft:logs" }
 * { "all": { "traits": ["mineable"], "exclude": "everydeeds:technical" } }
 * ```
 */
sealed interface TargetSelector {
    data class Single(val id: Identifier) : TargetSelector

    data class Tag(val tag: Identifier) : TargetSelector

    data class All(val traits: List<TargetTrait>, val exclude: Optional<Identifier>) : TargetSelector

    companion object {
        private val SINGLE_CODEC: Codec<Single> =
            Identifier.CODEC.fieldOf("id").xmap(::Single, Single::id).codec()

        private val TAG_CODEC: Codec<Tag> =
            Identifier.CODEC.fieldOf("tag").xmap(::Tag, Tag::tag).codec()

        private val ALL_BODY_CODEC: Codec<All> = RecordCodecBuilder.create { instance ->
            instance.group(
                TargetTrait.CODEC.listOf().optionalFieldOf("traits", emptyList()).forGetter(All::traits),
                Identifier.CODEC.optionalFieldOf("exclude").forGetter(All::exclude)
            ).apply(instance, ::All)
        }

        private val ALL_CODEC: Codec<All> = ALL_BODY_CODEC.fieldOf("all").codec()

        private val DECODER: Codec<TargetSelector> = Codec.withAlternative(
            Codec.withAlternative(SINGLE_CODEC.widen(), TAG_CODEC.widen()),
            ALL_CODEC.widen()
        )

        /** Decodes whichever form is present; encodes through the branch matching the value. */
        val CODEC: Codec<TargetSelector> = Codec.of(
            object : Encoder<TargetSelector> {
                override fun <T> encode(input: TargetSelector, ops: DynamicOps<T>, prefix: T): DataResult<T> =
                    when (input) {
                        is Single -> SINGLE_CODEC.encode(input, ops, prefix)
                        is Tag -> TAG_CODEC.encode(input, ops, prefix)
                        is All -> ALL_CODEC.encode(input, ops, prefix)
                    }
            },
            DECODER,
            "TargetSelector"
        )

        @Suppress("UNCHECKED_CAST")
        private fun <T : TargetSelector> Codec<T>.widen(): Codec<TargetSelector> =
            xmap({ selector -> selector as TargetSelector }, { selector -> selector as T })
    }
}

/** Static properties of a registry entry used to keep impossible targets out of generated rules. */
enum class TargetTrait(private val id: String) : StringRepresentable {
    /** The crosshair can land on the block (it has an outline shape in some state) or it is a fluid. */
    TARGETABLE("targetable"),

    /** Block is targetable, breakable by a survival player and not a fluid. */
    MINEABLE("mineable"),

    /** Block is placed from an item that a survival player can obtain. */
    PLACEABLE("placeable"),

    /** Block has more than one block state. */
    HAS_STATES("has_states"),

    /** Block is a liquid (water, lava, modded fluids): the player can swim in it. */
    LIQUID("liquid"),

    /** Item (or the item that places the block) is the result of at least one loaded recipe. */
    CRAFTABLE("craftable"),

    /** Block reacts to a right click; item has a use (swing, shot, food, tool, equip...). */
    USABLE("usable"),

    /** At least one enchantment can be applied to the item. */
    ENCHANTABLE("enchantable"),

    /** Item has durability. */
    DAMAGEABLE("damageable"),

    /** Entity type is a living entity (has default attributes). */
    LIVING("living"),

    /** Item can be enchanted at an enchanting table (or is the enchanted book a book turns into). */
    TABLE_ENCHANTABLE("table_enchantable"),

    /** Item is food (has a food component). */
    EDIBLE("edible"),

    /** Status effect that a survival player can get at more than one level. */
    LEVELED("leveled"),

    /** Entity type can trade with players (villagers, wandering traders, modded merchants). */
    MERCHANT("merchant"),

    /** Entity type exposes enumerable variants (cat type, sheep colour...). */
    HAS_VARIANTS("has_variants");

    override fun getSerializedName(): String = id

    companion object {
        val CODEC: Codec<TargetTrait> = StringRepresentable.fromEnum { entries.toTypedArray() }
    }
}
