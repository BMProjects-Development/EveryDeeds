package ru.benos.everydeeds.tracking

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.Permissions
import net.minecraft.util.Prediction
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.data.DeedDefinitions
import ru.benos.everydeeds.data.DeedIndex
import ru.benos.everydeeds.data.DeedIndexBuilder
import ru.benos.everydeeds.data.DeedInstance
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.DeedReward
import ru.benos.everydeeds.deed.DeedSubject
import ru.benos.everydeeds.deed.LedgerKey
import ru.benos.everydeeds.deed.PredicateContext
import ru.benos.everydeeds.deed.VariantContext
import ru.benos.everydeeds.platform.DeedPlatform

/**
 * The single entry point every event source (mixins, loader events, the sight scanner) funnels into.
 * Server-authoritative: this is the only place that decides whether a deed progressed or completed.
 */
object DeedTracker {
    @Volatile
    var index: DeedIndex = DeedIndex.EMPTY
        private set

    /** Rebuilds the index for the world's active set and resyncs everyone. Call after reloads and set changes. */
    fun rebuild(server: MinecraftServer) {
        val set = DeedPlatform.current.worldSettings(server).activeSet
        if (set !in DeedDefinitions.sets()) {
            EveryDeeds.LOGGER.warn("Active deed set '{}' has no definitions (available: {})", set, DeedDefinitions.sets())
        }

        index = DeedIndexBuilder.build(server, set)
        server.playerList.players.forEach { player ->
            reconcile(player)
            DeedSync.sendFullSync(player)
        }
    }

    fun clear() {
        index = DeedIndex.EMPTY
    }

