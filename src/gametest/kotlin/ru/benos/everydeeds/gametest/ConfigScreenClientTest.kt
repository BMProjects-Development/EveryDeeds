package ru.benos.everydeeds.gametest

import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen
import ru.benos.everydeeds.client.config.EveryDeedsConfigScreen

/** The settings screen opens (directly and through Mod Menu's entrypoint) and renders. */
class ConfigScreenClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val fromModMenu = context.computeOnClient<List<Any>, RuntimeException> { _ ->
            FabricLoader.getInstance().getEntrypointContainers("modmenu", ModMenuApi::class.java)
                .filter { container -> container.provider.metadata.id == "everydeeds" }
                .map { container -> container.entrypoint.modConfigScreenFactory.create(TitleScreen()) }
        }
        check(fromModMenu.singleOrNull() is EveryDeedsConfigScreen) { "Mod Menu does not offer the EveryDeeds settings screen: $fromModMenu" }

        context.setScreen { EveryDeedsConfigScreen(TitleScreen()) }
        context.waitTicks(5)
        context.takeScreenshot("config_screen")
        context.setScreen { TitleScreen() }
    }
}
