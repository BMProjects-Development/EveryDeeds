package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import ru.benos.everydeeds.client.config.DeedPalette
import kotlin.math.roundToInt

/**
 * Bottom strip of the progress screen: a long bar with the progress through the whole set (every deed
 * of every category) and the "done / total" counter on its right. Once everything is done, the bar and
 * the counter turn the finale colour.
 */
internal class TabloFooter(
    private val font: Font,
    private val palette: DeedPalette,
    private val dataSource: AchievementUiDataSource
) {
    /** Where the strip is on screen; set by the screen's layout. */
    var area: UiRect = UiRect.ZERO

    fun render(graphics: GuiGraphicsExtractor) {
        val completion = dataSource.overallCompletion()
        val color = if (completion.isComplete) palette.finale else palette.inProgress

        // Spaced like the popup's "2 / 1 000": a bare slash would glue onto the thousands spaces ("33/8 380").
        val counter = "${formatNumber(completion.completed.toLong())} / ${formatNumber(completion.total.toLong())}"
        val counterX = area.right - font.width(counter)
        graphics.text(font, counter, counterX, area.y + (area.height - font.lineHeight) / 2 + 1, if (completion.isComplete) color else palette.text)

        val bar = UiRect(area.x, area.centerY - BAR_HEIGHT / 2, counterX - COUNTER_GAP - area.x, BAR_HEIGHT)
        graphics.fill(bar.x, bar.y, bar.right, bar.bottom, TabloStyle.COMPLETION_TRACK)
        val filled = ((bar.width - 2) * completion.fraction).roundToInt()
        if (filled > 0) graphics.fill(bar.x + 1, bar.y + 1, bar.x + 1 + filled, bar.bottom - 1, color)
        graphics.outline(bar.x, bar.y, bar.width, bar.height, palette.border)
    }

    companion object {
        /** Height the strip needs. */
        const val HEIGHT = 11

        private const val BAR_HEIGHT = 7
        private const val COUNTER_GAP = 6
    }
}
