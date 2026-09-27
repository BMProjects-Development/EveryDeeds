package ru.benos.everydeeds.client.gui

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Util
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.DeedPalette
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.network.ChangeDeedSetPayload
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Left panel of the progress screen: the category buttons, the "not started" switch and the
 * difficulty slider. It owns no screen state: choices are passed on through the callbacks (the
 * panel's signals), and the screen tells it what is selected when drawing.
 */
internal class TabloSidebar(
    private val font: Font,
    private val palette: DeedPalette,
    private val dataSource: AchievementUiDataSource,
    showRemaining: Boolean,
    private val onCategorySelected: (DeedCategory) -> Unit,
    private val onRemainingToggled: () -> Unit
) {
    /** Where the panel is on screen; set by the screen's layout. */
    var area: UiRect = UiRect.ZERO

    private val difficultySlider = DifficultySlider(font)

    // Knob of the "not started" switch: 0 = off (left), 1 = on (right); it slides towards the current state.
    private var switchKnob: Float = if (showRemaining) 1f else 0f
    private var lastFrameAt: Long = Util.getMillis()

    /** [interactive] is false while a modal window covers the screen: nothing reacts to the cursor then. */
    fun render(
        graphics: GuiGraphicsExtractor, selected: DeedCategory, showRemaining: Boolean,
        mouseX: Int, mouseY: Int, interactive: Boolean
    ) {
        graphics.text(font, "gui.$MOD_ID.categories".translatable, area.x + 12, area.y + 12, palette.text)
        DeedCategory.entries.forEach { category ->
            renderCategory(graphics, category, category == selected, interactive && categoryRect(category).contains(mouseX, mouseY))
        }
        renderRemainingSwitch(graphics, showRemaining, interactive && remainingSwitchRect().contains(mouseX, mouseY))

        if (ClientDeedSnapshot.availableSets.isNotEmpty()) {
            val slider = difficultyArea()
            val sets = difficultySlider.orderedSets(ClientDeedSnapshot.availableSets)
            difficultySlider.render(
                graphics, slider.x, slider.y, slider.width, sets, ClientDeedSnapshot.set, ClientDeedSnapshot.canChangeSet,
                if (interactive) mouseX else -1, mouseY, palette
            )
        }
    }

    /** Handles a left click; true when it landed on something of the sidebar. */
    fun mouseClicked(x: Int, y: Int): Boolean {
        DeedCategory.entries.firstOrNull { category -> categoryRect(category).contains(x, y) }?.let { category ->
            onCategorySelected(category)
            return true
        }
        if (remainingSwitchRect().contains(x, y)) {
            onRemainingToggled()
            return true
        }
        return clickDifficulty(x, y)
    }

    fun categoryRect(category: DeedCategory): UiRect {
        val height = categoryButtonHeight()
        return UiRect(area.x + 10, area.y + CATEGORIES_TOP + category.ordinal * (height + categoryButtonGap()), area.width - 20, height)
    }

    private fun renderCategory(graphics: GuiGraphicsExtractor, category: DeedCategory, selected: Boolean, hovered: Boolean) {
        val rect = categoryRect(category)
        val background = when {
            selected -> CATEGORY_SELECTED
            hovered -> TabloStyle.BUTTON_HOVERED
            else -> TabloStyle.BUTTON_IDLE
        }
        val border = when {
            selected -> palette.accent
            hovered -> CATEGORY_BORDER_HOVERED
            else -> CATEGORY_BORDER_IDLE
        }
        graphics.fill(rect.x, rect.y, rect.right, rect.bottom, background)
        graphics.outline(rect.x, rect.y, rect.width, rect.height, border)
        if (selected) graphics.fill(rect.x, rect.y, rect.x + 4, rect.bottom, palette.accent)

        val textY = rect.y + (rect.height - font.lineHeight) / 2
        val completion = dataSource.categoryCompletion(category)
        var nameRight = rect.right - 8
        if (completion.total > 0) {
            val summary = "${completion.completed}/${completion.total}"
            val summaryX = rect.right - font.width(summary) - 8
            graphics.text(font, summary, summaryX, textY, if (completion.isComplete) palette.complete else palette.textMuted)
            nameRight = summaryX - 4
        }
        // A narrow sidebar slides a long name instead of letting it run into the counter.
        ScrollingText.draw(graphics, font, category.displayName, rect.x + 12, nameRight, textY, if (selected) TEXT_SELECTED else palette.text)
    }

    /** The knob slides right when the not-started objects are shown. */
    private fun renderRemainingSwitch(graphics: GuiGraphicsExtractor, showRemaining: Boolean, hovered: Boolean) {
        val now = Util.getMillis()
        val step = (now - lastFrameAt) / SWITCH_ANIMATION_MS
        lastFrameAt = now
        val target = if (showRemaining) 1f else 0f
        switchKnob = if (switchKnob < target) min(target, switchKnob + step) else max(target, switchKnob - step)

        val row = remainingSwitchRect()
        val track = UiRect(row.x + 2, row.y + (row.height - SWITCH_HEIGHT) / 2, SWITCH_WIDTH, SWITCH_HEIGHT)
        graphics.fill(track.x, track.y, track.right, track.bottom, if (showRemaining) TabloStyle.remainingWash(palette.remaining) else TabloStyle.BUTTON_IDLE)
        graphics.outline(track.x, track.y, track.width, track.height, if (hovered) palette.text else palette.border)

        val knobSize = SWITCH_HEIGHT - 2
        val knobX = track.x + 1 + ((track.width - 2 - knobSize) * switchKnob).roundToInt()
        graphics.fill(knobX, track.y + 1, knobX + knobSize, track.y + 1 + knobSize, if (showRemaining) palette.remaining else palette.textMuted)

        val labelColor = when {
            showRemaining -> palette.remaining
            hovered -> palette.text
            else -> palette.textMuted
        }
        val labelY = row.y + (row.height - font.lineHeight) / 2 + 1
        ScrollingText.draw(graphics, font, "gui.$MOD_ID.remaining.toggle".translatable, track.right + 6, row.right, labelY, labelColor)
    }

    /** Sends a set change for the clicked difficulty tick; the server re-checks the permission. */
    private fun clickDifficulty(x: Int, y: Int): Boolean {
        val slider = difficultyArea()
        val sets = difficultySlider.orderedSets(ClientDeedSnapshot.availableSets)
        val clicked = difficultySlider.clickedSet(slider.x, slider.y, slider.width, sets, x, y) ?: return false
        if (ClientDeedSnapshot.canChangeSet && clicked != ClientDeedSnapshot.set && ClientPlayNetworking.canSend(ChangeDeedSetPayload.TYPE)) {
            ClientPlayNetworking.send(ChangeDeedSetPayload(clicked))
        }
        return true
    }

    // region Geometry

    /** Height left for the category buttons between the header and the switch and slider below them. */
    private fun categorySpace(): Int = difficultyArea().y - 4 - SWITCH_ROW_HEIGHT - (area.y + CATEGORIES_TOP)

    /** Space between category buttons: tighter when the sidebar is short. */
    private fun categoryButtonGap(): Int =
        if (categorySpace() / DeedCategory.entries.size >= font.lineHeight + 4 + CATEGORY_BUTTON_GAP) CATEGORY_BUTTON_GAP else 1

    /**
     * Buttons shrink on small windows so the categories, the switch and the difficulty slider always
     * fit in the sidebar without overlapping.
     */
    private fun categoryButtonHeight(): Int {
        val fitting = categorySpace() / DeedCategory.entries.size - categoryButtonGap()
        return fitting.coerceIn(font.lineHeight + 1, CATEGORY_BUTTON_HEIGHT)
    }

    /** Whole row of the "not started" switch (switch and label), right under the category buttons. */
    private fun remainingSwitchRect(): UiRect {
        val lastCategory = categoryRect(DeedCategory.entries.last())
        return UiRect(lastCategory.x, lastCategory.bottom + 3, lastCategory.width, 16)
    }

    /** The difficulty slider sits at the bottom of the sidebar. */
    private fun difficultyArea(): UiRect =
        UiRect(area.x + 12, area.bottom - difficultySlider.height - 8, area.width - 24, difficultySlider.height)

    // endregion

    private companion object {
        const val CATEGORIES_TOP = 32
        const val CATEGORY_BUTTON_HEIGHT = 22
        const val CATEGORY_BUTTON_GAP = 4
        const val SWITCH_ROW_HEIGHT = 22
        const val SWITCH_WIDTH = 20
        const val SWITCH_HEIGHT = 10
        const val SWITCH_ANIMATION_MS = 120f

        const val TEXT_SELECTED = 0xFFFFFFFF.toInt()
        const val CATEGORY_SELECTED = 0xFF314A48.toInt()
        const val CATEGORY_BORDER_HOVERED = 0xFF69727B.toInt()
        const val CATEGORY_BORDER_IDLE = 0xFF363C43.toInt()
    }
}
