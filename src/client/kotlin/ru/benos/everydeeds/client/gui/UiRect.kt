package ru.benos.everydeeds.client.gui

import kotlin.math.max

/** An axis-aligned rectangle in GUI pixels; `right` and `bottom` are exclusive. */
data class UiRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(px: Int, py: Int): Boolean =
        px >= x && px < right && py >= y && py < bottom

    fun inset(amount: Int): UiRect =
        UiRect(x + amount, y + amount, max(0, width - amount * 2), max(0, height - amount * 2))

    companion object {
        val ZERO: UiRect = UiRect(0, 0, 0, 0)
    }
}
