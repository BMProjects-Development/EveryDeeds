package ru.benos.everydeeds.client.gui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.EveryDeedsClient
import ru.benos.everydeeds.client.config.ClientConfigStore
import ru.benos.everydeeds.client.deed.SnapshotAchievementUiDataSource
import ru.benos.everydeeds.deed.DeedCategory
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The progress screen ("tablo"). It lays out and wires together its parts, like a Godot scene root:
 *
 * - [TabloSidebar]: categories, the "not started" switch, the difficulty slider;
 * - [TabloGrid]: the scrollable, virtualised grid of objects;
 * - [TabloFocusCard]: the enlarged card shown after hovering a cell;
 * - [TabloDetailsPopup]: the modal details window opened by clicking a cell;
 * - [TabloFooter]: the bar with the progress through the whole set.
 *
 * All data comes from an [AchievementUiDataSource]; by default the synced snapshot.
 */
class TabloScreen(
    private val dataSource: AchievementUiDataSource = SnapshotAchievementUiDataSource()
) : Screen("gui.$MOD_ID.tablo.title".translatable) {
    // Presentation settings are read once per opened screen.
    private val config = ClientConfigStore.current
    private val palette = config.colors

    // The parts need the screen's font, which is only available once the screen is set up.
    private val sidebar: TabloSidebar by lazy {
        TabloSidebar(font, palette, dataSource, config.showRemaining, ::selectCategory) { toggleRemaining() }
    }
    private val grid: TabloGrid by lazy {
        TabloGrid(font, palette, dataSource, config, rememberedCategory, rememberedScroll[rememberedCategory] ?: 0.0)
    }
    private val focusCard: TabloFocusCard by lazy { TabloFocusCard(font, palette, dataSource, config) }
    private val popup: TabloDetailsPopup by lazy { TabloDetailsPopup(font, palette, dataSource) }
    private val footer: TabloFooter by lazy { TabloFooter(font, palette, dataSource) }

    private var mouseX: Int = 0
    private var mouseY: Int = 0

    private var mainRect: UiRect = UiRect.ZERO
    private var sidebarRect: UiRect = UiRect.ZERO
    // Outer frame of the content panel (border included); the grid's viewport is strictly inside it.
    private var contentFrameRect: UiRect = UiRect.ZERO

    // region Inspection and control (for tests and tooling; the screen itself is driven by input)

    val category: DeedCategory get() = grid.category
    val scroll: Double get() = grid.scrollOffset
    val openedEntry: AchievementUiEntry? get() = popup.entry
    val hoveredVariantGoal: AchievementUiRequirement? get() = popup.hoveredRequirement

    /** The grid's viewport on screen. */
    fun contentArea(): UiRect {
        layout()
        return grid.area
    }

    fun categoryArea(category: DeedCategory): UiRect {
        layout()
        return sidebar.categoryRect(category)
    }

    /** The overall progress strip at the bottom. */
    fun footerArea(): UiRect {
        layout()
        return footer.area
    }

    /** Cells in the current category (all groups, whether in view or not). */
    fun entryCount(): Int = grid.entryCount()

    fun selectCategory(category: DeedCategory) {
        if (category == grid.category) return
        rememberedScroll[grid.category] = grid.scrollOffset
        grid.show(category, rememberedScroll[category] ?: 0.0)
    }

    fun scrollTo(offset: Double) {
        grid.scrollTo(offset)
    }

    fun toggleRemaining() {
        grid.showRemaining = !grid.showRemaining
        ClientConfigStore.update { current -> current.copy(showRemaining = grid.showRemaining) }
    }

    fun openDetails(entry: AchievementUiEntry) {
        popup.open(entry)
    }

    // endregion

    // region Lifecycle

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, tickDelta)
        this.mouseX = mouseX
        this.mouseY = mouseY
        layout()

        val interactive = !popup.isOpen
        grid.update(mouseX, mouseY, interactive)

        renderFrame(graphics)
        sidebar.render(graphics, grid.category, grid.showRemaining, mouseX, mouseY, interactive)
        grid.render(graphics, mouseX, mouseY, interactive)
        footer.render(graphics)
        grid.hovered?.let { hovered ->
            if (grid.hoverProgress() >= 1f) focusCard.render(graphics, hovered, grid.hoverOpenedAt, width, height)
        }
        popup.render(graphics, mouseX, mouseY, width, height)
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        grid.invalidate()
    }

    /** Reopening the screen returns to the same category and scroll position (for the game session). */
    override fun removed() {
        super.removed()
        rememberedCategory = grid.category
        rememberedScroll[grid.category] = grid.scrollOffset
    }

    // endregion

    // region Input

    override fun mouseMoved(x: Double, y: Double) {
        mouseX = x.roundToInt()
        mouseY = y.roundToInt()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // The popup is modal: the wheel scrolls its variant list, never the grid underneath.
        if (popup.isOpen) {
            popup.mouseScrolled(verticalAmount)
            return true
        }
        return grid.mouseScrolled(mouseX.roundToInt(), mouseY.roundToInt(), verticalAmount)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().roundToInt()
        val y = event.y().roundToInt()
        mouseX = x
        mouseY = y

        // 26.3 uses SDL button numbering: left is 1, not GLFW's 0. Always compare against the named constant.
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        layout()

        // The popup is modal: while it is open, every click belongs to it.
        if (popup.isOpen) {
            popup.mouseClicked(x, y, width, height)
            return true
        }
        return sidebar.mouseClicked(x, y) || grid.mouseClicked(x, y, popup::open)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        return grid.mouseDragged(event.y().roundToInt())
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        return grid.mouseReleased()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (popup.isOpen && event.isEscape) {
            popup.close()
            return true
        }
        if (!popup.isOpen && EveryDeedsClient.OPEN_TABLO_SCREEN_KEY.matches(event)) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    // endregion

    // region Layout and frame

    /** Places the panels for the current window size and hands the parts their areas. */
    private fun layout() {
        val panelWidth = max(320, width - PANEL_MARGIN * 2)
        val panelHeight = max(220, height - PANEL_MARGIN * 2)
        mainRect = UiRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)

        // Sidebar and content above, the overall progress strip along the whole bottom.
        val footerTop = mainRect.bottom - PANEL_GAP - TabloFooter.HEIGHT
        val sidebarWidth = (mainRect.width * 0.24).roundToInt().coerceIn(SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH)
        sidebarRect = UiRect(mainRect.x + PANEL_GAP, mainRect.y + PANEL_GAP, sidebarWidth, footerTop - PANEL_GAP - (mainRect.y + PANEL_GAP))
        contentFrameRect = UiRect(sidebarRect.right + PANEL_GAP, sidebarRect.y, mainRect.right - sidebarRect.right - PANEL_GAP * 2, sidebarRect.height)

        sidebar.area = sidebarRect
        grid.area = contentFrameRect.inset(1)
        footer.area = UiRect(sidebarRect.x, footerTop, contentFrameRect.right - sidebarRect.x, TabloFooter.HEIGHT)
    }

    private fun renderFrame(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, width, height, BACKDROP)
        graphics.fill(mainRect.x, mainRect.y, mainRect.right, mainRect.bottom, palette.panel)
        graphics.outline(mainRect.x, mainRect.y, mainRect.width, mainRect.height, palette.border)
        graphics.fill(sidebarRect.x, sidebarRect.y, sidebarRect.right, sidebarRect.bottom, SIDEBAR_BACKGROUND)
        graphics.fill(contentFrameRect.x, contentFrameRect.y, contentFrameRect.right, contentFrameRect.bottom, CONTENT_BACKGROUND)
        graphics.outline(contentFrameRect.x, contentFrameRect.y, contentFrameRect.width, contentFrameRect.height, palette.border)
    }

    // endregion

    companion object {
        // Where the screen was left, restored when it is opened again during the same game session.
        private var rememberedCategory: DeedCategory = DeedCategory.BLOCKS
        private val rememberedScroll: MutableMap<DeedCategory, Double> = EnumMap(DeedCategory::class.java)

        private const val PANEL_MARGIN = 32
        private const val PANEL_GAP = 6
        private const val SIDEBAR_MIN_WIDTH = 124
        private const val SIDEBAR_MAX_WIDTH = 172

        private const val BACKDROP = 0xC0101010.toInt()
        private const val SIDEBAR_BACKGROUND = 0xD024272B.toInt()
        private const val CONTENT_BACKGROUND = 0xE014171A.toInt()
    }
}
