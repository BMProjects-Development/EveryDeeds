package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Text that does not fit its box slides left and right (with a pause at each end) inside a scissor,
 * like vanilla button labels, instead of being cut with "...". Text that fits is drawn normally.
 */
object ScrollingText {
    /** Draws [text] in `[x0, x1)` at [y]; when it fits, centred if [centered], otherwise left-aligned. */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font, text: Component,
        x0: Int, x1: Int, y: Int, color: Int, centered: Boolean = false
    ) {
        val boxWidth = x1 - x0
        val textWidth = font.width(text)
        if (textWidth <= boxWidth) {
            val x = if (centered) x0 + (boxWidth - textWidth) / 2 else x0
            graphics.text(font, text, x, y, color)
            return
        }

        val overflow = textWidth - boxWidth
        val seconds = Util.getMillis() / 1000.0
        // Same easing as vanilla scrolling strings: slow at both ends, one cycle takes longer for longer text.
        val period = max(overflow * 0.5, 3.0)
        val phase = sin(PI / 2 * cos(2 * PI * seconds / period)) / 2 + 0.5
        val offset = (phase * overflow).toInt()

        graphics.enableScissor(x0, y - 1, x1, y + font.lineHeight + 1)
        graphics.text(font, text, x0 - offset, y, color)
        graphics.disableScissor()
    }
}
