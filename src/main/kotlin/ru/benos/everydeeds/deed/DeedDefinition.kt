package ru.benos.everydeeds.deed

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.Identifier
import net.minecraft.util.ExtraCodecs
import net.minecraft.world.item.ItemStackTemplate
import java.util.Optional

/**
 * How much of an action completes a deed.
 *
 * ```json
 * { "type": "count", "count": 10 }
 * { "type": "unique", "variant": "enchantment", "required": "all" }
 * { "type": "unique", "variant": "dyed_color", "required": 64 }
 * ```
 */
sealed interface DeedGoal {
    /** Perform the action [count] times. */
    data class Count(val count: Int) : DeedGoal

    /**
     * Perform the action with [required] distinct values of [variant]. Progress is the set of
     * variants actually produced by the player, never the theoretical space.
     */
    data class Unique(val variant: DeedVariant, val required: Required) : DeedGoal

    /** Either a fixed number, or every value of the variant space for the target. */
    sealed interface Required {
        data class Fixed(val amount: Long) : Required
        data object All : Required

        companion object {
            val CODEC: Codec<Required> = Codec.either(Codec.LONG, Codec.STRING).comapFlatMap(
                { either ->
                    either.map(
                        { amount -> if (amount > 0) DataResult.success(Fixed(amount)) else DataResult.error { "required must be positive" } },
                        { text -> if (text == "all") DataResult.success(All) else DataResult.error { "required must be a number or \"all\"" } }
                    )
                },
                { required ->
                    when (required) {
                        is Fixed -> Either.left(required.amount)
                        All -> Either.right("all")
                    }
                }
            )
        }
    }

    companion object {
        private val COUNT_CODEC = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(Count::count)
            ).apply(instance, ::Count)
        }

        private val UNIQUE_CODEC = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                DeedVariant.CODEC.fieldOf("variant").forGetter(Unique::variant),
                Required.CODEC.optionalFieldOf("required", Required.All).forGetter(Unique::required)
            ).apply(instance, ::Unique)
        }

        /** A misspelled type is an error, not silently a count goal. */
        private val TYPE_CODEC: Codec<String> = Codec.STRING.validate { type ->
            if (type == "count" || type == "unique") DataResult.success(type)
            else DataResult.error { "Unknown goal type '$type', expected \"count\" or \"unique\"" }
        }

        val CODEC: Codec<DeedGoal> = TYPE_CODEC.dispatch(
            "type",
            { goal ->
                when (goal) {
                    is Count -> "count"
                    is Unique -> "unique"
                }
            },
            { type ->
                when (type) {
                    "unique" -> UNIQUE_CODEC
                    else -> COUNT_CODEC
                }
            }
        )
    }
}

/** Optional reward granted once when a deed completes. The set author decides whether to use it. */
data class DeedReward(
    val experience: Int,
    val items: List<ItemStackTemplate>,
    val function: Optional<Identifier>
) {
    companion object {
        val CODEC: Codec<DeedReward> = RecordCodecBuilder.create { instance ->
            instance.group(
                ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("experience", 0).forGetter(DeedReward::experience),
                ItemStackTemplate.CODEC.listOf().optionalFieldOf("items", emptyList()).forGetter(DeedReward::items),
                Identifier.CODEC.optionalFieldOf("function").forGetter(DeedReward::function)
            ).apply(instance, ::DeedReward)
        }
    }
}

/**
 * A data-driven deed rule, loaded from `data/<namespace>/everydeeds/deeds/<set>/<path>.json`.
 *
 * One definition expands into one deed per matched target, e.g. "every mineable block: mine it once".
 * The first path segment is the deed set ("short", "extended", "insane", "maniac" or any custom name);
 * only the world's active set is tracked.
 */
data class DeedDefinition(
    val category: DeedCategory,
    val action: DeedAction,
    val targets: TargetSelector,
    val predicate: Optional<DeedPredicate>,
    val goal: DeedGoal,
    val reward: Optional<DeedReward>,
    val title: Optional<Component>,
    /**
     * Milestone: one deed advanced by the action on any of the targets ("break 10,000 blocks")
     * instead of one deed per target. Only count goals can be aggregated.
     */
    val aggregate: Boolean = false,
    /** Item shown for an aggregate deed, which has no object of its own. */
    val icon: Optional<Identifier> = Optional.empty()
) {
    /** Id of the single target an aggregate deed is expanded to. */
    fun milestoneTarget(definitionId: Identifier): Identifier =
        Identifier.fromNamespaceAndPath(definitionId.namespace, MILESTONE_PREFIX + definitionId.path)

    companion object {
        private val RAW_CODEC: Codec<DeedDefinition> = RecordCodecBuilder.create { instance ->
            instance.group(
                DeedCategory.CODEC.fieldOf("category").forGetter(DeedDefinition::category),
                DeedAction.CODEC.fieldOf("action").forGetter(DeedDefinition::action),
                TargetSelector.CODEC.fieldOf("targets").forGetter(DeedDefinition::targets),
                DeedPredicate.CODEC.optionalFieldOf("predicate").forGetter(DeedDefinition::predicate),
                DeedGoal.CODEC.optionalFieldOf("goal", DeedGoal.Count(1)).forGetter(DeedDefinition::goal),
                DeedReward.CODEC.optionalFieldOf("reward").forGetter(DeedDefinition::reward),
                ComponentSerialization.CODEC.optionalFieldOf("title").forGetter(DeedDefinition::title),
                Codec.BOOL.optionalFieldOf("aggregate", false).forGetter(DeedDefinition::aggregate),
                Identifier.CODEC.optionalFieldOf("icon").forGetter(DeedDefinition::icon)
            ).apply(instance, ::DeedDefinition)
        }

        val CODEC: Codec<DeedDefinition> = RAW_CODEC.validate { definition -> definition.validate() }

        /** Path prefix of milestone targets; they never collide with real registry ids. */
        const val MILESTONE_PREFIX: String = "milestone/"
    }

    private fun validate(): DataResult<DeedDefinition> {
        if (category !in action.categories) {
            return DataResult.error { "Action '${action.serializedName}' does not apply to category '${category.serializedName}'" }
        }
        val unique = goal as? DeedGoal.Unique
        if (unique != null && !unique.variant.supports(category)) {
            return DataResult.error { "Variant '${unique.variant.type}' does not apply to category '${category.serializedName}'" }
        }
        if (aggregate && goal !is DeedGoal.Count) {
            return DataResult.error { "Aggregate (milestone) deeds need a count goal" }
        }
        return DataResult.success(this)
    }
}
