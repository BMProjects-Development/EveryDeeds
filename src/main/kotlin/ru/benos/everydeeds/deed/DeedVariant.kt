package ru.benos.everydeeds.deed

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.Level
import ru.benos.everydeeds.EveryDeeds

/** What [DeedVariant] implementations need from the world to enumerate variant spaces. */
class VariantContext(val registries: RegistryAccess, val level: Level?)

/**
 * A dimension along which the "same" object can differ: enchantments, block states, durability,
 * dye colour, mob variants. A variant goal counts distinct [keys] seen for a target instead of
 * enumerating every possible value as its own definition.
 */
sealed interface DeedVariant {
    val type: String

    fun supports(category: DeedCategory): Boolean

    /** Variant keys carried by [subject]. Several keys mean "each counts separately" (no combinations). */
    fun keys(subject: DeedSubject, context: VariantContext): LongArray

    /** Number of distinct keys possible for [targetId], or null when it cannot be enumerated. */
    fun spaceSize(targetId: Identifier, context: VariantContext): Long?

    /** Every enchantment counts on its own, regardless of level. */
    data object EachEnchantment : DeedVariant {
        override val type: String = "enchantment"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray =
            enchantmentsOf(subject).keySet().mapNotNull { holder -> holder.keyId() }
                .map { id -> VariantKey.of("enchantment/$id") }
                .toLongArray()

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            applicableEnchantments(targetId, context).size.toLong()
    }

    /** Every enchantment level counts on its own: Sharpness I and Sharpness II are two variants. */
    data object EachEnchantmentLevel : DeedVariant {
        override val type: String = "enchantment_level"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val enchantments = enchantmentsOf(subject)
            return enchantments.keySet().mapNotNull { holder ->
                holder.keyId()?.let { id -> VariantKey.of("enchantment/$id/${enchantments.getLevel(holder)}") }
            }.toLongArray()
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            applicableEnchantments(targetId, context).sumOf { holder -> levelCount(holder.value()) }
    }

    /**
     * The complete enchantment set (with levels) is one variant. The space is finite because
     * incompatible enchantments can never coexist on one item.
     */
    data object EnchantmentCombination : DeedVariant {
        override val type: String = "enchantment_combination"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val enchantments = enchantmentsOf(subject)
            if (enchantments.isEmpty) return LongArray(0)

            val signature = enchantments.keySet()
                .mapNotNull { holder -> holder.keyId()?.let { id -> "$id=${enchantments.getLevel(holder)}" } }
                .sorted()
                .joinToString(",")
            return longArrayOf(VariantKey.of("enchantments/$signature"))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            EnchantmentCombinatorics.countCombinations(applicableEnchantments(targetId, context))
    }

    /** The full block state is one variant. */
    data object FullBlockState : DeedVariant {
        override val type: String = "block_state"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.BLOCKS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val block = subject as? DeedSubject.Block ?: return LongArray(0)
            return longArrayOf(VariantKey.of("state/${block.state}"))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            BuiltInRegistries.BLOCK.getValue(targetId).stateDefinition.possibleStates.size.toLong()
    }

    /** Every `property=value` pair counts on its own (a block state without combinations). */
    data object EachBlockProperty : DeedVariant {
        override val type: String = "block_property"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.BLOCKS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val block = subject as? DeedSubject.Block ?: return LongArray(0)
            return block.state.values
                .map { value -> VariantKey.of("property/$value") }
                .toList()
                .toLongArray()
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            BuiltInRegistries.BLOCK.getValue(targetId).stateDefinition.properties
                .sumOf { property -> property.possibleValues.size.toLong() }
    }

    /** Remaining durability; every damage value is a variant. */
    data object Durability : DeedVariant {
        override val type: String = "durability"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val stack = (subject as? DeedSubject.Item)?.stack ?: return LongArray(0)
            if (!stack.isDamageableItem) return LongArray(0)
            return longArrayOf(VariantKey.of("damage", stack.damageValue.toLong()))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long {
            val stack = ItemStack(BuiltInRegistries.ITEM.getValue(targetId))
            return if (stack.isDamageableItem) stack.maxDamage + 1L else 0L
        }
    }

