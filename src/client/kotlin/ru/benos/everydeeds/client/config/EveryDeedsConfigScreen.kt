package ru.benos.everydeeds.client.config

import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import kotlin.math.roundToInt

/**
 * Client settings, built from vanilla option widgets so it looks like the game's own options.
 * Every change is written to `config/everydeeds-client.json` right away. Opened from Mod Menu.
 */
class EveryDeedsConfigScreen(lastScreen: Screen) :
    OptionsSubScreen(lastScreen, Minecraft.getInstance().options, "gui.$MOD_ID.config.title".translatable) {

    override fun addOptions() {
        val list = list ?: return
        val config = ClientConfigStore.current

        list.addHeader("gui.$MOD_ID.config.grid".translatable)
        list.addSmall(
            intOption("cell_size", EveryDeedsClientConfig.MIN_CELL_SIZE, EveryDeedsClientConfig.MAX_CELL_SIZE, config.cellSize,
                { value -> "gui.$MOD_ID.config.pixels".translatable(value) }) { value ->
                ClientConfigStore.update { current -> current.copy(cellSize = value) }
            },
            intOption("focus_zoom", 100, 300, (config.focusZoom * 100).roundToInt(),
                { value -> "gui.$MOD_ID.config.percent".translatable(value) }) { value ->
                ClientConfigStore.update { current -> current.copy(focusZoom = value / 100f) }
            },
            intOption("hover_delay", 0, MAX_HOVER_DELAY_STEPS, config.hoverDelayMs / HOVER_DELAY_STEP_MS,
                { value -> "gui.$MOD_ID.config.milliseconds".translatable(value * HOVER_DELAY_STEP_MS) }) { value ->
                ClientConfigStore.update { current -> current.copy(hoverDelayMs = value * HOVER_DELAY_STEP_MS) }
            },
            OptionInstance.createBoolean("gui.$MOD_ID.config.show_remaining", config.showRemaining) { value ->
                ClientConfigStore.update { current -> current.copy(showRemaining = value) }
            }
        )

        list.addHeader("gui.$MOD_ID.config.colors".translatable)
        for (color in PaletteColor.entries) {
            list.addSmall(StringWidget(COLUMN_WIDTH, 20, "gui.$MOD_ID.config.color.${color.key}".translatable, font), colorBox(color))
        }
    }

    override fun addFooter() {
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder("gui.$MOD_ID.config.reset".translatable) { _ ->
            ClientConfigStore.update { current -> EveryDeedsClientConfig(showRemaining = current.showRemaining) }
            minecraft.gui.setScreen(EveryDeedsConfigScreen(lastScreen))
        }.width(150).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { _ -> onClose() }.width(150).build())
    }

    /** Hex field (`#RRGGBB` or `#AARRGGBB`); the text turns red while it cannot be parsed. */
    private fun colorBox(color: PaletteColor): EditBox {
        val box = EditBox(font, COLUMN_WIDTH, 20, "gui.$MOD_ID.config.color.${color.key}".translatable)
        box.setMaxLength(9)
        box.value = "#%08X".format(color.get(ClientConfigStore.current.colors))
        box.setResponder { text ->
            val parsed = parseColor(text)
            box.setTextColor(if (parsed == null) INVALID_TEXT else VALID_TEXT)
            if (parsed != null) {
                ClientConfigStore.update { current -> current.copy(colors = color.set(current.colors, parsed)) }
            }
        }
        return box
    }

    private fun intOption(
        key: String, min: Int, max: Int, initial: Int,
        label: (Int) -> Component,
        onChange: (Int) -> Unit
    ): OptionInstance<Int> =
        OptionInstance(
            "gui.$MOD_ID.config.$key",
            OptionInstance.noTooltip(),
            { caption, value -> Options.genericValueLabel(caption, label(value)) },
            OptionInstance.IntRange(min, max, false),
            initial.coerceIn(min, max),
            { value -> onChange(value) }
        )

    /** The palette entries, with accessors to read and replace each one in the immutable [DeedPalette]. */
    private enum class PaletteColor(val key: String, val get: (DeedPalette) -> Int, val set: (DeedPalette, Int) -> DeedPalette) {
        ACCENT("accent", DeedPalette::accent, { palette, value -> palette.copy(accent = value) }),
        COMPLETE("complete", DeedPalette::complete, { palette, value -> palette.copy(complete = value) }),
        IN_PROGRESS("in_progress", DeedPalette::inProgress, { palette, value -> palette.copy(inProgress = value) }),
        REMAINING("remaining", DeedPalette::remaining, { palette, value -> palette.copy(remaining = value) }),
        TEXT("text", DeedPalette::text, { palette, value -> palette.copy(text = value) }),
        TEXT_MUTED("text_muted", DeedPalette::textMuted, { palette, value -> palette.copy(textMuted = value) }),
        PANEL("panel", DeedPalette::panel, { palette, value -> palette.copy(panel = value) }),
        SLOT("slot", DeedPalette::slot, { palette, value -> palette.copy(slot = value) }),
        SLOT_INNER("slot_inner", DeedPalette::slotInner, { palette, value -> palette.copy(slotInner = value) }),
        BORDER("border", DeedPalette::border, { palette, value -> palette.copy(border = value) }),
        MILESTONE("milestone", DeedPalette::milestone, { palette, value -> palette.copy(milestone = value) }),
        FINALE("finale", DeedPalette::finale, { palette, value -> palette.copy(finale = value) })
    }

    private companion object {
        const val COLUMN_WIDTH = 150
        const val HOVER_DELAY_STEP_MS = 50
        const val MAX_HOVER_DELAY_STEPS = 30
        const val VALID_TEXT = 0xFFE0E0E0.toInt()
        const val INVALID_TEXT = 0xFFFF5555.toInt()

        /** `#RRGGBB` (opaque) or `#AARRGGBB`; null when the text is not a colour. */
        fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6 && hex.length != 8) return null
            val value = hex.toLongOrNull(16) ?: return null
            return if (hex.length == 6) (value or 0xFF000000L).toInt() else value.toInt()
        }
    }
}
