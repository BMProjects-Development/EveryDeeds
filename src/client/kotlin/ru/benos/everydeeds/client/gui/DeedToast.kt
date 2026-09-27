package ru.benos.everydeeds.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.ClientConfigStore
import ru.benos.everydeeds.client.config.DeedPalette

/**
 * "Deed complete" toast, styled like the progress screen. Unlocks of the same [Kind] arriving while it
 * is visible are merged into it (cycling through them) instead of stacking dozens of toasts when, say,
 * a whole enchantment set completes at once.
 */
class DeedToast private constructor(private val kind: Kind) : Toast {
    /** What was completed: sets the toast's colour, title and sound. Each kind has its own toast. */
    enum class Kind(private val id: String, val sound: SoundEvent?, val color: (DeedPalette) -> Int) {
        /** A cell of the progress screen: an object with all of its deeds, or a whole family. */
        CELL("deed", null, DeedPalette::complete),

        /** A milestone ("Broken: 10 000"), with the level-up sound. */
        MILESTONE("milestone", SoundEvents.PLAYER_LEVELUP, DeedPalette::milestone),

        /** Every deed of the set, with the challenge fanfare. */
        EVERYTHING("everything", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, DeedPalette::finale);

        fun title(count: Int): Component =
            if (count > 1) "toast.$MOD_ID.$id.title_many".translatable(count) else "toast.$MOD_ID.$id.title".translatable
    }

    private data class Unlock(val icon: ItemStack, val description: Component)

    private val unlocks = ArrayList<Unlock>()
    private var lastChanged = 0L
    private var changed = false
    private var visibility: Toast.Visibility = Toast.Visibility.HIDE
    private var displayedIndex = 0

    /** Whether the toast has appeared on screen (a queued toast is not updated yet). */
    private var appeared = false

    override fun getToken(): Any = kind

    /** Played by the toast manager when the toast appears. */
    override fun getSoundEvent(): SoundEvent? = kind.sound

    override fun getWantedVisibility(): Toast.Visibility = visibility

    override fun update(manager: ToastManager, fullyVisibleForMs: Long) {
        appeared = true
        if (changed) {
            lastChanged = fullyVisibleForMs
            changed = false
        }

        val displayTime = DISPLAY_TIME_MS * manager.notificationDisplayTimeMultiplier
        visibility = if (unlocks.isEmpty() || fullyVisibleForMs - lastChanged >= displayTime) Toast.Visibility.HIDE else Toast.Visibility.SHOW
        if (unlocks.isNotEmpty()) {
            displayedIndex = ((fullyVisibleForMs / maxOf(1.0, displayTime / unlocks.size)).toInt()) % unlocks.size
        }
    }

    /** Drawn in the style of the progress screen: dark panel, slot with the icon, frame in the kind's colour. */
    override fun extractRenderState(graphics: GuiGraphicsExtractor, font: Font, fullyVisibleForMs: Long) {
        val palette = ClientConfigStore.current.colors
        val color = kind.color(palette)
        val width = width()
        val height = height()
        graphics.fill(0, 0, width, height, OPAQUE_PANEL)
        graphics.outline(0, 0, width, height, color)
        graphics.fill(0, 0, 3, height, color)

        graphics.fill(7, 6, 27, 26, palette.slot)
        graphics.fill(8, 7, 26, 25, palette.slotInner)
        val unlock = unlocks.getOrNull(displayedIndex) ?: return
        graphics.fakeItem(unlock.icon, 9, 8)

        graphics.text(font, kind.title(unlocks.size), 32, 7, color, false)
        graphics.text(font, font.substrByWidth(unlock.description, width - 38).string, 32, 18, palette.text, false)
    }

    private fun add(icon: ItemStack, description: Component) {
        unlocks += Unlock(icon, description)
        changed = true
    }

    companion object {
        private val OPAQUE_PANEL = 0xF01B1D20.toInt()
        private const val DISPLAY_TIME_MS = 5000.0

        /**
         * Shows the unlock in the toast of its [kind], opening one if there is none. A toast plays its
         * sound when it appears; an unlock merged into a toast already on screen plays it once more.
         */
        fun show(manager: ToastManager, kind: Kind, icon: ItemStack, description: Component) {
            val existing = manager.getToast(DeedToast::class.java, kind)
            if (existing == null) {
                manager.addToast(DeedToast(kind).also { toast -> toast.add(icon, description) })
                return
            }
            existing.add(icon, description)
            if (existing.appeared) {
                kind.sound?.let { sound -> Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, 1f, 1f)) }
            }
        }
    }
}