    /** Every obtainable level of a status effect counts on its own (see [EffectLevels]). */
    data object EachEffectLevel : DeedVariant {
        override val type: String = "effect_level"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.EFFECTS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val effect = subject as? DeedSubject.Effect ?: return LongArray(0)
            return longArrayOf(VariantKey.of("effect_level/${effect.level}"))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long =
            EffectLevels.obtainable(targetId).size.toLong()
    }

    /** Every potion type (water, awkward, swiftness, long swiftness...) counts on its own. */
    data object EachPotion : DeedVariant {
        override val type: String = "potion"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val potion = (subject as? DeedSubject.Item)?.stack?.get(DataComponents.POTION_CONTENTS)?.potion()?.orElse(null)
                ?: return LongArray(0)
            val id = potion.keyId() ?: return LongArray(0)
            return longArrayOf(VariantKey.of("potion/$id"))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long? {
            val stack = ItemStack(BuiltInRegistries.ITEM.getValue(targetId))
            if (!stack.has(DataComponents.POTION_CONTENTS)) return 0L
            return context.registries.lookupOrThrow(Registries.POTION).listElements().count()
        }
    }

    /** Dye colour (RGB) of dyeable items: 16,777,216 values, tracked as a growing set. */
    data object DyedColor : DeedVariant {
        override val type: String = "dyed_color"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ITEMS

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val color = (subject as? DeedSubject.Item)?.stack?.get(DataComponents.DYED_COLOR) ?: return LongArray(0)
            return longArrayOf(VariantKey.of("dyed_color", (color.rgb() and 0xFFFFFF).toLong()))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long = 1L shl 24
    }

    /** Every mob variant component (cat type, sheep colour, villager profession...) counts on its own. */
    data object EachEntityVariant : DeedVariant {
        override val type: String = "entity_variant"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ENTITIES

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val entity = (subject as? DeedSubject.Entity)?.entity ?: return LongArray(0)
            return EntityVariants.values(entity)
                .map { (componentId, value) -> VariantKey.of("entity_variant/$componentId=$value") }
                .toLongArray()
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long? =
            EntityVariants.domainSizes(targetId, context)?.values?.sum()
    }

    /** All mob variant components together form one variant. */
    data object EntityVariantCombination : DeedVariant {
        override val type: String = "entity_variant_combination"

        override fun supports(category: DeedCategory): Boolean = category == DeedCategory.ENTITIES

        override fun keys(subject: DeedSubject, context: VariantContext): LongArray {
            val entity = (subject as? DeedSubject.Entity)?.entity ?: return LongArray(0)
            val values = EntityVariants.values(entity)
            if (values.isEmpty()) return LongArray(0)

            val signature = values.map { (componentId, value) -> "$componentId=$value" }.sorted().joinToString(",")
            return longArrayOf(VariantKey.of("entity_variants/$signature"))
        }

        override fun spaceSize(targetId: Identifier, context: VariantContext): Long? {
            val sizes = EntityVariants.domainSizes(targetId, context) ?: return null
            if (sizes.isEmpty()) return 0L
            return sizes.values.fold(1L) { product, size -> multiplyCapped(product, size) }
        }
    }

    companion object {
        val ALL: List<DeedVariant> = listOf(
            EachEnchantment, EachEnchantmentLevel, EnchantmentCombination,
            FullBlockState, EachBlockProperty,
            Durability, DyedColor, EachPotion, EachEffectLevel,
            EachEntityVariant, EntityVariantCombination
        )

        private val BY_TYPE: Map<String, DeedVariant> = ALL.associateBy { variant -> variant.type }

        private val TYPE_CODEC: Codec<DeedVariant> = Codec.STRING.comapFlatMap(
            { type ->
                BY_TYPE[type]?.let { DataResult.success(it) }
                    ?: DataResult.error { "Unknown variant type '$type', expected one of ${BY_TYPE.keys}" }
            },
            { variant -> variant.type }
        )

        /** Accepts both the short form `"enchantment"` and `{"type": "enchantment"}`. */
        val CODEC: Codec<DeedVariant> = Codec.withAlternative(
            TYPE_CODEC,
            TYPE_CODEC.fieldOf("type").codec()
        )
    }
}

