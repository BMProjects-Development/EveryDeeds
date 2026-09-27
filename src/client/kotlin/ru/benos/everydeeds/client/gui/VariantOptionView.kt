package ru.benos.everydeeds.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.deed.VariantOption

/** How one missing variant is shown in the details window: a small icon and a one-line description. */
object VariantOptionView {
    fun label(option: VariantOption): Component =
        when (option) {
            is VariantOption.State -> stateLabel(option.state)
            is VariantOption.PropertyValue -> "${option.property}=${option.value}".literal
            is VariantOption.Enchantment -> enchantmentLabel(option.enchantment, option.level)
            is VariantOption.Damage ->
                "gui.$MOD_ID.variant.durability".translatable(option.maxDamage - option.damage, option.maxDamage)
            is VariantOption.Potion -> potionStack(option.potion).hoverName
            is VariantOption.EntityVariant -> entityVariantLabel(option)
            is VariantOption.EffectLevel -> effectLabel(option)
        }

    /** Icon at ([x], [y]), 16x16: the block in that state, an enchanted book, or the item at that durability. */
    fun renderIcon(graphics: GuiGraphicsExtractor, font: Font, option: VariantOption, targetId: Identifier, x: Int, y: Int) {
        when (option) {
            is VariantOption.State -> DeedPreviewRenderer.renderStateIcon(graphics, option.state, x, y, x + 16, y + 16)
            is VariantOption.PropertyValue -> DeedPreviewRenderer.renderStateIcon(graphics, option.state, x, y, x + 16, y + 16)
            is VariantOption.Enchantment -> graphics.fakeItem(ItemStack(Items.ENCHANTED_BOOK), x, y)
            is VariantOption.Damage -> {
                val stack = ItemStack(BuiltInRegistries.ITEM.getValue(targetId))
                stack.damageValue = option.damage
                graphics.fakeItem(stack, x, y)
                graphics.itemDecorations(font, stack, x, y)
            }
            is VariantOption.Potion -> graphics.fakeItem(potionStack(option.potion), x, y)
            is VariantOption.EffectLevel -> {
                val effect = BuiltInRegistries.MOB_EFFECT.get(option.effect).orElse(null)
                if (effect != null) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect), x - 1, y - 1, 18, 18)
            }
            is VariantOption.EntityVariant -> {
                val type = BuiltInRegistries.ENTITY_TYPE.getValue(targetId)
                val egg = SpawnEggItem.byId(type).map { holder -> ItemStack(holder.value()) }.orElse(ItemStack(Items.NAME_TAG))
                graphics.fakeItem(egg, x, y)
            }
        }
    }

    /** "Speed II": the effect name with the level as a Roman numeral, like the game shows it. */
    private fun effectLabel(option: VariantOption.EffectLevel): Component {
        val name = BuiltInRegistries.MOB_EFFECT.get(option.effect).map { effect -> effect.value().displayName.copy() }
            .orElse(option.effect.toString().literal)
        return name.append(" ").append("enchantment.level.${option.level}".translatable)
    }

    /** Forms get words ("Baby", "Charged"); component values read as "variant: tabby", "color: white". */
    private fun entityVariantLabel(option: VariantOption.EntityVariant): Component {
        if (option.dimension.namespace == MOD_ID) {
            return "gui.$MOD_ID.variant.${option.dimension.path}.${option.value}".translatable
        }
        val dimension = option.dimension.path.substringAfterLast('/')
        val value = option.value.substringAfter(':')
        return "$dimension: $value".literal
    }

    private fun potionStack(id: Identifier): ItemStack {
        val potion = Minecraft.getInstance().connection?.registryAccess()
            ?.lookup(Registries.POTION)?.flatMap { registry -> registry.get(ResourceKey.create(Registries.POTION, id)) }
            ?.orElse(null) ?: return ItemStack(Items.POTION)
        return PotionContents.createItemStack(Items.POTION, potion)
    }

    /** Only what differs from the block's default state, so rows stay short and distinguishable. */
    private fun stateLabel(state: BlockState): Component {
        val default = state.block.defaultBlockState()
        val changed = state.properties.filter { property -> state.getValue(property) != default.getValue(property) }
        if (changed.isEmpty()) return "gui.$MOD_ID.variant.default_state".translatable
        return changed.joinToString(", ") { property -> "${property.name}=${valueName(state, property)}" }.literal
    }

    private fun <T : Comparable<T>> valueName(state: BlockState, property: Property<T>): String =
        property.getName(state.getValue(property))

    private fun enchantmentLabel(id: Identifier, level: Int?): Component {
        val holder = Minecraft.getInstance().connection?.registryAccess()
            ?.lookup(Registries.ENCHANTMENT)?.flatMap { registry -> registry.get(ResourceKey.create(Registries.ENCHANTMENT, id)) }
            ?.orElse(null)
            ?: return (if (level == null) id.toString() else "$id $level").literal
        return if (level == null) holder.value().description() else Enchantment.getFullname(holder, level)
    }
}
