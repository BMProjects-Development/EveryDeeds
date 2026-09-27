package ru.benos.everydeeds.deed

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ExtraCodecs
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments

/** What a predicate is evaluated against: the object acted upon and the acting player. */
class PredicateContext(val subject: DeedSubject, val player: ServerPlayer)

/**
 * Extra condition an action must satisfy to count toward a deed. A recursive JSON tree:
 *
 * ```json
 * { "type": "all_of", "terms": [
 *     { "type": "has_enchantment", "enchantment": "minecraft:sharpness", "min_level": 5 },
 *     { "type": "not", "term": { "type": "dimension", "dimension": "minecraft:overworld" } }
 * ] }
 * ```
 */
sealed interface DeedPredicate {
    val type: Type

    fun test(context: PredicateContext): Boolean

    data class AllOf(val terms: List<DeedPredicate>) : DeedPredicate {
        override val type: Type get() = Type.ALL_OF
        override fun test(context: PredicateContext): Boolean = terms.all { term -> term.test(context) }
    }

    data class AnyOf(val terms: List<DeedPredicate>) : DeedPredicate {
        override val type: Type get() = Type.ANY_OF
        override fun test(context: PredicateContext): Boolean = terms.any { term -> term.test(context) }
    }

    data class Not(val term: DeedPredicate) : DeedPredicate {
        override val type: Type get() = Type.NOT
        override fun test(context: PredicateContext): Boolean = !term.test(context)
    }

    /** Item carries [enchantment] at [minLevel] or higher (stored enchantments count for books). */
    data class HasEnchantment(val enchantment: Identifier, val minLevel: Int) : DeedPredicate {
        override val type: Type get() = Type.HAS_ENCHANTMENT

        override fun test(context: PredicateContext): Boolean {
            val stack = (context.subject as? DeedSubject.Item)?.stack ?: return false
            val enchantments = stack.getOrDefault(EnchantmentHelper.getComponentType(stack), ItemEnchantments.EMPTY)
            return enchantments.keySet().any { holder ->
                holder.unwrapKey().map { key -> key.identifier() == enchantment }.orElse(false) &&
                    enchantments.getLevel(holder) >= minLevel
            }
        }
    }

    /** Item carries at least one enchantment. */
    data object Enchanted : DeedPredicate {
        override val type: Type get() = Type.ENCHANTED

        override fun test(context: PredicateContext): Boolean {
            val stack = (context.subject as? DeedSubject.Item)?.stack ?: return false
            return !stack.getOrDefault(EnchantmentHelper.getComponentType(stack), ItemEnchantments.EMPTY).isEmpty
        }
    }

    /** Remaining durability, as a fraction of maximum, lies within [min, max]. */
    data class Durability(val min: Float, val max: Float) : DeedPredicate {
        override val type: Type get() = Type.DURABILITY

        override fun test(context: PredicateContext): Boolean {
            val stack = (context.subject as? DeedSubject.Item)?.stack ?: return false
            if (!stack.isDamageableItem) return false
            val remaining = 1f - stack.damageValue.toFloat() / stack.maxDamage.toFloat()
            return remaining in min..max
        }
    }

    /** Block state has `property=value`. */
    data class BlockProperty(val property: String, val value: String) : DeedPredicate {
        override val type: Type get() = Type.BLOCK_PROPERTY

        override fun test(context: PredicateContext): Boolean {
            val state = (context.subject as? DeedSubject.Block)?.state ?: return false
            // Property.Value renders as "name=value" using the property's serialized value names.
            return state.values.anyMatch { propertyValue -> propertyValue.toString() == "$property=$value" }
        }
    }

    /** The acting player is in [dimension]. */
    data class Dimension(val dimension: Identifier) : DeedPredicate {
        override val type: Type get() = Type.DIMENSION
        override fun test(context: PredicateContext): Boolean = context.player.level().dimension().identifier() == dimension
    }

    /** Entity is a baby. */
    data object Baby : DeedPredicate {
        override val type: Type get() = Type.BABY
        override fun test(context: PredicateContext): Boolean =
            ((context.subject as? DeedSubject.Entity)?.entity as? LivingEntity)?.isBaby == true
    }

    /** Entity or item has a custom name. */
    data object Named : DeedPredicate {
        override val type: Type get() = Type.NAMED
        override fun test(context: PredicateContext): Boolean =
            when (val subject = context.subject) {
                is DeedSubject.Entity -> subject.entity.hasCustomName()
                is DeedSubject.Item -> subject.stack.has(DataComponents.CUSTOM_NAME)
                else -> false
            }
    }

    enum class Type(val id: String) {
        ALL_OF("all_of"),
        ANY_OF("any_of"),
        NOT("not"),
        HAS_ENCHANTMENT("has_enchantment"),
        ENCHANTED("enchanted"),
        DURABILITY("durability"),
        BLOCK_PROPERTY("block_property"),
        DIMENSION("dimension"),
        BABY("baby"),
        NAMED("named");

        companion object {
            val CODEC: Codec<Type> = Codec.STRING.comapFlatMap(
                { id ->
                    entries.firstOrNull { type -> type.id == id }?.let { DataResult.success(it) }
                        ?: DataResult.error { "Unknown predicate type '$id', expected one of ${entries.map { it.id }}" }
                },
                { type -> type.id }
            )
        }
    }

    companion object {
        /** Recursive dispatch codec: composite nodes reference the codec being defined. */
        val CODEC: Codec<DeedPredicate> = Codec.recursive("DeedPredicate") { self ->
            Type.CODEC.dispatch("type", DeedPredicate::type) { type -> mapCodecFor(type, self) }
        }

        private fun mapCodecFor(type: Type, self: Codec<DeedPredicate>): MapCodec<out DeedPredicate> =
            when (type) {
                Type.ALL_OF -> self.listOf().fieldOf("terms").xmap(::AllOf, AllOf::terms)
                Type.ANY_OF -> self.listOf().fieldOf("terms").xmap(::AnyOf, AnyOf::terms)
                Type.NOT -> self.fieldOf("term").xmap(::Not, Not::term)
                Type.HAS_ENCHANTMENT -> RecordCodecBuilder.mapCodec<HasEnchantment> { instance ->
                    instance.group(
                        Identifier.CODEC.fieldOf("enchantment").forGetter(HasEnchantment::enchantment),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("min_level", 1).forGetter(HasEnchantment::minLevel)
                    ).apply(instance, ::HasEnchantment)
                }
                Type.ENCHANTED -> MapCodec.unit(Enchanted)
                Type.DURABILITY -> RecordCodecBuilder.mapCodec<Durability> { instance ->
                    instance.group(
                        Codec.floatRange(0f, 1f).optionalFieldOf("min", 0f).forGetter(Durability::min),
                        Codec.floatRange(0f, 1f).optionalFieldOf("max", 1f).forGetter(Durability::max)
                    ).apply(instance, ::Durability)
                }
                Type.BLOCK_PROPERTY -> RecordCodecBuilder.mapCodec<BlockProperty> { instance ->
                    instance.group(
                        Codec.STRING.fieldOf("property").forGetter(BlockProperty::property),
                        Codec.STRING.fieldOf("value").forGetter(BlockProperty::value)
                    ).apply(instance, ::BlockProperty)
                }
                Type.DIMENSION -> Identifier.CODEC.fieldOf("dimension").xmap(::Dimension, Dimension::dimension)
                Type.BABY -> MapCodec.unit(Baby)
                Type.NAMED -> MapCodec.unit(Named)
            }
    }
}