private fun Holder<*>.keyId(): String? =
    unwrapKey().map { key -> key.identifier().toString() }.orElse(null)

private fun enchantmentsOf(subject: DeedSubject): ItemEnchantments {
    val stack = (subject as? DeedSubject.Item)?.stack ?: return ItemEnchantments.EMPTY
    return stack.getOrDefault(EnchantmentHelper.getComponentType(stack), ItemEnchantments.EMPTY)
}

private fun levelCount(enchantment: Enchantment): Long =
    (enchantment.maxLevel - enchantment.minLevel + 1).coerceAtLeast(1).toLong()

/** Enchantments that can end up on [targetId]; enchanted books accept every enchantment. */
internal fun applicableEnchantments(targetId: Identifier, context: VariantContext): List<Holder<Enchantment>> {
    val stack = ItemStack(BuiltInRegistries.ITEM.getValue(targetId))
    val all = context.registries.lookupOrThrow(Registries.ENCHANTMENT).listElements().toList()
    if (EnchantmentHelper.getComponentType(stack) == DataComponents.STORED_ENCHANTMENTS) return all
    return all.filter { holder -> holder.value().isSupportedItem(stack) }
}

internal fun multiplyCapped(left: Long, right: Long): Long =
    if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

/**
 * Counts non-empty enchantment sets (with levels) that can coexist on one item.
 * Incompatibility forms a conflict graph; independent sets are counted per connected component and
 * multiplied, which keeps enchanted books (dozens of enchantments) cheap to evaluate.
 */
internal object EnchantmentCombinatorics {
    fun countCombinations(enchantments: List<Holder<Enchantment>>): Long {
        if (enchantments.isEmpty()) return 0L

        val size = enchantments.size
        val conflicts = Array(size) { BooleanArray(size) }
        for (i in 0 until size) {
            for (j in i + 1 until size) {
                if (!Enchantment.areCompatible(enchantments[i], enchantments[j])) {
                    conflicts[i][j] = true
                    conflicts[j][i] = true
                }
            }
        }

        val visited = BooleanArray(size)
        var total = 1L
        for (start in 0 until size) {
            if (visited[start]) continue
            val component = mutableListOf<Int>()
            val queue = ArrayDeque(listOf(start))
            visited[start] = true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component += node
                for (next in 0 until size) {
                    if (conflicts[node][next] && !visited[next]) {
                        visited[next] = true
                        queue += next
                    }
                }
            }
            val weights = component.map { index -> levelCount(enchantments[index].value()) }
            total = multiplyCapped(total, weightedIndependentSets(component, weights, conflicts))
        }

        // Every component may also stay empty; the all-empty selection is not an enchanted item.
        return total - 1
    }

    /** Sum over independent sets of the product of their weights, including the empty set. */
    private fun weightedIndependentSets(nodes: List<Int>, weights: List<Long>, conflicts: Array<BooleanArray>): Long {
        fun count(index: Int, chosen: List<Int>): Long {
            if (index == nodes.size) return 1L
            val skip = count(index + 1, chosen)
            val node = nodes[index]
            if (chosen.any { other -> conflicts[node][other] }) return skip
            return skip + multiplyCapped(weights[index], count(index + 1, chosen + node))
        }
        return count(0, emptyList())
    }
}

/**
 * Mob variants: the data components that carry a variant (cat/variant, sheep/color...) plus two
 * "forms" that are entity state rather than components: baby vs adult, and charged vs normal creeper.
 * Values are rendered to stable strings: registry holders by their key, enums and flags by name.
 * Other component values (custom names and the like) are not variants and are ignored.
 *
 * Which components carry a variant is looked up once per entity type; recording a kill or a sighting
 * then only reads those few components instead of probing every component type of the game.
 */
