package ru.benos.everydeeds.platform.fabric

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.resource.v1.DataResourceLoader
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.command.DeedCommands
import ru.benos.everydeeds.config.ServerConfigStore
import ru.benos.everydeeds.data.DeedDefinitionLoader
import ru.benos.everydeeds.data.DeedPacks
import ru.benos.everydeeds.network.ChangeDeedSetPayload
import ru.benos.everydeeds.network.DeedProgressPayload
import ru.benos.everydeeds.network.DeedSyncPayload
import ru.benos.everydeeds.network.DeedUnlockedPayload
import ru.benos.everydeeds.network.DeedVariantsPayload
import ru.benos.everydeeds.network.RequestDeedSyncPayload
import ru.benos.everydeeds.network.RequestDeedVariantsPayload
import ru.benos.everydeeds.platform.DeedPlatform
import ru.benos.everydeeds.tracking.DeedHooks
import ru.benos.everydeeds.tracking.DeedSync
import ru.benos.everydeeds.tracking.DeedTracker
import ru.benos.everydeeds.tracking.PlayerDeedData
import ru.benos.everydeeds.tracking.PlayerTrackers
import ru.benos.everydeeds.tracking.WorldDeedSettings

/**
 * Fabric implementation of [DeedPlatform] plus the Fabric-side event wiring. Actions without a
 * Fabric event (placing, picking up, crafting, trading, looting...) come from the mixins in
 * `ru.benos.everydeeds.mixins`; everything ends up in [DeedTracker.record] via [DeedHooks].
 */
object FabricDeedPlatform : DeedPlatform {
    private val PLAYER_DATA: AttachmentType<PlayerDeedData> = AttachmentRegistry.create(id("player_deeds")) { builder ->
        builder.persistent(PlayerDeedData.CODEC)
            .copyOnDeath()
            .initializer { PlayerDeedData() }
    }

    private val WORLD_SETTINGS: AttachmentType<WorldDeedSettings> = AttachmentRegistry.create(id("world_settings")) { builder ->
        builder.persistent(WorldDeedSettings.CODEC)
            .initializer { WorldDeedSettings.DEFAULT }
    }

    override fun playerData(player: ServerPlayer): PlayerDeedData =
        player.getAttachedOrCreate(PLAYER_DATA)

    override fun worldSettings(server: MinecraftServer): WorldDeedSettings =
        server.overworld().getAttachedOrCreate(WORLD_SETTINGS)

    override fun setWorldSettings(server: MinecraftServer, settings: WorldDeedSettings) {
        server.overworld().setAttached(WORLD_SETTINGS, settings)
    }

    override fun canSend(player: ServerPlayer, type: CustomPacketPayload.Type<*>): Boolean =
        ServerPlayNetworking.canSend(player, type)

    override fun send(player: ServerPlayer, payload: CustomPacketPayload) {
        ServerPlayNetworking.send(player, payload)
    }

    fun initialize() {
        DeedPlatform.install(this)
        registerNetworking()
        registerResources()
        registerEvents()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> DeedCommands.register(dispatcher) }
    }

    private fun registerNetworking() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(DeedSyncPayload.TYPE, DeedSyncPayload.CODEC, DeedSyncPayload.MAX_SIZE)
        PayloadTypeRegistry.clientboundPlay().register(DeedProgressPayload.TYPE, DeedProgressPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(DeedUnlockedPayload.TYPE, DeedUnlockedPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RequestDeedSyncPayload.TYPE, RequestDeedSyncPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ChangeDeedSetPayload.TYPE, ChangeDeedSetPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RequestDeedVariantsPayload.TYPE, RequestDeedVariantsPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(DeedVariantsPayload.TYPE, DeedVariantsPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestDeedVariantsPayload.TYPE) { payload, context ->
            val player = context.player()
            val keys = DeedSync.collectedVariants(player, payload.instanceIndex) ?: return@registerGlobalReceiver
            if (canSend(player, DeedVariantsPayload.TYPE)) send(player, DeedVariantsPayload(payload.instanceIndex, keys))
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestDeedSyncPayload.TYPE) { _, context ->
            DeedSync.sendFullSync(context.player())
        }
        ServerPlayNetworking.registerGlobalReceiver(ChangeDeedSetPayload.TYPE) { payload, context ->
            val player = context.player()
            if (!DeedTracker.canChangeSet(player)) {
                EveryDeeds.LOGGER.warn("{} tried to switch the deed set without permission", player.name.string)
                return@registerGlobalReceiver
            }
            DeedTracker.switchSet(player.level().server, payload.set)
        }
    }

    private fun registerResources() {
        DataResourceLoader.get().registerReloadListener(DeedDefinitionLoader.LISTENER_ID) { registries ->
            DeedDefinitionLoader(registries)
        }

        // Optional content ships inside the jar as built-in data packs: on by default, turned off per world like any data pack.
        val mod = FabricLoader.getInstance().getModContainer(EveryDeeds.MOD_ID).orElseThrow()
        ResourceLoader.registerBuiltinPack(
            DeedPacks.MORE_MILESTONES, mod, "pack.${EveryDeeds.MOD_ID}.more_milestones".translatable, PackActivationType.DEFAULT_ENABLED
        )
    }

    private fun registerEvents() {
        // Index only once tags and recipes of the (re)loaded datapacks are in place.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ServerConfigStore.load()
            PlayerTrackers.clear()
            DeedTracker.rebuild(server)
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, success -> if (success) DeedTracker.rebuild(server) }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            DeedTracker.clear()
            PlayerTrackers.clear()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            PlayerTrackers.forget(handler.player)
            DeedHooks.onPlayerJoined(handler.player)
            DeedTracker.reconcile(handler.player)
            DeedSync.sendFullSync(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            DeedSync.forget(handler.player)
            PlayerTrackers.forget(handler.player)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            PlayerTrackers.tick(server)
            DeedSync.tick(server)
        }

        PlayerBlockBreakEvents.AFTER.register { _, player, _, state, _ -> DeedHooks.onBlockBroken(player, state) }

        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source -> DeedHooks.onEntityKilled(source.entity, entity) }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, _ ->
            if (damageTaken > 0f) DeedHooks.onPlayerDamaged(entity, source)
        }
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, path)
}
