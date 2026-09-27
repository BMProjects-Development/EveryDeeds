package ru.benos.everydeeds.client.deed

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Hud
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.gui.AchievementUiEntry
import ru.benos.everydeeds.client.gui.DeedPreview
import ru.benos.everydeeds.client.gui.displayName
import ru.benos.everydeeds.client.gui.formatAmount
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.network.DeedInstanceView
import ru.benos.everydeeds.network.DeedTemplateView
import java.util.Optional

/**
 * Turns a synced target id into what the UI shows: name, id and preview. Shared by the progress
 * screen and the unlock toast. Returns null for ids this client does not know (a server-only mod).
 */
object TargetEntries {
    private const val NEUTRAL_GRASS = 0x7FB238

    fun entryFor(category: DeedCategory, id: Identifier): AchievementUiEntry? =
        when (category) {
            DeedCategory.BLOCKS -> BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.let { block ->
                val icon = when {
                    block.asItem() != Items.AIR -> ItemStack(block.asItem())
                    block is LiquidBlock -> ItemStack(block.defaultBlockState().fluidState.type.bucket)
                    else -> ItemStack(Items.BARRIER)
                }
                AchievementUiEntry(category, id, block.name, DeedPreview.Block(block.defaultBlockState(), icon))
            }

            DeedCategory.ITEMS -> BuiltInRegistries.ITEM.getOptional(id).orElse(null)?.let { item ->
                val stack = ItemStack(item)
                AchievementUiEntry(category, id, stack.hoverName, DeedPreview.Item(stack))
            }

            DeedCategory.ENTITIES -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)?.let { type ->
                val egg = SpawnEggItem.byId(type).map { holder -> ItemStack(holder.value()) }.orElse(ItemStack(Items.NAME_TAG))
                AchievementUiEntry(category, id, type.description, DeedPreview.Entity(type, egg))
            }

            DeedCategory.BIOMES -> AchievementUiEntry(category, id, biomeName(id), biomePreview(id))

            DeedCategory.EFFECTS -> BuiltInRegistries.MOB_EFFECT.get(id).orElse(null)?.let { effect ->
                val potion = ItemStack(Items.POTION)
                potion.set(DataComponents.POTION_CONTENTS, PotionContents(Optional.empty(), Optional.of(effect.value().color), emptyList(), Optional.empty()))
                AchievementUiEntry(category, id, effect.value().displayName, DeedPreview.Effect(Hud.getMobEffectSprite(effect), potion))
            }

            DeedCategory.STRUCTURES -> AchievementUiEntry(category, id, structureName(id), structurePreview(id))

