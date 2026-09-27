package ru.benos.everydeeds.tracking

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * A server-side observer that watches every online player each tick and keeps some state per player
 * ([S]), like a Godot node's `process()`: [process] runs once per server tick for each player.
 *
 * The state belongs to one world and one session: [clear] drops everything when a server starts or
 * stops (singleplayer reuses the same player UUID in every world), [forget] drops one player when they
 * join or leave. Spectators do not play, so they are skipped.
 */
abstract class PlayerTracker<S : Any> {
    private val states: MutableMap<UUID, S> = HashMap()

    /** Fresh state for a player seen for the first time since joining. */
    protected abstract fun newState(): S

    /** One server tick for one player; [tick] is the server's tick counter, for sampling intervals. */
    protected abstract fun process(player: ServerPlayer, state: S, tick: Int)

    /** The player's state, created on first use. */
    protected fun stateOf(player: ServerPlayer): S = states.getOrPut(player.uuid, ::newState)

    fun tick(server: MinecraftServer) {
        val tick = server.tickCount
        for (player in server.playerList.players) {
            if (player.isSpectator) continue
            process(player, stateOf(player), tick)
        }
    }

    fun forget(player: ServerPlayer) {
        states.remove(player.uuid)
    }

    fun clear() {
        states.clear()
    }
}

/** Every [PlayerTracker]; the platform drives them together. */
object PlayerTrackers {
    private val ALL: List<PlayerTracker<*>> = listOf(SightTracker, TravelTracker, StructureTracker, Celebration)

    fun tick(server: MinecraftServer) {
        ALL.forEach { tracker -> tracker.tick(server) }
    }

    /** A join or leave: the player's next session starts fresh (a login is a new entry into the current biome). */
    fun forget(player: ServerPlayer) {
        ALL.forEach { tracker -> tracker.forget(player) }
    }

    fun clear() {
        ALL.forEach { tracker -> tracker.clear() }
    }
}
