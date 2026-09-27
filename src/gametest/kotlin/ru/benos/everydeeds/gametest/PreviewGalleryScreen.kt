package ru.benos.everydeeds.gametest

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import ru.benos.everydeeds.client.gui.DeedPreview
import ru.benos.everydeeds.client.gui.DeedPreviewRenderer
import kotlin.math.max

/**
 * Visual check of tricky previews: each target is drawn twice, grid icon (top) and live 3D (bottom).
 * Not part of the mod; only used to take screenshots in client game tests.
 */
class PreviewGalleryScreen : Screen(Component.literal("Preview gallery")) {
    private val previews: List<Pair<String, DeedPreview>> = listOf(
        "water" to block(Blocks.WATER),
        "lava" to block(Blocks.LAVA),
        "bed" to block(Blocks.STRAW_BED),
        "door" to block(Blocks.OAK_DOOR),
        "pane" to block(Blocks.GLASS_PANE),
        "bars" to block(Blocks.IRON_BARS),
        "wall" to block(Blocks.COBBLESTONE_WALL),
        "ench" to block(Blocks.ENCHANTING_TABLE),
        "chest" to block(Blocks.CHEST),
        "fire" to block(Blocks.FIRE),
        "portal" to block(Blocks.NETHER_PORTAL),
        "sword" to DeedPreview.Item(ItemStack(Items.DIAMOND_SWORD)),
        "stone" to DeedPreview.Item(ItemStack(Items.STONE)),
        "zombie" to entity(EntityTypes.ZOMBIE),
        "creeper" to entity(EntityTypes.CREEPER),
        "chicken" to entity(EntityTypes.CHICKEN),
        "rabbit" to entity(EntityTypes.RABBIT),
        "horse" to entity(EntityTypes.HORSE),
        "slime" to entity(EntityTypes.SLIME),
        "boat" to entity(EntityTypes.OAK_BOAT),
        "chestboat" to entity(EntityTypes.OAK_CHEST_BOAT)
    )

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        graphics.fill(0, 0, width, height, 0xFF202428.toInt())
        // The largest cell at which every preview fits on screen (two stacked squares and a label per preview).
        val cell = (52 downTo 20).first { size ->
            val columns = max(1, (width - 8) / size)
            (previews.size + columns - 1) / columns * (size * 2 + 14) <= height - 4
        }
        val perRow = max(1, (width - 8) / cell)
        previews.forEachIndexed { index, (label, preview) ->
            val x = 4 + (index % perRow) * cell
            val y = 4 + (index / perRow) * (cell * 2 + 14)
            graphics.fill(x, y, x + cell - 4, y + cell - 4, 0xFF2A2E33.toInt())
            DeedPreviewRenderer.renderIcon(graphics, preview, x + 8, y + 8, x + cell - 12, y + cell - 12)
            graphics.fill(x, y + cell, x + cell - 4, y + cell * 2 - 4, 0xFF2A2E33.toInt())
            DeedPreviewRenderer.renderLive(graphics, preview, x + 2, y + cell + 2, x + cell - 6, y + cell * 2 - 6)
            graphics.text(font, label, x + 2, y + cell * 2, 0xFFE9ECEF.toInt())
        }
    }

    private fun block(block: Block): DeedPreview =
        DeedPreview.Block(block.defaultBlockState(), ItemStack(Items.BARRIER))

    private fun entity(type: EntityType<*>): DeedPreview =
        DeedPreview.Entity(type, ItemStack(Items.NAME_TAG))
}
