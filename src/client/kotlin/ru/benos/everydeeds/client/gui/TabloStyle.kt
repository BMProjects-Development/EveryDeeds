package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import ru.benos.everydeeds.client.config.DeedPalette
import kotlin.math.roundToInt

/**
 * Fixed colours and small drawing helpers shared by several parts of the progress screen. The
 * configurable colours live in [DeedPalette]; these are the neutral shades around them.
 */
internal object TabloStyle {
    const val POPUP_BACKGROUND = 0xFF1B1D20.toInt()
    const val HEADER_LINE = 0xFF3C444C.toInt()
    const val SLOT_INNER_HOVERED = 0xFF343A40.toInt()
    const val COMPLETION_TRACK = 0xFF1A1D21.toInt()
    const val BUTTON_HOVERED = 0xFF30343A.toInt()
    const val BUTTON_IDLE = 0xFF202429.toInt()

    /** Alpha of the red wash over objects nobody has started yet (and over the switch that shows them). */
    private const val REMAINING_TINT = 0x30000000

    /** [color] as a faint wash, for "not started" backgrounds. */
    fun remainingWash(color: Int): Int = (color and 0x00FFFFFF) or REMAINING_TINT

    /** Hover shade of a colour: each channel moved a third of the way to white. */
    fun brighter(color: Int): Int {
        fun channel(shift: Int): Int {
            val value = (color ushr shift) and 0xFF
            return (value + (255 - value) / 3) shl shift
        }
        return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
    }

    /** Thin bar along the bottom of a slot: share of the object's deeds (or of a milestone's amount) done. */
    fun renderCompletionBar(graphics: GuiGraphicsExtractor, palette: DeedPalette, completion: EntryCompletion, rect: UiRect) {
        if (completion.total == 0 || completion.completed == 0) return

        val left = rect.x + 3
        val right = rect.right - 3
        val filled = left + ((right - left) * completion.fraction).roundToInt()
        graphics.fill(left, rect.bottom - 5, right, rect.bottom - 3, COMPLETION_TRACK)
        graphics.fill(left, rect.bottom - 5, filled, rect.bottom - 3, if (completion.isComplete) palette.complete else palette.inProgress)
    }
}
