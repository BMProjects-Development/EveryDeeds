package ru.benos.everydeeds.client.compat

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import ru.benos.everydeeds.client.config.EveryDeedsConfigScreen

/** Mod Menu's "configure" button. Only loaded when Mod Menu is installed (its own entrypoint). */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> EveryDeedsConfigScreen(parent) }
}
