package ru.benos.everydeeds

import net.fabricmc.api.ModInitializer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.benos.everydeeds.platform.fabric.FabricDeedPlatform

object EveryDeeds : ModInitializer {
    const val MOD_ID: String = "everydeeds"

    val LOGGER: Logger = LoggerFactory.getLogger(this.MOD_ID)

    override fun onInitialize() {
        FabricDeedPlatform.initialize()
    }

    // Utils //

    val String.ident: Identifier
        get() = Identifier.parse(this@ident)

    val String.literal: MutableComponent
        get() = Component.literal(this@literal)
    val String.translatable: MutableComponent
        get() = Component.translatable(this@translatable)

    fun String.translatable(vararg args: Any): MutableComponent =
        Component.translatable(this, *args)
}