    /** Same rule as `/everydeeds set`: game-master permission. */
    fun canChangeSet(player: ServerPlayer): Boolean =
        player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)

    /** Switches the world's active set and resyncs everyone. Returns false for unknown sets. */
    fun switchSet(server: MinecraftServer, set: String): Boolean {
        if (set !in DeedDefinitions.sets()) return false
        DeedPlatform.current.setWorldSettings(server, WorldDeedSettings(set))
        rebuild(server)
        return true
    }

    /**
     * Records that [player] performed [action] on [subject] [amount] times.
     * Creative and spectator players are not tracked: the challenge only counts survival play.
     */
    fun record(player: ServerPlayer, action: DeedAction, subject: DeedSubject, amount: Int = 1) {
        if (amount <= 0) return
        apply(player, action, subject, amount)
    }

    /**
     * Records that [subject] is present for [player] (an item sitting in the inventory) without counting
     * it again: the counter only moves from 0 to 1, while variant goals still collect what is present.
     * Real acquisitions (pickups, crafts) are counted by [record].
     */
    fun recordPresence(player: ServerPlayer, action: DeedAction, subject: DeedSubject) {
        if (player.isSpectator || player.isCreative) return
        val alreadyCounted = DeedPlatform.current.playerData(player).ledgerCount(LedgerKey(subject.category, action, subject.targetId)) > 0
        apply(player, action, subject, if (alreadyCounted) 0 else 1)
    }

    /** [amount] may be 0: then counters stay as they are and only variants are collected. */
    private fun apply(player: ServerPlayer, action: DeedAction, subject: DeedSubject, amount: Int) {
        if (player.isSpectator || player.isCreative) return
        if (subject.category !in action.categories) return

        val data = DeedPlatform.current.playerData(player)
        val targetId = subject.targetId
        if (amount > 0) {
            val ledgerKey = LedgerKey(subject.category, action, targetId)
            data.addToLedger(ledgerKey, amount.toLong())
            DeedSync.markLedgerDirty(player, ledgerKey)
        }

        val candidates = index.instancesFor(action, subject.category, targetId)
        if (candidates.isEmpty()) return

        val predicateContext = PredicateContext(subject, player)
        val variantContext = VariantContext(player.level().server.registryAccess(), player.level())
        for (instance in candidates) {
            if (data.progress[instance.key]?.completed == true) continue
            // Checked before any progress entry exists, so failed conditions leave nothing behind in the save.
            if (!instance.definition.predicate.map { predicate -> predicate.test(predicateContext) }.orElse(true)) continue
            val progress = data.progress.getOrPut(instance.key) { DeedProgress() }

            val changed = when (val goal = instance.definition.goal) {
                is DeedGoal.Count -> {
                    progress.count += amount
                    amount > 0
                }
                is DeedGoal.Unique -> {
                    var added = false
                    for (key in goal.variant.keys(subject, variantContext)) {
                        added = progress.variants.add(key) || added
                    }
                    added
                }
            }
            if (!changed) continue

            DeedSync.markProgressDirty(player, instance.index)
            if (progressValue(instance, progress) >= instance.required) {
                complete(player, instance, progress)
            }
        }
    }

    fun progressValue(instance: DeedInstance, progress: DeedProgress): Long =
        when (instance.definition.goal) {
            is DeedGoal.Count -> progress.count
            is DeedGoal.Unique -> progress.variants.size.toLong()
        }

    /**
     * Brings the player's progress in line with the active set; run after every rebuild and on join.
     *
     * - Plain counters (count goal, no condition) are the raw action counts, so they are raised to what
     *   the ledger already holds: work done in another set, or before this deed existed, counts here too.
     * - Completion follows the thresholds of this set: a lower one completes the deed (reward once),
     *   a higher one (a harder set sharing the deed) makes it incomplete again. No toasts: nothing new happened.
     */
    fun reconcile(player: ServerPlayer) {
        val data = DeedPlatform.current.playerData(player)
        for (instance in index.instances) {
            val definition = instance.definition
            var progress = data.progress[instance.key]
            if (definition.goal is DeedGoal.Count && definition.predicate.isEmpty) {
                val counted = instance.triggers.sumOf { target -> data.ledgerCount(LedgerKey(definition.category, definition.action, target)) }
                if (counted > (progress?.count ?: 0L)) {
                    progress = progress ?: DeedProgress().also { created -> data.progress[instance.key] = created }
                    progress.count = counted
                }
            }
            if (progress == null) continue

            val done = progressValue(instance, progress) >= instance.required
            if (done && !progress.completed) {
                progress.completed = true
                rewardOnce(player, instance, progress)
            } else if (!done && progress.completed) {
                progress.completed = false
            }
        }
    }

    private fun complete(player: ServerPlayer, instance: DeedInstance, progress: DeedProgress) {
        progress.completed = true
        DeedSync.markUnlocked(player, instance.index)
        rewardOnce(player, instance, progress)
        if (isEverythingComplete(player)) Celebration.start(player)
    }

    /** Whether [player] has completed every deed of the active set. */
    fun isEverythingComplete(player: ServerPlayer): Boolean {
        val progress = DeedPlatform.current.playerData(player).progress
        return index.instances.isNotEmpty() && index.instances.all { instance -> progress[instance.key]?.completed == true }
    }

    private fun rewardOnce(player: ServerPlayer, instance: DeedInstance, progress: DeedProgress) {
        if (progress.rewarded) return
        progress.rewarded = true
        instance.definition.reward.ifPresent { reward -> grant(player, reward) }
    }

    private fun grant(player: ServerPlayer, reward: DeedReward) {
        if (reward.experience > 0) player.giveExperiencePoints(reward.experience)

        for (template in reward.items) {
            val stack = template.create()
            if (!player.addItem(stack) && !stack.isEmpty) {
                player.drop(stack, false, Prediction.SERVER_ONLY)?.let { drop ->
                    drop.setNoPickUpDelay()
                    drop.setTarget(player.uuid)
                }
            }
        }

        reward.function.ifPresent { functionId ->
            val server = player.level().server
            server.functions.get(functionId).ifPresentOrElse(
                { function ->
                    server.functions.execute(
                        function,
                        player.createCommandSourceStack().withSuppressedOutput().withPermission(LevelBasedPermissionSet.GAMEMASTER)
                    )
                },
                { EveryDeeds.LOGGER.warn("Deed reward function '{}' does not exist", functionId) }
            )
        }
    }
}
