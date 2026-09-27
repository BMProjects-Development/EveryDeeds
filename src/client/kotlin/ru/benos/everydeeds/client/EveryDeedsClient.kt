package ru.benos.everydeeds.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import org.slf4j.Logger
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.EveryDeeds.ident
import ru.benos.everydeeds.client.config.ClientConfigStore
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.client.deed.UnlockAnnouncer
import ru.benos.everydeeds.client.gui.DeedPreviewRenderer
import ru.benos.everydeeds.client.gui.TabloScreen
import ru.benos.everydeeds.client.gui.preview.BlockPreviewRenderer
import ru.benos.everydeeds.network.DeedProgressPayload
import ru.benos.everydeeds.network.DeedSyncPayload
import ru.benos.everydeeds.network.DeedUnlockedPayload
import ru.benos.everydeeds.network.DeedVariantsPayload
import ru.benos.everydeeds.network.RequestDeedSyncPayload

object EveryDeedsClient : ClientModInitializer {
    val LOGGER: Logger by lazy { EveryDeeds.LOGGER }

    val KEYBIND_CATEGORY: KeyMapping.Category = KeyMapping.Category("${EveryDeeds.MOD_ID}:keybinds_category".ident)
    val OPEN_TABLO_SCREEN_KEY: KeyMapping = KeyMapping("key.${EveryDeeds.MOD_ID}.open_tablo_screen", InputConstants.KEY_G, this.KEYBIND_CATEGORY)

    override fun onInitializeClient() {
        ClientConfigStore.load()
        registerNetworking()
        registerKeybinds()
        PictureInPictureRendererRegistry.register { BlockPreviewRenderer() }
        registerResourceReload()
    }

    /**
     * Cached previews hold baked models and render states from the previous resources; after a reload
     * (language, resource packs, F3+T) they are rebuilt lazily from the new ones.
     */
    private fun registerResourceReload() {
        val listenerId = "preview_cache".ident
        val loader = ResourceLoader.get(PackType.CLIENT_RESOURCES)
        loader.registerReloadListener(listenerId, ResourceManagerReloadListener { _ -> DeedPreviewRenderer.clear() })
        loader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, listenerId)
        loader.addListenerOrdering(ResourceReloaderKeys.Client.ENTITY_RENDER_DISPATCHER, listenerId)
    }

    private fun registerKeybinds() {
        KeyMappingHelper.registerKeyMapping(this.OPEN_TABLO_SCREEN_KEY)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (this.OPEN_TABLO_SCREEN_KEY.consumeClick()) {
                if (client.gui.screen() == null) openTabloScreen(client)
            }
        }
    }

    private fun openTabloScreen(client: Minecraft) {
        if (!ClientDeedSnapshot.isSynced && ClientPlayNetworking.canSend(RequestDeedSyncPayload.TYPE)) {
            ClientPlayNetworking.send(RequestDeedSyncPayload)
        }
        client.gui.setScreen(TabloScreen())
    }

    private fun registerNetworking() {
        ClientPlayNetworking.registerGlobalReceiver(DeedSyncPayload.TYPE) { payload, _ ->
            ClientDeedSnapshot.applySync(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(DeedProgressPayload.TYPE) { payload, _ ->
            ClientDeedSnapshot.applyUpdate(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(DeedVariantsPayload.TYPE) { payload, _ ->
            ClientDeedSnapshot.applyVariants(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(DeedUnlockedPayload.TYPE) { payload, context ->
            UnlockAnnouncer.announce(context.client(), payload.instanceIndices)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientDeedSnapshot.clear()
            DeedPreviewRenderer.clear()
        }
    }
}
