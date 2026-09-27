package ru.benos.everydeeds.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import ru.benos.everydeeds.client.EveryDeedsClient
import ru.benos.everydeeds.client.gui.TabloScreen
import ru.benos.everydeeds.deed.DeedCategory

class TabloScreenClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { singleplayer ->
            singleplayer.connection.waitForChunksRender()

            // Mirror the reported setup: 1920x1009 window, GUI scale 3, screen opened through the keybind.
            context.input.resizeWindow(1920, 1009)
            context.runOnClient<RuntimeException> { client -> client.options.guiScale().set(3); client.resizeGui() }
            context.waitTicks(2)
            context.input.pressKey(EveryDeedsClient.OPEN_TABLO_SCREEN_KEY)
            context.waitForScreen(TabloScreen::class.java)
            context.waitTicks(5)
            context.takeScreenshot("tablo_00_real_data")

            // The rest exercises layout, hit-testing and virtualization on a large synthetic data set.
            context.setScreen { TabloScreen(StressAchievementUiDataSource()) }
            context.waitTicks(5)
            context.takeScreenshot("tablo_01_initial")

            // Category switching.
            val itemsButton = context.onClient { client -> tablo(client).categoryArea(DeedCategory.ITEMS) }
            clickAt(context, Pair(itemsButton.centerX, itemsButton.centerY))
            context.waitTicks(3)
            val category = context.onClient { client -> tablo(client).category }
            check(category == DeedCategory.ITEMS) { "Expected ITEMS after clicking the sidebar, got $category" }
            context.takeScreenshot("tablo_02_items")

            // Scrollbar drag.
            val scale = guiScale(context)
            val content = context.onClient { client -> tablo(client).contentArea() }
            val gutterX = (content.x + content.width - 6) * scale
            val startY = (content.y + 10) * scale
            context.input.setCursorPos(gutterX, startY)
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(2)
            context.input.setCursorPos(gutterX, startY + 80 * scale)
            context.waitTicks(2)
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(2)
            val scroll = context.onClient { client -> tablo(client).scroll }
            check(scroll > 0.0) { "Expected the grid to scroll after dragging the scrollbar, scrollOffset=$scroll" }
            context.takeScreenshot("tablo_03_dragged")

            // Cell click opens the popup; clicking outside closes it.
            clickAt(context, Pair(content.x + 40, content.y + 70))
            context.waitTicks(3)
            context.takeScreenshot("tablo_04_popup")
            val opened = context.onClient { client -> tablo(client).openedEntry }
            check(opened != null) { "Expected a popup after clicking a cell" }

            clickAt(context, Pair(4, 4))
            context.waitTicks(3)
            val closed = context.onClient { client -> tablo(client).openedEntry }
            check(closed == null) { "Expected the popup to close on an outside click" }

            context.setScreen { null }
        }
    }

    private fun guiScale(context: ClientGameTestContext): Double =
        context.onClient { client -> client.window.guiScale.toDouble() }

    private fun clickAt(context: ClientGameTestContext, guiPoint: Pair<Int, Int>) {
        val scale = guiScale(context)
        context.input.setCursorPos(guiPoint.first * scale + scale / 2, guiPoint.second * scale + scale / 2)
        context.waitTick()
        context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
    }

    private fun tablo(client: Minecraft): TabloScreen = client.gui.screen() as TabloScreen

    private fun <T> ClientGameTestContext.onClient(block: (Minecraft) -> T): T =
        computeOnClient<T, RuntimeException> { client -> block(client) }
}