internal object EntityVariants {
    /** Form dimensions, keyed like components so they share the key format. */
    val BABY: Identifier = Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, "baby")
    val CHARGED: Identifier = Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, "charged")
    private val FLAG_VALUES = listOf("false", "true")

    private class VariantComponent(val id: Identifier, val type: DataComponentType<*>)

    // All caches are shared by the server and, in singleplayer, the client thread: guarded by one lock.
    private val lock = Any()
    private val domainCache: MutableMap<Identifier, Map<Identifier, List<String>>?> = HashMap()
    private val componentsByType: MutableMap<EntityType<*>, List<VariantComponent>> = HashMap()
    private val canBeBaby: MutableMap<EntityType<*>, Boolean> = HashMap()

    /** Variant values of [entity]: its variant components, then its forms. */
    fun values(entity: Entity): List<Pair<Identifier, String>> {
        val components = variantComponents(entity).mapNotNull { component ->
            when (val value = runCatching { entity.get(component.type) }.getOrNull()) {
                is Holder<*> -> value.keyId()?.let { id -> component.id to id }
                is Enum<*> -> component.id to value.name.lowercase()
                else -> null
            }
        }
        return components + forms(entity).map { (form, value) -> form to value }
    }

    /** Per-dimension domain sizes for [targetId], or null when some domain cannot be enumerated. */
    fun domainSizes(targetId: Identifier, context: VariantContext): Map<Identifier, Long>? =
        domains(targetId, context.registries, context.level)?.mapValues { (_, values) -> values.size.toLong() }

    /** Every value of every variant dimension of [targetId] (needs a level to instantiate the mob). */
    fun domains(targetId: Identifier, registries: RegistryAccess, level: Level?): Map<Identifier, List<String>>? =
        synchronized(lock) {
            domainCache[targetId]?.let { cached -> return cached }
            val computed = computeDomains(targetId, registries, level ?: return null)
            domainCache[targetId] = computed
            computed
        }

    fun clearCache() {
        synchronized(lock) {
            domainCache.clear()
            componentsByType.clear()
            canBeBaby.clear()
        }
    }

    private fun computeDomains(targetId: Identifier, registries: RegistryAccess, level: Level): Map<Identifier, List<String>>? {
        val entity = create(BuiltInRegistries.ENTITY_TYPE.getValue(targetId), level) ?: return null

        val domains = LinkedHashMap<Identifier, List<String>>()
        for (component in variantComponents(entity)) {
            val values: List<String> = when (val value = entity.get(component.type)) {
                is Holder<*> -> value.unwrapKey().map { holderKey ->
                    registries.lookup(holderKey.registryKey())
                        .map { lookup -> lookup.listElementIds().map { id -> id.identifier().toString() }.toList() }
                        .orElse(emptyList())
                }.orElse(emptyList())
                is Enum<*> -> value.declaringJavaClass.enumConstants.map { constant -> (constant as Enum<*>).name.lowercase() }
                else -> continue
            }
            if (values.isEmpty()) return null
            domains[component.id] = values
        }
        forms(entity).forEach { (form, _) -> domains[form] = FLAG_VALUES }
        entity.discard()
        return domains
    }

    /** The forms [entity] can take, with its current value of each. */
    private fun forms(entity: Entity): List<Pair<Identifier, String>> = buildList {
        if (entity is Mob && canBeBaby(entity)) add(BABY to entity.isBaby.toString())
        if (entity is Creeper) add(CHARGED to entity.isPowered.toString())
    }

    /** Components of this entity type whose value is a variant (a registry holder or an enum). */
    private fun variantComponents(entity: Entity): List<VariantComponent> =
        synchronized(lock) {
            componentsByType.getOrPut(entity.type) {
                BuiltInRegistries.DATA_COMPONENT_TYPE.entrySet().mapNotNull { (key, type) ->
                    when (runCatching { entity.get(type) }.getOrNull()) {
                        is Holder<*>, is Enum<*> -> VariantComponent(key.identifier(), type)
                        else -> null
                    }
                }
            }
        }

    /** Whether this kind of mob has a baby form: a throwaway instance is asked to become one. */
    private fun canBeBaby(mob: Mob): Boolean =
        synchronized(lock) {
            canBeBaby.getOrPut(mob.type) {
                val probe = create(mob.type, mob.level()) as? Mob ?: return@getOrPut false
                val result = runCatching {
                    probe.setBaby(true)
                    probe.isBaby
                }.getOrDefault(false)
                probe.discard()
                result
            }
        }

    private fun create(type: EntityType<*>, level: Level): Entity? =
        runCatching { type.create(level, EntitySpawnRequest(EntitySpawnReason.LOAD, true)) }.getOrNull()
}
