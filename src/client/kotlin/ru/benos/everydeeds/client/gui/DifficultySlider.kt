package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.DeedPalette
import kotlin.math.abs

/**
 * Sidebar slider over the loaded deed sets (difficulties): built-in sets in ascending difficulty,
 * then custom sets alphabetically. Only operators can move it; everyone else just sees the level.
 */
class DifficultySlider(private val font: Font) {
    /** Height the slider needs in the sidebar. */
    val height: Int = 44

    fun orderedSets(available: List<String>): List<String> {
        val builtIn = BUILT_IN_ORDER.filter { set -> set in available }
        val custom = available.filter { set -> set !in BUILT_IN_ORDER }.sorted()
        return builtIn + custom
    }

    fun render(
        graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int,
        sets: List<String>, current: String, canChange: Boolean,
        mouseX: Int, mouseY: Int, palette: DeedPalette
    ) {
        graphics.text(font, "gui.$MOD_ID.difficulty".translatable, x, y, palette.textMuted)
        if (sets.isEmpty()) return

        val trackLeft = x + KNOB_HALF
        val trackRight = x + width - KNOB_HALF
        val trackY = y + 18
        graphics.fill(trackLeft, trackY, trackRight, trackY + 2, palette.border)

        val hoveredIndex = hoveredTick(x, y, width, sets.size, mouseX, mouseY)
        val currentIndex = sets.indexOf(current)
        sets.indices.forEach { index ->
            val tickX = tickX(trackLeft, trackRight, sets.size, index)
            val color = if (index <= currentIndex) palette.accent else palette.border
            graphics.fill(tickX - 1, trackY - 3, tickX + 1, trackY + 5, color)
        }
        if (currentIndex >= 0) {
            val knobX = tickX(trackLeft, trackRight, sets.size, currentIndex)
            val knobColor = if (canChange) palette.accent else palette.textMuted
            graphics.fill(knobX - KNOB_HALF, trackY - 5, knobX + KNOB_HALF, trackY + 7, knobColor)
            graphics.outline(knobX - KNOB_HALF, trackY - 5, KNOB_HALF * 2, 12, palette.text)
        }

        // Current level, or the hovered one (and whether it can be chosen).
        val label: Component = when {
            hoveredIndex != null && !canChange -> "gui.$MOD_ID.difficulty.locked".translatable
            hoveredIndex != null -> deedSetName(sets[hoveredIndex])
            else -> deedSetName(current)
        }
        val labelColor = if (hoveredIndex != null && hoveredIndex != currentIndex && canChange) palette.accent else palette.text
        graphics.text(font, font.substrByWidth(label, width).string, x, trackY + 12, labelColor)
    }

    /** The set under the cursor, if clicking would select it. */
    fun clickedSet(x: Int, y: Int, width: Int, sets: List<String>, mouseX: Int, mouseY: Int): String? =
        hoveredTick(x, y, width, sets.size, mouseX, mouseY)?.let { index -> sets[index] }

    private fun hoveredTick(x: Int, y: Int, width: Int, count: Int, mouseX: Int, mouseY: Int): Int? {
        if (count == 0 || mouseY < y + 10 || mouseY > y + 28 || mouseX < x || mouseX > x + width) return null
        val trackLeft = x + KNOB_HALF
        val trackRight = x + width - KNOB_HALF
        return (0 until count).minByOrNull { index -> abs(tickX(trackLeft, trackRight, count, index) - mouseX) }
    }

    private fun tickX(left: Int, right: Int, count: Int, index: Int): Int =
        if (count <= 1) (left + right) / 2 else left + (right - left) * index / (count - 1)

    private companion object {
        const val KNOB_HALF = 3
        val BUILT_IN_ORDER = listOf("short", "extended", "insane", "maniac")
    }
}
