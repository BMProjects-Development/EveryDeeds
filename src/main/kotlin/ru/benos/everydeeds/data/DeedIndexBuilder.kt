package ru.benos.everydeeds.data

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.DefaultAttributes
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.item.trading.Merchant
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.structure.Structure
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.DeedDefinition
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.DeedTags
import ru.benos.everydeeds.deed.EffectLevels
import ru.benos.everydeeds.deed.EntityVariants
import ru.benos.everydeeds.deed.TargetSelector
import ru.benos.everydeeds.deed.TargetTrait
import ru.benos.everydeeds.deed.VariantContext
import ru.benos.everydeeds.deed.applicableEnchantments

/**
 * Expands the definitions of one set into concrete [DeedInstance]s against the server's registries,
 * tags and recipes. Targets for which a goal is impossible (a variant space of zero, an unknown
 * space for `"required": "all"`) are skipped instead of producing deeds nobody can finish.
 */
object DeedIndexBuilder {
    fun build(server: MinecraftServer, set: String): DeedIndex {
        val startedAt = System.nanoTime()
        val definitions = DeedDefinitions.inSet(set).toSortedMap(compareBy { id -> id.toString() })
        val context = VariantContext(server.registryAccess(), server.overworld())
        val traits = TraitEvaluator(server, context)

        val templates = ArrayList<DeedTemplate>(definitions.size)
        val instances = ArrayList<DeedInstance>()
        var skipped = 0

        EntityVariants.clearCache()
        for ((definitionId, definition) in definitions) {
            val template = DeedTemplate(templates.size, definitionId, definition)
            templates += template

            val resolved = resolveTargets(definition, traits, server.overworld().enabledFeatures())
            if (definition.aggregate) {
                if (resolved.isNotEmpty()) {
                    val milestone = definition.milestoneTarget(definitionId)
                    val required = (definition.goal as DeedGoal.Count).count.toLong()
                    instances += DeedInstance(instances.size, template.index, definitionId, definition, milestone, required, resolved.toHashSet())
                }
                continue
            }
            for (targetId in resolved) {
                val required = requiredAmount(definition, targetId, context)
                if (required == null) {
                    skipped++
                    continue
                }
                instances += DeedInstance(instances.size, template.index, definitionId, definition, targetId, required)
            }
        }

        EveryDeeds.LOGGER.info(
            "Indexed deed set '{}': {} definitions, {} deeds ({} impossible targets skipped) in {} ms",
            set, templates.size, instances.size, skipped, (System.nanoTime() - startedAt) / 1_000_000
        )
        return DeedIndex(set, templates, instances)
    }

    /** Content behind a disabled experimental feature cannot exist in this world, so it gets no deeds. */
    private fun resolveTargets(definition: DeedDefinition, traits: TraitEvaluator, features: FeatureFlagSet): List<Identifier> =
        when (definition.category) {
            DeedCategory.BLOCKS -> resolve(BuiltInRegistries.BLOCK, definition.targets) { block, _ ->
                !block.defaultBlockState().isAir
            }.filter { id -> BuiltInRegistries.BLOCK.getValue(id).isEnabled(features) && traits.matches(definition, id) }

            DeedCategory.ITEMS -> resolve(BuiltInRegistries.ITEM, definition.targets) { item, _ ->
                item != Items.AIR
            }.filter { id -> BuiltInRegistries.ITEM.getValue(id).isEnabled(features) && traits.matches(definition, id) }

            DeedCategory.ENTITIES -> resolve(BuiltInRegistries.ENTITY_TYPE, definition.targets) { _, _ -> true }
                .filter { id -> BuiltInRegistries.ENTITY_TYPE.getValue(id).isEnabled(features) && traits.matches(definition, id) }

            // Only biomes some dimension's generator can produce: registered but unused biomes (the_void) are unreachable.
            DeedCategory.BIOMES -> {
                val reachable = traits.reachableBiomes
                resolve(traits.biomeRegistry, definition.targets) { _, id -> id in reachable }
                    .filter { id -> id in reachable }
            }

            DeedCategory.DIMENSIONS -> resolve(traits.dimensionRegistry, definition.targets) { _, _ -> true }
                .filter { id -> id in traits.existingDimensions }

            // Only structures that some structure set places in a biome the world can generate.
            DeedCategory.EFFECTS -> resolve(BuiltInRegistries.MOB_EFFECT, definition.targets) { _, _ -> true }

            DeedCategory.STRUCTURES -> {
                val placeable = traits.placeableStructures
                resolve(traits.structureRegistry, definition.targets) { _, id -> id in placeable }
                    .filter { id -> id in placeable }
            }
        }

