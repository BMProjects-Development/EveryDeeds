package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.DeedPalette
import ru.benos.everydeeds.client.config.EveryDeedsClientConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hover focus: once the cursor has rested on a cell, the whole screen dims and the object pops out as
 * an enlarged card (growing from the cell) with a large live model and a short summary below it.
 */
internal class TabloFocusCard(
    private val font: Font,
    private val palette: DeedPalette,
    private val dataSource: AchievementUiDataSource,
    config: EveryDeedsClientConfig
) {
    private val cellSize: Int = config.cellSize
    private val cardSize: Int = (config.cellSize * config.focusZoom).roundToInt()

    /** Draws the card for [hovered], whose focus opened at [openedAt] (for the grow-in animation). */
    fun render(graphics: GuiGraphicsExtractor, hovered: HoveredEntry, openedAt: Long, screenWidth: Int, screenHeight: Int) {
        val progress = ((Util.getMillis() - openedAt) / ANIMATION_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress)
        val size = (cellSize + (cardSize - cellSize) * eased).roundToInt()

        val entry = hovered.entry
        val lines = summaryLines(entry)
        val panelWidth = max(size, lines.maxOfOrNull { line -> font.width(line) + 12 } ?: 0)
        val panelHeight = lines.size * LINE_HEIGHT + 8

        val cardX = (hovered.rect.centerX - size / 2).coerceIn(MARGIN, max(MARGIN, screenWidth - MARGIN - size))
        var cardY = (hovered.rect.centerY - size / 2).coerceIn(MARGIN, max(MARGIN, screenHeight - MARGIN - size))
        // Keep the summary on screen: shift the card up if the summary would fall off the bottom.
        cardY = min(cardY, max(MARGIN, screenHeight - MARGIN - size - 4 - panelHeight))
        val card = UiRect(cardX, cardY, size, size)

        graphics.nextStratum()
        graphics.fill(0, 0, screenWidth, screenHeight, DIM)

        val completion = dataSource.completion(entry)
        graphics.fill(card.x, card.y, card.right, card.bottom, palette.slot)
        graphics.fill(card.x + 3, card.y + 3, card.right - 3, card.bottom - 3, TabloStyle.SLOT_INNER_HOVERED)
        graphics.outline(card.x, card.y, card.width, card.height, if (completion.isComplete) palette.complete else palette.accent)
        val inset = card.width / 10
        DeedPreviewRenderer.renderLive(graphics, entry.preview, card.x + inset, card.y + inset, card.right - inset, card.bottom - inset)
        TabloStyle.renderCompletionBar(graphics, palette, completion, card)

        val panelX = (card.centerX - panelWidth / 2).coerceIn(MARGIN, max(MARGIN, screenWidth - MARGIN - panelWidth))
        val panel = UiRect(panelX, card.bottom + 4, panelWidth, panelHeight)
        graphics.fill(panel.x, panel.y, panel.right, panel.bottom, TabloStyle.POPUP_BACKGROUND)
        graphics.outline(panel.x, panel.y, panel.width, panel.height, palette.border)
        lines.forEachIndexed { index, line ->
            graphics.centeredText(font, line, panel.centerX, panel.y + 5 + index * LINE_HEIGHT, if (index == 0) palette.text else palette.textMuted)
        }
    }

    /** Name, then what was done with the object, then how far its deeds (or its milestone amount) are. */
    private fun summaryLines(entry: AchievementUiEntry): List<Component> {
        val lines = ArrayList<Component>()
        lines += entry.name

        val discovered = dataSource.statistics(entry).mapNotNull { statistic -> statistic.count?.let { count -> statistic.action to count } }
        if (discovered.isEmpty()) {
            lines += "gui.$MOD_ID.ledger.nothing".translatable
        } else {
            discovered.forEach { (action, count) -> lines += "gui.$MOD_ID.ledger.line".translatable(action.displayName(entry.category), action.formatAmount(count)) }
        }

        val completion = dataSource.completion(entry)
        if (completion.total > 0) {
            val color = if (completion.isComplete) palette.complete else palette.inProgress
            val measure = completion.measure
            lines += if (measure != null) {
                measure.formatAmount(completion.completed.toLong()).copy().append(" / ").append(measure.formatAmount(completion.total.toLong())).withColor(color)
            } else {
                "gui.$MOD_ID.deeds.line".translatable(completion.completed, completion.total).withColor(color)
            }
        }
        return lines
    }

    private companion object {
        const val MARGIN = 6
        const val LINE_HEIGHT = 11
        const val ANIMATION_MS = 120L
        const val DIM = 0x99000000.toInt()
    }
}
