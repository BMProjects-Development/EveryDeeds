package ru.benos.everydeeds.deed

import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * One concrete value of a variant space, with the same [key] that [DeedVariant.keys] produces for it.
 * Lets the client list what is still missing by comparing against the keys the server collected.
 */
sealed interface VariantOption {
    val key: Long

    data class State(val state: BlockState, override val key: Long) : VariantOption

    data class PropertyValue(val state: BlockState, val property: String, val value: String, override val key: Long) : VariantOption

    /** [level] is null for "any level of this enchantment". */
    data class Enchantment(val enchantment: Identifier, val level: Int?, override val key: Long) : VariantOption

    data class Damage(val damage: Int, val maxDamage: Int, override val key: Long) : VariantOption

    data class Potion(val potion: Identifier, override val key: Long) : VariantOption

    data class EffectLevel(val effect: Identifier, val level: Int, override val key: Long) : VariantOption

    /** One value of one mob variant dimension ([dimension] is a component id or a form like everydeeds:baby). */
    data class EntityVariant(val dimension: Identifier, val value: String, override val key: Long) : VariantOption
}

/** Enumerates the finite, listable variant spaces; huge or data-dependent ones (dye colours, combinations, mob variants) are not listed. */
object VariantOptions {
    fun enumerate(variantType: String, targetId: Identifier, registries: RegistryAccess, level: Level? = null): List<VariantOption>? =
        when (variantType) {
            DeedVariant.FullBlockState.type -> {
                val block = BuiltInRegistries.BLOCK.getOptional(targetId).orElse(null) ?: return null
                block.stateDefinition.possibleStates.map { state -> VariantOption.State(state, VariantKey.of("state/$state")) }
            }

            DeedVariant.EachBlockProperty.type -> {
                val block = BuiltInRegistries.BLOCK.getOptional(targetId).orElse(null) ?: return null
                block.stateDefinition.properties.flatMap { property -> propertyOptions(block.defaultBlockState(), property) }
            }

            DeedVariant.EachEnchantment.type ->
                applicableEnchantments(targetId, VariantContext(registries, null)).mapNotNull { holder ->
                    val id = holder.unwrapKey().map { key -> key.identifier() }.orElse(null) ?: return@mapNotNull null
                    VariantOption.Enchantment(id, null, VariantKey.of("enchantment/$id"))
                }

            DeedVariant.EachEnchantmentLevel.type ->
                applicableEnchantments(targetId, VariantContext(registries, null)).flatMap { holder ->
                    val id = holder.unwrapKey().map { key -> key.identifier() }.orElse(null) ?: return@flatMap emptyList()
                    val enchantment = holder.value()
                    (enchantment.minLevel..enchantment.maxLevel).map { level ->
                        VariantOption.Enchantment(id, level, VariantKey.of("enchantment/$id/$level"))
                    }
                }

            DeedVariant.Durability.type -> {
                val stack = ItemStack(BuiltInRegistries.ITEM.getOptional(targetId).orElse(null) ?: return null)
                if (!stack.isDamageableItem) return null
                (0..stack.maxDamage).map { damage -> VariantOption.Damage(damage, stack.maxDamage, VariantKey.of("damage", damage.toLong())) }
            }

            DeedVariant.EachPotion.type ->
                registries.lookupOrThrow(Registries.POTION).listElementIds().map { key ->
                    VariantOption.Potion(key.identifier(), VariantKey.of("potion/${key.identifier()}"))
                }.toList()

            DeedVariant.EachEffectLevel.type ->
                EffectLevels.obtainable(targetId).map { level -> VariantOption.EffectLevel(targetId, level, VariantKey.of("effect_level/$level")) }

            DeedVariant.EachEntityVariant.type ->
                EntityVariants.domains(targetId, registries, level)?.flatMap { (dimension, values) ->
                    values.map { value -> VariantOption.EntityVariant(dimension, value, VariantKey.of("entity_variant/$dimension=$value")) }
                }

            else -> null
        }

    /** Each value of [property], shown on the block's default state with that one value applied. */
    private fun <T : Comparable<T>> propertyOptions(base: BlockState, property: Property<T>): List<VariantOption> =
        property.possibleValues.map { value ->
            val propertyValue = property.value(value)
            VariantOption.PropertyValue(base.setValue(property, value), property.name, property.getName(value), VariantKey.of("property/$propertyValue"))
        }
}