    private fun <T : Any> resolve(
        registry: Registry<T>,
        selector: TargetSelector,
        allowedInAll: (T, Identifier) -> Boolean
    ): List<Identifier> =
        when (selector) {
            is TargetSelector.Single ->
                if (registry.containsKey(selector.id)) listOf(selector.id) else emptyList()

            is TargetSelector.Tag ->
                registry.getTagOrEmpty(TagKey.create(registry.key(), selector.tag))
                    .mapNotNull { holder -> holder.unwrapKey().map { key -> key.identifier() }.orElse(null) }

            is TargetSelector.All -> {
                val excluded = selector.exclude.map { tag ->
                    registry.getTagOrEmpty(TagKey.create(registry.key(), tag))
                        .mapNotNull { holder -> holder.unwrapKey().map { key -> key.identifier() }.orElse(null) }
                        .toSet()
                }.orElse(emptySet())

                registry.keySet()
                    .filter { id -> id !in excluded && allowedInAll(registry.getValue(id)!!, id) }
                    .sortedBy { id -> registry.getId(registry.getValue(id)) }
            }
        }

    /** Resolved completion threshold, or null when the deed would be impossible for [targetId]. */
    private fun requiredAmount(definition: DeedDefinition, targetId: Identifier, context: VariantContext): Long? =
        when (val goal = definition.goal) {
            is DeedGoal.Count -> goal.count.toLong()
            is DeedGoal.Unique -> {
                val space = goal.variant.spaceSize(targetId, context)
                when (val required = goal.required) {
                    DeedGoal.Required.All -> space?.takeIf { it > 0 }
                    is DeedGoal.Required.Fixed -> when {
                        space == null -> required.amount
                        space <= 0 -> null
                        else -> minOf(required.amount, space)
                    }
                }
            }
        }

    private val BLOCK_USE_METHODS = setOf("useWithoutItem", "useItemOn")
    private val ITEM_USE_METHODS = setOf("use", "useOn", "releaseUsing", "finishUsingItem", "interactLivingEntity", "mineBlock", "hurtEnemy")
    private val USE_COMPONENTS: List<DataComponentType<*>> = listOf(
        DataComponents.CONSUMABLE, DataComponents.EQUIPPABLE, DataComponents.WEAPON,
        DataComponents.TOOL, DataComponents.BLOCKS_ATTACKS
    )

    /** Evaluates [TargetTrait]s; `all` selectors list their traits, other selectors are taken as-is. */
    private class TraitEvaluator(private val server: MinecraftServer, private val context: VariantContext) {
        private val recipeResults: Set<Item> by lazy { collectRecipeResults() }

        val biomeRegistry: Registry<Biome> by lazy { server.registryAccess().lookupOrThrow(Registries.BIOME) }
        val dimensionRegistry: Registry<LevelStem> by lazy { server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM) }

        val reachableBiomes: Set<Identifier> by lazy {
            server.allLevels.flatMap { level -> level.chunkSource.generator.biomeSource.possibleBiomes() }
                .mapNotNull { holder -> holder.unwrapKey().map { key -> key.identifier() }.orElse(null) }
                .toSet()
        }

        val existingDimensions: Set<Identifier> by lazy {
            server.levelKeys().map { key -> key.identifier() }.toSet()
        }

        val structureRegistry: Registry<Structure> by lazy { server.registryAccess().lookupOrThrow(Registries.STRUCTURE) }

        val placeableStructures: Set<Identifier> by lazy {
            val biomes = reachableBiomes
            server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
                .flatMap { set -> set.structures().map { entry -> entry.structure() } }
                .filter { holder -> holder.value().biomes().any { biome -> biome.unwrapKey().map { key -> key.identifier() in biomes }.orElse(false) } }
                .mapNotNull { holder -> holder.unwrapKey().map { key -> key.identifier() }.orElse(null) }
                .toSet()
        }

        fun matches(definition: DeedDefinition, targetId: Identifier): Boolean {
            val selector = definition.targets as? TargetSelector.All ?: return true
            return selector.traits.all { trait -> test(trait, definition.category, targetId) }
        }

        private fun test(trait: TargetTrait, category: DeedCategory, targetId: Identifier): Boolean =
            when (category) {
                DeedCategory.BLOCKS -> testBlock(trait, BuiltInRegistries.BLOCK.getValue(targetId))
                DeedCategory.ITEMS -> testItem(trait, BuiltInRegistries.ITEM.getValue(targetId), targetId)
                DeedCategory.ENTITIES -> testEntity(trait, BuiltInRegistries.ENTITY_TYPE.getValue(targetId), targetId)
                DeedCategory.EFFECTS -> trait == TargetTrait.LEVELED && EffectLevels.obtainable(targetId).size > 1
                DeedCategory.BIOMES, DeedCategory.DIMENSIONS, DeedCategory.STRUCTURES -> false
            }

