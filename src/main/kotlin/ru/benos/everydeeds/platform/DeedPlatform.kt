package ru.benos.everydeeds.platform

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.benos.everydeeds.tracking.PlayerDeedData
import ru.benos.everydeeds.tracking.WorldDeedSettings

/**
 * The few loader-specific services the tracking core needs. Event sources, persistence and
 * networking are wired per loader; everything above this seam is shared business logic.
 */
interface DeedPlatform {
    fun playerData(player: ServerPlayer): PlayerDeedData

    fun worldSettings(server: MinecraftServer): WorldDeedSettings

    fun setWorldSettings(server: MinecraftServer, settings: WorldDeedSettings)

    fun canSend(player: ServerPlayer, type: CustomPacketPayload.Type<*>): Boolean

    fun send(player: ServerPlayer, payload: CustomPacketPayload)

    companion object {
        @Volatile
        private var instance: DeedPlatform? = null

        val current: DeedPlatform
            get() = instance ?: error("EveryDeeds platform services are not initialized")

        fun install(platform: DeedPlatform) {
            instance = platform
        }
    }
}
