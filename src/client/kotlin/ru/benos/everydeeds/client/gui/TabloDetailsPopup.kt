package ru.benos.everydeeds.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.DeedPalette
import ru.benos.everydeeds.deed.DeedCategory
import kotlin.math.max
import kotlin.math.min

/**
 * Modal details window of one object:
 * ```
 * +------------+  <name>                         [x]
 * |  rotating  |  <id>
 * |   model    |
 * +------------+  Statistics:
 *                 ├─ Broken: 12
 *                 └─ ?
 * --------------------------------------------------
 * Deeds                                       status
 * ```
 * Variant goals that can be listed are underlined: hovering one shows the variants still missing in a
 * floating list (scrolled with the wheel). For a family (copper, candle cakes) the rows are its members,
 * and clicking one opens that member's own details.
 */
internal class TabloDetailsPopup(
    private val font: Font,
    private val palette: DeedPalette,
    private val dataSource: AchievementUiDataSource
) {
    var entry: AchievementUiEntry? = null
        private set

    /** The listable variant goal under the cursor, whose missing variants are shown. */
    var hoveredRequirement: AchievementUiRequirement? = null
        private set

    private var variantScroll: Int = 0
    private var variantListSize: Int = 0

    // Rows that open another object (family members), as laid out in the last frame.
    private var memberRows: List<Pair<UiRect, AchievementUiEntry>> = emptyList()

    val isOpen: Boolean get() = entry != null

    fun open(entry: AchievementUiEntry) {
        this.entry = entry
        hoveredRequirement = null
        variantScroll = 0
        memberRows = emptyList()
    }

    fun close() {
        entry = null
        hoveredRequirement = null
        memberRows = emptyList()
    }

    // region Input

    /** A click while open: a member row opens that member, the close button or anything outside closes. */
    fun mouseClicked(x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        val opened = entry ?: return
        memberRows.firstOrNull { (row, _) -> row.contains(x, y) }?.let { (_, member) ->
            open(member)
            return
        }
        val rect = windowRect(opened, screenWidth, screenHeight)
        if (closeButtonRect(rect).contains(x, y) || !rect.contains(x, y)) close()
    }

    /** The wheel scrolls the missing-variants list, if one is shown and longer than it can show. */
    fun mouseScrolled(amount: Double) {
        if (hoveredRequirement == null || variantListSize <= VARIANT_ROWS) return
        variantScroll = (variantScroll - amount.toInt().coerceIn(-1, 1)).coerceIn(0, variantListSize - VARIANT_ROWS)
    }

    // endregion

    // region Rendering

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, screenWidth: Int, screenHeight: Int) {
        val entry = entry ?: return
        val statistics = dataSource.statistics(entry)
        val requirements = dataSource.requirements(entry)
        val rect = windowRect(statistics.size, requirements.size, screenWidth, screenHeight)
        val closeRect = closeButtonRect(rect)

        graphics.nextStratum()
        graphics.fill(0, 0, screenWidth, screenHeight, DIM)
        graphics.fill(rect.x, rect.y, rect.right, rect.bottom, TabloStyle.POPUP_BACKGROUND)
        graphics.outline(rect.x, rect.y, rect.width, rect.height, palette.accent)

        // Model box.
        val modelBox = UiRect(rect.x + PADDING, rect.y + PADDING, MODEL_SIZE, MODEL_SIZE)
        graphics.fill(modelBox.x, modelBox.y, modelBox.right, modelBox.bottom, palette.slotInner)
        graphics.outline(modelBox.x, modelBox.y, modelBox.width, modelBox.height, palette.border)
        DeedPreviewRenderer.renderLive(graphics, entry.preview, modelBox.x + 4, modelBox.y + 4, modelBox.right - 4, modelBox.bottom - 4)

        // Name and a smaller grey id.
        val textX = modelBox.right + 10
        ScrollingText.draw(graphics, font, entry.name, textX, closeRect.x - 6, rect.y + PADDING + 2, palette.text)
        val idScale = idScale()
        graphics.pose().pushMatrix()
        graphics.pose().translate(textX.toFloat(), (rect.y + PADDING + 14).toFloat())
        graphics.pose().scale(idScale, idScale)
        graphics.text(font, entry.id.toString(), 0, 0, palette.textMuted)
        graphics.pose().popMatrix()

        val statisticsBottom = renderStatistics(graphics, entry.category, statistics, textX, rect.y + PADDING + 30)
        renderCloseButton(graphics, closeRect, closeRect.contains(mouseX, mouseY))

        // Deeds of this object with their status.
        var y = max(statisticsBottom, modelBox.bottom) + 6
        graphics.fill(rect.x + PADDING, y, rect.right - PADDING, y + 1, TabloStyle.HEADER_LINE)
        y += 6
        graphics.text(font, "gui.$MOD_ID.popup.requirements".translatable, rect.x + PADDING, y, palette.textMuted)
        y += ROW_HEIGHT
        if (requirements.isEmpty()) {
            graphics.text(font, "gui.$MOD_ID.popup.no_requirements".translatable, rect.x + PADDING + 4, y, palette.textMuted)
        }
        renderRequirements(graphics, entry, requirements, rect, y, mouseX, mouseY, screenWidth, screenHeight)
    }

    /** Statistics tree: one trunk from the header down to the last row, a short branch to each row. Returns the bottom. */
    private fun renderStatistics(graphics: GuiGraphicsExtractor, category: DeedCategory, statistics: List<ActionStatistic>, x: Int, top: Int): Int {
        var y = top
        graphics.text(font, "gui.$MOD_ID.popup.statistics".translatable, x, y, palette.text)
        y += LINE_HEIGHT

        val trunkX = x + 3
        val trunkTop = y - 2
        statistics.forEachIndexed { index, statistic ->
            val branchY = y + font.lineHeight / 2 - 1
            val trunkBottom = if (index == statistics.lastIndex) branchY + 1 else y + LINE_HEIGHT
            graphics.fill(trunkX, if (index == 0) trunkTop else y, trunkX + 1, trunkBottom, palette.textMuted)
            graphics.fill(trunkX + 1, branchY, trunkX + TREE_BRANCH_LENGTH, branchY + 1, palette.textMuted)

            // Actions not done yet stay hidden behind "?".
            val count = statistic.count
            val line = if (count == null) "?".literal
            else "gui.$MOD_ID.ledger.line".translatable(statistic.action.displayName(category), statistic.action.formatAmount(count))
            graphics.text(font, line, trunkX + TREE_BRANCH_LENGTH + 3, y, if (count == null) palette.textMuted else palette.text)
            y += LINE_HEIGHT
        }
        return y
    }

    private fun renderCloseButton(graphics: GuiGraphicsExtractor, rect: UiRect, hovered: Boolean) {
        graphics.fill(rect.x, rect.y, rect.right, rect.bottom, if (hovered) TabloStyle.BUTTON_HOVERED else TabloStyle.BUTTON_IDLE)
        graphics.outline(rect.x, rect.y, rect.width, rect.height, if (hovered) palette.accent else palette.border)
        graphics.centeredText(font, "x", rect.centerX, rect.y + 3, palette.text)
    }

    private fun renderRequirements(
        graphics: GuiGraphicsExtractor, entry: AchievementUiEntry, requirements: List<AchievementUiRequirement>,
        rect: UiRect, top: Int, mouseX: Int, mouseY: Int, screenWidth: Int, screenHeight: Int
    ) {
        var y = top
        var hovered: AchievementUiRequirement? = null
        val rows = ArrayList<Pair<UiRect, AchievementUiEntry>>()
        for (requirement in requirements) {
            val (statusText, statusColor) = statusOf(requirement.status)
            val statusWidth = font.width(statusText)
            val titleX = rect.x + PADDING + 4
            val titleWidth = rect.width - PADDING * 2 - 4 - statusWidth - 8
            ScrollingText.draw(graphics, font, requirement.title, titleX, titleX + titleWidth, y, palette.text)
            graphics.text(font, statusText, rect.right - statusWidth - PADDING, y, statusColor)

            val row = UiRect(rect.x + PADDING, y - 2, rect.width - PADDING * 2, ROW_HEIGHT)
            val underline = { color: Int ->
                graphics.fill(titleX, y + font.lineHeight, titleX + min(titleWidth, font.width(requirement.title)), y + font.lineHeight + 1, color)
            }
            // Family members open their own details; they read as links when hovered.
            requirement.member?.let { member ->
                rows += row to member
                if (row.contains(mouseX, mouseY)) underline(palette.accent)
            }
            // Listable variant goals are underlined; hovering them lists what is still missing.
            if (requirement.listable) {
                underline(palette.text)
                if (row.contains(mouseX, mouseY)) hovered = requirement
            }
            y += ROW_HEIGHT
        }

        memberRows = rows
        if (hovered != hoveredRequirement) {
            hoveredRequirement = hovered
            variantScroll = 0
        }
        hovered?.let { requirement -> renderMissingVariants(graphics, entry, requirement, mouseX, mouseY, screenWidth, screenHeight) }
    }

    private fun statusOf(status: RequirementStatus): Pair<Component, Int> =
        when (status) {
            RequirementStatus.Complete -> "gui.$MOD_ID.requirement.complete".translatable to palette.complete
            RequirementStatus.Unexplored -> "gui.$MOD_ID.requirement.unexplored".translatable to palette.inProgress
            is RequirementStatus.Counting ->
                status.action.formatAmount(status.current).copy().append(" / ").append(status.action.formatAmount(status.required)) to palette.inProgress
            is RequirementStatus.RemainingUnexplored ->
                "gui.$MOD_ID.requirement.remaining_unexplored".translatable(status.remaining) to palette.inProgress
            is RequirementStatus.Partial -> "${status.done}/${status.total}".literal to palette.inProgress
        }

    /** Floating list next to the cursor: the variants of the hovered goal never collected, each with an icon. */
    private fun renderMissingVariants(
        graphics: GuiGraphicsExtractor, entry: AchievementUiEntry, requirement: AchievementUiRequirement,
        mouseX: Int, mouseY: Int, screenWidth: Int, screenHeight: Int
    ) {
        val missing = dataSource.missingVariants(requirement)
        val options = (missing as? MissingVariants.Ready)?.missing.orEmpty()
        variantListSize = options.size
        variantScroll = variantScroll.coerceIn(0, max(0, options.size - VARIANT_ROWS))

        val header: Component = when (missing) {
            MissingVariants.NotListable -> return
            MissingVariants.Loading -> "gui.$MOD_ID.variant.loading".translatable
            is MissingVariants.Ready ->
                if (options.isEmpty()) "gui.$MOD_ID.variant.all_collected".translatable
                else "gui.$MOD_ID.variant.missing".translatable(options.size, missing.total)
        }

        val rows = min(VARIANT_ROWS, options.size)
        val panelWidth = min(VARIANT_PANEL_WIDTH, screenWidth - 16)
        val panelHeight = 8 + font.lineHeight + 4 + rows * VARIANT_ROW_HEIGHT + 4
        val panel = UiRect(
            (mouseX + 12).coerceAtMost(screenWidth - panelWidth - 8).coerceAtLeast(8),
            (mouseY - 8).coerceAtMost(screenHeight - panelHeight - 8).coerceAtLeast(8),
            panelWidth, panelHeight
        )

        graphics.nextStratum()
        graphics.fill(panel.x, panel.y, panel.right, panel.bottom, TabloStyle.POPUP_BACKGROUND)
        graphics.outline(panel.x, panel.y, panel.width, panel.height, palette.accent)
        graphics.text(font, header, panel.x + 6, panel.y + 6, palette.textMuted)

        val listTop = panel.y + 8 + font.lineHeight + 4
        var rowY = listTop
        for (option in options.subList(variantScroll, variantScroll + rows)) {
            VariantOptionView.renderIcon(graphics, font, option, entry.id, panel.x + 6, rowY)
            ScrollingText.draw(graphics, font, VariantOptionView.label(option), panel.x + 28, panel.right - 8, rowY + (16 - font.lineHeight) / 2 + 1, palette.text)
            rowY += VARIANT_ROW_HEIGHT
        }

        // Scroll position, when the list is longer than the panel.
        if (options.size > rows) {
            val trackHeight = rows * VARIANT_ROW_HEIGHT
            val thumbHeight = max(6, trackHeight * rows / options.size)
            val thumbTop = listTop + (trackHeight - thumbHeight) * variantScroll / (options.size - rows)
            graphics.fill(panel.right - 4, listTop, panel.right - 2, listTop + trackHeight, palette.border)
            graphics.fill(panel.right - 4, thumbTop, panel.right - 2, thumbTop + thumbHeight, palette.accent)
        }
    }

    // endregion

    // region Geometry

    /**
     * The id is drawn smaller than the name. The factor keeps every font pixel on a whole number of
     * screen pixels (3/4 at GUI scale 4, 2/3 at 3), otherwise thin glyphs like ':' vanish; at GUI
     * scale 1 or 2 the text is already small and stays unscaled.
     */
    private fun idScale(): Float {
        val guiScale = Minecraft.getInstance().window.guiScale
        return if (guiScale >= 3) (guiScale - 1f) / guiScale else 1f
    }

    private fun windowRect(entry: AchievementUiEntry, screenWidth: Int, screenHeight: Int): UiRect =
        windowRect(dataSource.statistics(entry).size, dataSource.requirements(entry).size, screenWidth, screenHeight)

    private fun windowRect(statisticCount: Int, requirementCount: Int, screenWidth: Int, screenHeight: Int): UiRect {
        val width = min(360, screenWidth - 32)
        val header = max(MODEL_SIZE, 30 + LINE_HEIGHT * (statisticCount + 1))
        val height = min(screenHeight - 16, PADDING + header + 12 + ROW_HEIGHT * (max(1, requirementCount) + 1) + PADDING)
        return UiRect((screenWidth - width) / 2, (screenHeight - height) / 2, width, height)
    }

    private fun closeButtonRect(window: UiRect): UiRect = UiRect(window.right - 22, window.y + 8, 14, 14)

    // endregion

    private companion object {
        const val PADDING = 10
        const val MODEL_SIZE = 64
        const val ROW_HEIGHT = 14
        const val LINE_HEIGHT = 11
        const val TREE_BRANCH_LENGTH = 6
        const val VARIANT_ROWS = 8
        const val VARIANT_ROW_HEIGHT = 18
        const val VARIANT_PANEL_WIDTH = 300
        const val DIM = 0x88000000.toInt()
    }
}
