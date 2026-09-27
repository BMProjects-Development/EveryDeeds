package ru.benos.everydeeds.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Util
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.config.DeedPalette
import ru.benos.everydeeds.client.config.EveryDeedsClientConfig
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.deed.DeedCategory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A cell under the cursor, with where it is drawn on screen. */
internal data class HoveredEntry(val entry: AchievementUiEntry, val rect: UiRect)

/**
 * Right panel of the progress screen: the objects of one category, grouped by mod, in a grid that fills
 * the available width, with sticky group headers, a draggable scrollbar and hover tracking.
 *
 * Virtualised: only the rows in view are drawn and hit-tested. Group offsets are prefix sums that are
 * rebuilt only when the content or the width changes; a visible group is found by binary search.
 */
internal class TabloGrid(
    private val font: Font,
    private val palette: DeedPalette,
    private val dataSource: AchievementUiDataSource,
    private val config: EveryDeedsClientConfig,
    category: DeedCategory,
    scrollOffset: Double
) {
    /** The scrollable viewport, strictly inside the panel border; set by the screen's layout. */
    var area: UiRect = UiRect.ZERO

    var category: DeedCategory = category
        private set
    var scrollOffset: Double = scrollOffset
        private set
    var showRemaining: Boolean = config.showRemaining

    private val cellSize: Int = config.cellSize
    private val cellStride: Int = cellSize + CELL_GAP
    private var maxScrollOffset: Int = 0

    private var model: TabloContentModel? = null
    private var layoutCache: ContentLayout? = null

    // Distance from the thumb top to the grab point while the scrollbar thumb is dragged; null when not dragging.
    private var scrollbarGrabOffset: Int? = null

    // Hover is resolved from layout arithmetic at the start of every frame, before anything is drawn.
    var hovered: HoveredEntry? = null
        private set
    private var hoverTarget: AchievementUiEntry? = null
    private var hoverStartedAt: Long = 0L

    val isDraggingScrollbar: Boolean get() = scrollbarGrabOffset != null

    /** When the hovered cell's delay ran out and its focus card opened (or opens). */
    val hoverOpenedAt: Long get() = hoverStartedAt + config.hoverDelayMs

    /** Switches the shown category at [scroll] (its remembered position); a no-op for the current one. */
    fun show(category: DeedCategory, scroll: Double) {
        if (category == this.category) return
        this.category = category
        scrollOffset = scroll
        layoutCache = null
    }

    /** Forgets the cached layout, e.g. after a resize. */
    fun invalidate() {
        layoutCache = null
    }

    fun scrollTo(offset: Double) {
        scrollOffset = offset.coerceIn(0.0, maxScrollOffset.toDouble())
    }

    /** Number of cells in the current content (all groups), for tests and tooling. */
    fun entryCount(): Int = currentModel().groups.sumOf { group -> group.entries.size }

    // region Frame

    /**
     * Brings the layout up to date and resolves the hovered cell for this frame. [interactive] is false
     * while a modal window is open or the scrollbar is dragged: nothing is hovered then.
     */
    fun update(mouseX: Int, mouseY: Int, interactive: Boolean) {
        val layout = refreshLayout()
        hovered = if (interactive && !isDraggingScrollbar) entryAt(mouseX, mouseY, layout) else null

        // The focus opens once the cursor has rested on the same cell for the configured delay.
        val entry = hovered?.entry
        if (entry !== hoverTarget) {
            hoverTarget = entry
            hoverStartedAt = Util.getMillis()
        }
    }

    /** 0 when nothing is hovered, 1 once the hover delay has passed. */
    fun hoverProgress(): Float {
        if (hoverTarget == null) return 0f
        if (config.hoverDelayMs <= 0) return 1f
        return ((Util.getMillis() - hoverStartedAt) / config.hoverDelayMs.toFloat()).coerceIn(0f, 1f)
    }

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, interactive: Boolean) {
        val layout = refreshLayout()
        renderContent(graphics, layout)
        renderScrollbar(graphics, layout, interactive && scrollbarHitRect().contains(mouseX, mouseY))
    }

    // endregion

    // region Input

    fun mouseScrolled(x: Int, y: Int, amount: Double): Boolean {
        if (!area.contains(x, y)) return false
        scrollTo(scrollOffset - amount * SCROLL_SPEED)
        return true
    }

    /** A left click: grabs the scrollbar (true) or returns the clicked cell's entry through [onEntryClicked]. */
    fun mouseClicked(x: Int, y: Int, onEntryClicked: (AchievementUiEntry) -> Unit): Boolean {
        val layout = refreshLayout()
        if (scrollbarHitRect().contains(x, y) && maxScrollOffset > 0) {
            val thumb = scrollbarThumbRect(layout)
            scrollbarGrabOffset = if (thumb.contains(x, y)) y - thumb.y else thumb.height / 2
            dragScrollbarTo(y, layout)
            return true
        }

        val hit = entryAt(x, y, layout) ?: return false
        hovered = null
        onEntryClicked(hit.entry)
        return true
    }

    fun mouseDragged(y: Int): Boolean {
        if (scrollbarGrabOffset == null) return false
        dragScrollbarTo(y, refreshLayout())
        return true
    }

    fun mouseReleased(): Boolean {
        if (scrollbarGrabOffset == null) return false
        scrollbarGrabOffset = null
        return true
    }

    // endregion

    // region Layout

    /** Recomputes the (cached) content layout for the current area, then clamps the scroll offset. */
    private fun refreshLayout(): ContentLayout {
        val layout = contentLayout()
        maxScrollOffset = max(0, layout.totalHeight - area.height)
        scrollOffset = scrollOffset.coerceIn(0.0, maxScrollOffset.toDouble())
        return layout
    }

    /** Width available to the grid. The scrollbar gutter is always reserved so columns never jitter. */
    private fun gridAreaWidth(): Int = max(cellSize, area.width - CONTENT_PADDING * 2 - SCROLLBAR_RESERVE)

    /** Group headers span the whole viewport, up to the scrollbar gutter. */
    private fun headerWidth(): Int = area.width - SCROLLBAR_RESERVE

    private fun currentModel(): TabloContentModel {
        val cached = model
        if (cached != null && !cached.isStale(dataSource, category, showRemaining)) return cached
        return TabloContentModel.build(dataSource, category, showRemaining).also { built -> model = built }
    }

    /** Builds prefix-summed group offsets. Only rebuilt when the content model or the panel width changes. */
    private fun contentLayout(): ContentLayout {
        val model = currentModel()
        val cache = layoutCache
        if (cache != null && cache.model === model && cache.width == area.width) return cache

        val columns = max(1, floor((gridAreaWidth() + CELL_GAP).toDouble() / cellStride.toDouble()).toInt())
        val layouts = ArrayList<GroupLayout>(model.groups.size)
        var top = 0
        model.groups.forEach { group ->
            val rows = ceil(group.entries.size / columns.toDouble()).toInt()
            val gridHeight = if (rows == 0) 0 else rows * cellSize + (rows - 1) * CELL_GAP
            val height = GRID_OFFSET + gridHeight + CONTENT_PADDING
            layouts += GroupLayout(group, top, height, rows)
            top += height
        }

        return ContentLayout(model, area.width, columns, top, layouts).also { built -> layoutCache = built }
    }

    /** Groups intersecting [viewportTop, viewportBottom) in content space, found by binary search over group tops. */
    private fun visibleGroups(layout: ContentLayout, viewportTop: Int, viewportBottom: Int): List<GroupLayout> {
        val groupLayouts = layout.groupLayouts
        if (groupLayouts.isEmpty()) return emptyList()

        var low = 0
        var high = groupLayouts.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (groupLayouts[middle].bottom <= viewportTop) low = middle + 1 else high = middle
        }

        val visible = ArrayList<GroupLayout>(2)
        var index = low
        while (index < groupLayouts.size && groupLayouts[index].top < viewportBottom) {
            visible += groupLayouts[index]
            index++
        }
        return visible
    }

    private fun gridX(layout: ContentLayout): Int {
        val gridWidth = layout.columns * cellSize + (layout.columns - 1) * CELL_GAP
        return area.x + CONTENT_PADDING + max(0, (gridAreaWidth() - gridWidth) / 2)
    }

    /**
     * Screen-space Y of a group's header: at its natural position, pinned to the top of the viewport while
     * its group scrolls past, and pushed up by the end of its own group. Null when fully scrolled out.
     */
    private fun stickyHeaderY(groupLayout: GroupLayout): Int? {
        val rawY = area.y + groupLayout.top - scrollOffset.toInt()
        val stickyMaxY = area.y + groupLayout.bottom - scrollOffset.toInt() - GROUP_HEADER_HEIGHT
        if (stickyMaxY + GROUP_HEADER_HEIGHT <= area.y) return null

        return min(max(rawY, area.y), stickyMaxY)
    }

    /**
     * Pure hit-test shared by hover and click: maps a screen point to the cell under it using the same
     * arithmetic as rendering. Points covered by a (sticky) group header or outside a cell return null.
     */
    private fun entryAt(x: Int, y: Int, layout: ContentLayout): HoveredEntry? {
        val gridLeft = area.x + CONTENT_PADDING
        if (x < gridLeft || x >= gridLeft + gridAreaWidth()) return null
        if (!area.contains(x, y)) return null

        val viewportTop = scrollOffset.toInt()
        val contentY = y - area.y + viewportTop
        val groupLayout = visibleGroups(layout, contentY, contentY + 1).firstOrNull() ?: return null

        // A header covers its own height and the gap below it (rows scrolling up are cut off there).
        for (visible in visibleGroups(layout, viewportTop, viewportTop + area.height)) {
            val headerY = stickyHeaderY(visible) ?: continue
            if (y >= headerY && y < headerY + GRID_OFFSET) return null
        }

        val localY = contentY - groupLayout.top - GRID_OFFSET
        if (localY < 0 || localY % cellStride >= cellSize) return null
        val row = localY / cellStride
        if (row >= groupLayout.rows) return null

        val localX = x - gridX(layout)
        if (localX < 0 || localX % cellStride >= cellSize) return null
        val column = localX / cellStride
        if (column >= layout.columns) return null

        val entry = groupLayout.group.entries.getOrNull(row * layout.columns + column) ?: return null
        val rect = UiRect(
            gridX(layout) + column * cellStride,
            area.y + groupLayout.top + GRID_OFFSET + row * cellStride - viewportTop,
            cellSize,
            cellSize
        )
        return HoveredEntry(entry, rect)
    }

    // endregion

    // region Rendering

    private fun renderContent(graphics: GuiGraphicsExtractor, layout: ContentLayout) {
        graphics.enableScissor(area.x, area.y, area.right, area.bottom)

        val viewportTop = scrollOffset.toInt()
        val viewportBottom = viewportTop + area.height
        val visibleGroups = visibleGroups(layout, viewportTop, viewportBottom)
        if (layout.groupLayouts.isEmpty()) renderEmptyState(graphics, layout)

        // Cells first, then headers on top. Rows scrolling up under a pinned header are cut off at the gap
        // below it, so they never run into the header.
        visibleGroups.forEach { groupLayout ->
            val clipTop = stickyHeaderY(groupLayout)?.let { headerY -> max(area.y, headerY + GRID_OFFSET) } ?: area.y
            graphics.enableScissor(area.x, clipTop, area.right, area.bottom)
            renderVisibleCells(graphics, layout, groupLayout, viewportTop, viewportBottom)
            graphics.disableScissor()
        }
        visibleGroups.forEach { groupLayout -> renderGroupHeader(graphics, groupLayout) }

        graphics.disableScissor()
    }

    private fun renderEmptyState(graphics: GuiGraphicsExtractor, layout: ContentLayout) {
        val message = when {
            !ClientDeedSnapshot.isSynced -> "gui.$MOD_ID.waiting".translatable
            layout.model.remainingCount > 0 -> "gui.$MOD_ID.nothing_explored".translatable
            else -> "gui.$MOD_ID.empty".translatable
        }
        // Wrapped to the panel: some languages say it in a much longer sentence than others.
        val lines = font.split(message, area.width - CONTENT_PADDING * 2)
        val top = area.centerY - lines.size * font.lineHeight / 2
        lines.forEachIndexed { index, line ->
            graphics.centeredText(font, line, area.centerX, top + index * font.lineHeight, palette.textMuted)
        }
    }

    private fun renderGroupHeader(graphics: GuiGraphicsExtractor, groupLayout: GroupLayout) {
        val y = stickyHeaderY(groupLayout) ?: return
        val x = area.x
        val width = headerWidth()

        graphics.fill(x, y, x + width, y + GROUP_HEADER_HEIGHT, HEADER_BACKGROUND)
        graphics.fill(x, y + GROUP_HEADER_HEIGHT - 1, x + width, y + GROUP_HEADER_HEIGHT, TabloStyle.HEADER_LINE)
        graphics.text(font, groupLayout.group.title, x + CONTENT_PADDING + 8, y + 8, palette.text)

        val group = groupLayout.group
        val countText = "${group.completed}/${group.total}"
        val countColor = if (group.total > 0 && group.completed >= group.total) palette.complete else palette.textMuted
        graphics.text(font, countText, x + width - font.width(countText) - CONTENT_PADDING - 8, y + 8, countColor)
    }

    private fun renderVisibleCells(
        graphics: GuiGraphicsExtractor,
        layout: ContentLayout,
        groupLayout: GroupLayout,
        viewportTop: Int,
        viewportBottom: Int
    ) {
        if (groupLayout.rows == 0) return

        val gridTop = groupLayout.top + GRID_OFFSET
        val firstRow = max(0, (viewportTop - gridTop) / cellStride)
        val lastRow = min(groupLayout.rows - 1, (viewportBottom - gridTop) / cellStride)
        if (lastRow < firstRow) return

        val gridX = gridX(layout)
        val entries = groupLayout.group.entries
        val hoveredEntry = hovered?.entry

        for (row in firstRow..lastRow) {
            val y = area.y + gridTop - viewportTop + row * cellStride
            for (column in 0 until layout.columns) {
                val entry = entries.getOrNull(row * layout.columns + column) ?: break
                val rect = UiRect(gridX + column * cellStride, y, cellSize, cellSize)
                renderCell(graphics, entry, layout.model.stateOf(entry), rect, hoveredEntry === entry)
            }
        }
    }

    private fun renderCell(graphics: GuiGraphicsExtractor, entry: AchievementUiEntry, state: EntryState, rect: UiRect, hovered: Boolean) {
        val stateColor = when (state) {
            EntryState.COMPLETE -> palette.complete
            EntryState.IN_PROGRESS -> palette.inProgress
            EntryState.NOT_STARTED -> palette.remaining
        }

        graphics.fill(rect.x, rect.y, rect.right, rect.bottom, palette.slot)
        graphics.fill(rect.x + 3, rect.y + 3, rect.right - 3, rect.bottom - 3, if (hovered) TabloStyle.SLOT_INNER_HOVERED else palette.slotInner)
        if (state == EntryState.NOT_STARTED) {
            graphics.fill(rect.x + 3, rect.y + 3, rect.right - 3, rect.bottom - 3, TabloStyle.remainingWash(palette.remaining))
        }
        graphics.outline(rect.x, rect.y, rect.width, rect.height, if (hovered) palette.accent else stateColor)

        // While the cursor rests on the cell, it fills up from the bottom until the focus card opens.
        if (hovered) {
            val progress = hoverProgress()
            if (progress < 1f) {
                val innerHeight = rect.height - 6
                val filledTop = rect.bottom - 3 - (innerHeight * progress).roundToInt()
                graphics.fill(rect.x + 3, filledTop, rect.right - 3, rect.bottom - 3, HOVER_FILL)
            }
        }

        renderEntryPreview(graphics, entry, rect)
        TabloStyle.renderCompletionBar(graphics, palette, dataSource.completion(entry), rect)
    }

    /**
     * Preview slot of a cell: a flat, inventory-style icon (the live 3D model is reserved for the
     * focus card and the details window) and, when the cell is large enough, the name.
     */
    private fun renderEntryPreview(graphics: GuiGraphicsExtractor, entry: AchievementUiEntry, rect: UiRect) {
        val labelled = cellSize >= LABELLED_CELL_MIN_SIZE
        val iconInset = cellSize / 4
        val iconTop = rect.y + if (labelled) cellSize / 8 else iconInset
        val iconBottom = if (labelled) rect.y + cellSize * 5 / 8 else rect.bottom - iconInset
        DeedPreviewRenderer.renderIcon(graphics, entry.preview, rect.x + iconInset, iconTop, rect.right - iconInset, iconBottom)

        if (labelled) {
            ScrollingText.draw(graphics, font, entry.name, rect.x + 4, rect.right - 4, rect.bottom - 16, palette.text, centered = true)
        }
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, layout: ContentLayout, hovered: Boolean) {
        if (maxScrollOffset <= 0) return

        val track = scrollbarTrackRect()
        val thumb = scrollbarThumbRect(layout)
        val active = isDraggingScrollbar || hovered
        graphics.fill(track.x, track.y, track.right, track.bottom, SCROLLBAR_TRACK)
        graphics.fill(thumb.x, thumb.y, thumb.right, thumb.bottom, if (active) TabloStyle.brighter(palette.accent) else palette.accent)
    }

    // endregion

    // region Scrollbar

    private fun scrollbarTrackRect(): UiRect =
        UiRect(area.right - SCROLLBAR_RESERVE / 2 - SCROLLBAR_WIDTH / 2, area.y + 4, SCROLLBAR_WIDTH, area.height - 8)

    /** Whole scrollbar gutter; wider than the visible track so the thin bar is easy to grab. */
    private fun scrollbarHitRect(): UiRect =
        UiRect(area.right - SCROLLBAR_RESERVE, area.y, SCROLLBAR_RESERVE, area.height)

    private fun scrollbarThumbRect(layout: ContentLayout): UiRect {
        val track = scrollbarTrackRect()
        val visibleFraction = area.height.toDouble() / max(1, layout.totalHeight).toDouble()
        val thumbHeight = (track.height * visibleFraction).roundToInt().coerceIn(SCROLLBAR_MIN_THUMB, track.height)
        val thumbTravel = track.height - thumbHeight
        val progress = if (maxScrollOffset > 0) scrollOffset / maxScrollOffset.toDouble() else 0.0
        return UiRect(track.x, track.y + (thumbTravel * progress).roundToInt(), track.width, thumbHeight)
    }

    private fun dragScrollbarTo(mouseY: Int, layout: ContentLayout) {
        val grabOffset = scrollbarGrabOffset ?: return
        val track = scrollbarTrackRect()
        val thumbTravel = track.height - scrollbarThumbRect(layout).height
        if (thumbTravel <= 0) return

        val progress = (mouseY - grabOffset - track.y).toDouble() / thumbTravel.toDouble()
        scrollTo(progress * maxScrollOffset)
    }

    // endregion

    private class ContentLayout(
        val model: TabloContentModel,
        val width: Int,
        val columns: Int,
        val totalHeight: Int,
        val groupLayouts: List<GroupLayout>
    )

    private class GroupLayout(
        val group: TabloContentModel.Group,
        val top: Int,
        val height: Int,
        val rows: Int
    ) {
        val bottom: Int get() = top + height
    }

    private companion object {
        const val CONTENT_PADDING = 10
        const val GROUP_HEADER_HEIGHT = 24

        /** Space between a group's header and its first row of cells. */
        const val HEADER_GAP = 6

        /** Where a group's first row starts, below its header and the gap. */
        const val GRID_OFFSET = GROUP_HEADER_HEIGHT + HEADER_GAP
        const val CELL_GAP = 6
        const val LABELLED_CELL_MIN_SIZE = 48
        const val SCROLL_SPEED = 34.0
        const val SCROLLBAR_RESERVE = 12
        const val SCROLLBAR_WIDTH = 4
        const val SCROLLBAR_MIN_THUMB = 24

        const val HOVER_FILL = 0x40FFFFFF
        const val HEADER_BACKGROUND = 0xFF20252A.toInt()
        const val SCROLLBAR_TRACK = 0x662A2E33
    }
}
