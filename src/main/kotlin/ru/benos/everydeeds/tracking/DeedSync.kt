package ru.benos.everydeeds.tracking

import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.benos.everydeeds.data.DeedDefinitions
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.LedgerKey
import ru.benos.everydeeds.network.DeedInstanceView
import ru.benos.everydeeds.network.DeedProgressPayload
import ru.benos.everydeeds.network.DeedProgressView
import ru.benos.everydeeds.network.DeedSyncPayload
import ru.benos.everydeeds.network.DeedTemplateView
import ru.benos.everydeeds.network.DeedUnlockedPayload
import ru.benos.everydeeds.network.LedgerView
import ru.benos.everydeeds.platform.DeedPlatform
import java.util.UUID

/**
 * Server -> client synchronisation. Frequent events (mining a tunnel, walking past mobs) only mark
 * state dirty; [flush] sends one batched update per player every [FLUSH_INTERVAL_TICKS].
 */
object DeedSync {
    const val FLUSH_INTERVAL_TICKS: Int = 10

    private class Pending {
        val progress = IntLinkedOpenHashSet()
        val unlocked = IntLinkedOpenHashSet()
        val ledger = LinkedHashSet<LedgerKey>()

        fun isEmpty(): Boolean = progress.isEmpty() && unlocked.isEmpty() && ledger.isEmpty()
    }

    private val pending: MutableMap<UUID, Pending> = HashMap()
    private var ticksSinceFlush = 0

    fun markProgressDirty(player: ServerPlayer, instanceIndex: Int) {
        pendingFor(player).progress.add(instanceIndex)
    }

    fun markUnlocked(player: ServerPlayer, instanceIndex: Int) {
        pendingFor(player).unlocked.add(instanceIndex)
    }

    fun markLedgerDirty(player: ServerPlayer, key: LedgerKey) {
        pendingFor(player).ledger.add(key)
    }

    fun forget(player: ServerPlayer) {
        pending.remove(player.uuid)
    }

    fun tick(server: MinecraftServer) {
        if (++ticksSinceFlush < FLUSH_INTERVAL_TICKS) return
        ticksSinceFlush = 0
        flush(server)
    }

    fun flush(server: MinecraftServer) {
        if (pending.isEmpty()) return

        val index = DeedTracker.index
        for (player in server.playerList.players) {
            val state = pending.remove(player.uuid) ?: continue
            if (state.isEmpty()) continue
            val data = DeedPlatform.current.playerData(player)

            val progress = state.progress.mapNotNull { instanceIndex ->
                val instance = index.instances.getOrNull(instanceIndex) ?: return@mapNotNull null
                val entry = data.progress[instance.key] ?: return@mapNotNull null
                DeedProgressView(instanceIndex, DeedTracker.progressValue(instance, entry), entry.completed)
            }
            val ledger = state.ledger.map { key -> LedgerView(key, data.ledgerCount(key)) }

            if ((progress.isNotEmpty() || ledger.isNotEmpty()) && DeedPlatform.current.canSend(player, DeedProgressPayload.TYPE)) {
                DeedPlatform.current.send(player, DeedProgressPayload(progress, ledger))
            }
            if (state.unlocked.isNotEmpty() && DeedPlatform.current.canSend(player, DeedUnlockedPayload.TYPE)) {
                DeedPlatform.current.send(player, DeedUnlockedPayload(state.unlocked.toList()))
            }
        }
        pending.clear()
    }

    /** Collected variant keys of one deed of the active set, or null for an unknown deed. */
    fun collectedVariants(player: ServerPlayer, instanceIndex: Int): LongArray? {
        val instance = DeedTracker.index.instances.getOrNull(instanceIndex) ?: return null
        return DeedPlatform.current.playerData(player).progress[instance.key]?.variants?.toLongArray() ?: LongArray(0)
    }

    /** Full snapshot of the active set with the player's progress. */
    fun sendFullSync(player: ServerPlayer) {
        if (!DeedPlatform.current.canSend(player, DeedSyncPayload.TYPE)) return
        pending.remove(player.uuid)
        DeedPlatform.current.send(player, buildSnapshot(player))
    }

    fun buildSnapshot(player: ServerPlayer): DeedSyncPayload {
        val index = DeedTracker.index
        val data = DeedPlatform.current.playerData(player)

        val templates = index.templates.map { template ->
            val definition = template.definition
            DeedTemplateView(
                definitionId = template.definitionId,
                category = definition.category,
                action = definition.action,
                variantType = (definition.goal as? DeedGoal.Unique)?.variant?.type ?: "",
                hasPredicate = definition.predicate.isPresent,
                title = definition.title,
                aggregate = definition.aggregate,
                icon = definition.icon
            )
        }

        val instances = ArrayList<DeedInstanceView>(index.instances.size)
        val progress = ArrayList<DeedProgressView>()
        for (instance in index.instances) {
            instances += DeedInstanceView(instance.templateIndex, instance.targetId, instance.required)

            val entry = data.progress[instance.key] ?: continue
            val value = DeedTracker.progressValue(instance, entry)
            if (value > 0 || entry.completed) {
                progress += DeedProgressView(instance.index, value, entry.completed)
            }
        }

        // Targets that no longer exist (a removed mod) stay in the saved ledger; the client ignores unknown ids.
        val ledger = data.ledger.map { (key, count) -> LedgerView(key, count) }

        return DeedSyncPayload(
            index.set,
            DeedDefinitions.sets().toList(),
            DeedTracker.canChangeSet(player),
            templates,
            instances,
            progress,
            ledger
        )
    }

    private fun pendingFor(player: ServerPlayer): Pending =
        pending.getOrPut(player.uuid) { Pending() }
}