        private fun testBlock(trait: TargetTrait, block: Block): Boolean =
            when (trait) {
                TargetTrait.TARGETABLE -> isTargetable(block)
                // Punching fire extinguishes it instead of breaking it, so it is never "mined".
                TargetTrait.MINEABLE -> isTargetable(block) && block.defaultDestroyTime() >= 0f &&
                    block !is LiquidBlock && block !is BaseFireBlock
                TargetTrait.PLACEABLE -> isPlacedByItem(block) && isObtainable(block.asItem())
                TargetTrait.HAS_STATES -> block.stateDefinition.possibleStates.size > 1
                TargetTrait.LIQUID -> block is LiquidBlock
                // Crafting credits the block its item places by default, so wall variants are not craftable targets.
                TargetTrait.CRAFTABLE -> (block.asItem() as? BlockItem)?.block == block && block.asItem() in recipeResults
                TargetTrait.USABLE -> overridesAny(block.javaClass, BlockBehaviour::class.java, BLOCK_USE_METHODS)
                else -> false
            }

        /**
         * The sight scanner and mining both need the crosshair on the block: blocks without an outline
         * in every state (air, fire, light, bubble columns...) can never be seen or mined. Fluids are
         * hit through their fluid shape instead.
         */
        private fun isTargetable(block: Block): Boolean {
            if (block is LiquidBlock) return true
            return block.stateDefinition.possibleStates.any { state ->
                runCatching { !state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty }.getOrDefault(true)
            }
        }

        /**
         * The block must be what its item actually places: the item's own block, or the wall variant of
         * a standing/wall item (torches, signs, heads, banners, coral fans). Blocks that only share an
         * item (water cauldron -> cauldron, dripleaf stem -> big dripleaf) can never be placed.
         */
        private fun isPlacedByItem(block: Block): Boolean {
            val item = block.asItem() as? BlockItem ?: return false
            return item.block == block || item is StandingAndWallBlockItem
        }

        private fun isObtainable(item: Item): Boolean =
            !BuiltInRegistries.ITEM.wrapAsHolder(item).`is`(DeedTags.UNOBTAINABLE_ITEMS)

        private fun testItem(trait: TargetTrait, item: Item, targetId: Identifier): Boolean =
            when (trait) {
                TargetTrait.CRAFTABLE -> item in recipeResults
                TargetTrait.ENCHANTABLE -> applicableEnchantments(targetId, context).isNotEmpty()
                TargetTrait.DAMAGEABLE -> ItemStack(item).isDamageableItem
                TargetTrait.USABLE -> isUsable(item)
                TargetTrait.EDIBLE -> ItemStack(item).has(DataComponents.FOOD)
                TargetTrait.TABLE_ENCHANTABLE ->
                    item == Items.ENCHANTED_BOOK || (item != Items.BOOK && ItemStack(item).has(DataComponents.ENCHANTABLE))
                else -> false
            }

        /**
         * Mirrors where the game awards the "used" statistic: an item-specific use (bows, buckets, hoes...)
         * or a component-driven one (food, armor, weapons, tools, shields). Placing is not a use of a block item.
         */
        private fun isUsable(item: Item): Boolean {
            if (item is BlockItem) return false
            if (overridesAny(item.javaClass, Item::class.java, ITEM_USE_METHODS)) return true
            val stack = ItemStack(item)
            return USE_COMPONENTS.any { component -> stack.has(component) }
        }

        /** Whether [type] (below [base]) declares one of [methods]: the block or item brings its own behaviour. */
        private fun overridesAny(type: Class<*>, base: Class<*>, methods: Set<String>): Boolean {
            var current: Class<*>? = type
            while (current != null && current != base) {
                if (current.declaredMethods.any { method -> method.name in methods }) return true
                current = current.superclass
            }
            return false
        }

        private fun testEntity(trait: TargetTrait, type: EntityType<*>, targetId: Identifier): Boolean =
            when (trait) {
                TargetTrait.LIVING -> DefaultAttributes.hasSupplier(type)
                TargetTrait.HAS_VARIANTS -> !EntityVariants.domainSizes(targetId, context).isNullOrEmpty()
                TargetTrait.MERCHANT -> isMerchant(type)
                else -> false
            }

        private fun isMerchant(type: EntityType<*>): Boolean {
            if (!DefaultAttributes.hasSupplier(type)) return false
            val entity = runCatching { type.create(server.overworld(), EntitySpawnRequest(EntitySpawnReason.LOAD, true)) }.getOrNull()
                ?: return false
            val merchant = entity is Merchant
            entity.discard()
            return merchant
        }

        private fun collectRecipeResults(): Set<Item> {
            val displayContext = SlotDisplayContext.fromLevel(server.overworld())
            val results = HashSet<Item>()
            for (holder in server.recipeManager.recipes) {
                runCatching {
                    for (display in holder.value().display()) {
                        display.result().resolveForStacks(displayContext).forEach { stack -> results += stack.item }
                    }
                }
            }
            return results
        }
    }
}