            DeedCategory.DIMENSIONS -> {
                val block = DIMENSION_BLOCKS[id.toString()] ?: Blocks.STONE
                val name = nameOr("gui.$MOD_ID.dimension.${id.namespace}.${id.path}", id)
                AchievementUiEntry(category, id, name, DeedPreview.Block(block.defaultBlockState(), ItemStack(block.asItem())))
            }
        }

    /**
     * A milestone (aggregate deed): its icon item and, as the short cell name, just the amount
     * ("10 000"); the full title ("Broken: 10 000") is its requirement row and toast text.
     */
    fun milestoneEntry(template: DeedTemplateView, instance: DeedInstanceView): AchievementUiEntry {
        val icon = template.icon.flatMap { id -> BuiltInRegistries.ITEM.getOptional(id) }.map(::ItemStack).orElse(ItemStack(Items.NETHER_STAR))
        val name = template.title.orElseGet { template.action.formatAmount(instance.required) }
        return AchievementUiEntry(template.category, instance.targetId, name, DeedPreview.Item(icon))
    }

    fun milestoneTitle(template: DeedTemplateView, required: Long): Component =
        template.title.orElseGet {
            "gui.$MOD_ID.goal.milestone".translatable(template.action.displayName(template.category), template.action.formatAmount(required))
        }

    /** Biome-map colour for known biomes; otherwise the grass colour from the client's copy of the biome registry. */
    private fun biomePreview(id: Identifier): DeedPreview.Biome {
        val icon = BIOME_ICON_KEYWORDS.firstOrNull { (keyword, _) -> keyword in id.path }?.second?.let(::ItemStack) ?: ItemStack.EMPTY
        val color = BiomeMapColors.of(id.toString()) ?: Minecraft.getInstance().level?.registryAccess()
            ?.lookup(Registries.BIOME)?.flatMap { registry -> registry.getOptional(id) }?.orElse(null)
            ?.getGrassColor(0.0, 0.0)
            ?: NEUTRAL_GRASS
        return DeedPreview.Biome(color, icon)
    }

    /**
     * Named by our language file; biome-specific variants (abandoned_camp_forest, ruined_portal_desert)
     * reuse the base name followed by the biome or place in brackets.
     */
    private fun structureName(id: Identifier): Component {
        val key = "gui.$MOD_ID.structure.${id.namespace}.${id.path}"
        if (Language.getInstance().has(key)) return key.translatable
        val base = STRUCTURE_VARIANT_BASES.firstOrNull { base -> id.path.startsWith("${base}_") }
        if (base != null) {
            val baseKey = "gui.$MOD_ID.structure.${id.namespace}.$base"
            val suffix = id.path.removePrefix("${base}_")
            val suffixName = nameOr("biome.${id.namespace}.$suffix", Identifier.fromNamespaceAndPath(id.namespace, suffix))
            if (Language.getInstance().has(baseKey)) {
                return "gui.$MOD_ID.structure.variant".translatable(baseKey.translatable, suffixName)
            }
        }
        return nameOr(key, id)
    }

    private fun structurePreview(id: Identifier): DeedPreview.Structure {
        val marker = STRUCTURE_MAP_ICONS[id.path].takeIf { id.namespace == "minecraft" }
            ?: STRUCTURE_VARIANT_BASES.firstOrNull { base -> id.path.startsWith("${base}_") }?.let(STRUCTURE_MAP_ICONS::get)
        val icon = marker?.let { name -> Identifier.withDefaultNamespace("textures/map/decorations/$name.png") }
        val block = STRUCTURE_BLOCKS.entries.firstOrNull { (keyword, _) -> keyword in id.path }?.value ?: Items.FILLED_MAP
        return DeedPreview.Structure(icon, ItemStack(block))
    }

    private fun biomeName(id: Identifier): Component = nameOr("biome.${id.namespace}.${id.path}", id)

    private fun nameOr(key: String, id: Identifier): Component =
        if (Language.getInstance().has(key)) key.translatable
        else id.path.split('_', '/').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }.literal

    /** Structures with per-biome variants: the variant id is the base id plus a suffix. */
    private val STRUCTURE_VARIANT_BASES = listOf("abandoned_camp", "ruined_portal", "village", "ocean_ruin", "shipwreck", "mineshaft")

    /** Explorer-map marker textures (textures/map/decorations) of vanilla structures. */
    private val STRUCTURE_MAP_ICONS = mapOf(
        "village_plains" to "plains_village",
        "village_desert" to "desert_village",
        "village_savanna" to "savanna_village",
        "village_snowy" to "snowy_village",
        "village_taiga" to "taiga_village",
        "mansion" to "woodland_mansion",
        "monument" to "ocean_monument",
        "jungle_pyramid" to "jungle_temple",
        "desert_pyramid" to "desert_pyramid",
        "swamp_hut" to "swamp_hut",
        "trial_chambers" to "trial_chambers",
        "ancient_city" to "ancient_city",
        "mineshaft" to "mineshaft",
        "abandoned_camp" to "abandoned_camp",
        "ocean_ruin" to "warm_ocean_ruins",
        "ocean_ruin_warm" to "warm_ocean_ruins",
        "ocean_ruin_cold" to "warm_ocean_ruins",
        "buried_treasure" to "red_x"
    )

    /** Structures without a map marker: a block or item players associate with them. */
    private val STRUCTURE_BLOCKS: Map<String, Item> = linkedMapOf(
        "pillager_outpost" to Items.CROSSBOW,
        "igloo" to Items.SNOW_BLOCK,
        "shipwreck" to Items.OAK_BOAT,
        "stronghold" to Items.END_PORTAL_FRAME,
        "fortress" to Items.NETHER_BRICKS,
        "nether_fossil" to Items.BONE_BLOCK,
        "end_city" to Items.PURPUR_BLOCK,
        "bastion" to Items.GILDED_BLACKSTONE,
        "ruined_portal" to Items.CRYING_OBSIDIAN,
        "trail_ruins" to Items.BRUSH
    )

    private val DIMENSION_BLOCKS = mapOf(
        "minecraft:overworld" to Blocks.GRASS_BLOCK,
        "minecraft:the_nether" to Blocks.NETHERRACK,
        "minecraft:the_end" to Blocks.END_STONE
    )

    /** First matching keyword of the biome path wins, so specific words come before general ones. */
    private val BIOME_ICON_KEYWORDS: List<Pair<String, Item>> = listOf(
        "cherry" to Items.CHERRY_SAPLING,
        "pale_garden" to Items.PALE_OAK_SAPLING,
        "mangrove" to Items.MANGROVE_PROPAGULE,
        "birch" to Items.BIRCH_SAPLING,
        "dark_forest" to Items.DARK_OAK_SAPLING,
        "bamboo" to Items.BAMBOO,
        "jungle" to Items.JUNGLE_SAPLING,
        "taiga" to Items.SPRUCE_SAPLING,
        "grove" to Items.SPRUCE_SAPLING,
        "savanna" to Items.ACACIA_SAPLING,
        "flower" to Items.POPPY,
        "forest" to Items.OAK_SAPLING,
        "mushroom" to Items.RED_MUSHROOM,
        "crimson" to Items.CRIMSON_FUNGUS,
        "warped" to Items.WARPED_FUNGUS,
        "soul_sand" to Items.SOUL_SAND,
        "basalt" to Items.BASALT,
        "nether" to Items.NETHERRACK,
        "end" to Items.END_STONE,
        "desert" to Items.DEAD_BUSH,
        "badlands" to Items.TERRACOTTA,
        "swamp" to Items.LILY_PAD,
        "ocean" to Items.KELP,
        "river" to Items.WATER_BUCKET,
        "beach" to Items.SAND,
        "shore" to Items.STONE,
        "frozen" to Items.ICE,
        "ice" to Items.PACKED_ICE,
        "snowy" to Items.SNOWBALL,
        "peaks" to Items.STONE,
        "slopes" to Items.SNOW_BLOCK,
        "meadow" to Items.CORNFLOWER,
        "hills" to Items.GRAVEL,
        "lush" to Items.AZALEA,
        "cave" to Items.POINTED_DRIPSTONE,
        "deep_dark" to Items.SCULK,
        "plains" to Items.SHORT_GRASS
    )
}
