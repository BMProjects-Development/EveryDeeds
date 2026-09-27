package ru.benos.everydeeds.tracking

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.LedgerKey
import java.util.Optional
import java.util.stream.LongStream

/**
 * Server-side progress toward one deed.
 *
 * Unique-variant goals store the variant keys actually produced by the player, so storage grows
 * with real actions, not with the theoretical size of the variant space.
 */
class DeedProgress(
    var count: Long = 0L,
    val variants: LongOpenHashSet = LongOpenHashSet(),
    var completed: Boolean = false,
    /** The reward was handed out; stays set when a harder set un-completes the deed, so it is never given twice. */
    var rewarded: Boolean = false
) {
    companion object {
        val CODEC: Codec<DeedProgress> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.optionalFieldOf("count", 0L).forGetter(DeedProgress::count),
                Codec.LONG_STREAM.optionalFieldOf("variants", LongStream.empty())
                    .forGetter { progress -> LongStream.of(*progress.variants.toLongArray()) },
                Codec.BOOL.optionalFieldOf("completed", false).forGetter(DeedProgress::completed),
                Codec.BOOL.optionalFieldOf("rewarded").forGetter { progress -> Optional.of(progress.rewarded) }
            ).apply(instance) { count, variants, completed, rewarded ->
                DeedProgress(count, LongOpenHashSet(variants.toArray()), completed, rewarded.orElse(completed))
            }
        }
    }
}

/**
 * Everything EveryDeeds remembers about one player. Persisted with the player (and kept on death).
 *
 * - [progress]: per-deed progress keyed by [ru.benos.everydeeds.data.DeedInstance.key]. Keys of other
 *   sets are kept, so switching the world's set back and forth never loses progress.
 * - [ledger]: raw action counters (broken minecraft:stone -> 37), independent of definitions.
 */
class PlayerDeedData(
    val progress: MutableMap<String, DeedProgress> = HashMap(),
    val ledger: MutableMap<LedgerKey, Long> = HashMap()
) {
    fun ledgerCount(key: LedgerKey): Long = ledger[key] ?: 0L

    fun ledgerCount(category: DeedCategory, action: DeedAction, targetId: Identifier): Long =
        ledgerCount(LedgerKey(category, action, targetId))

    fun addToLedger(key: LedgerKey, amount: Long): Long = ledger.merge(key, amount, Long::plus)!!

    fun clear() {
        progress.clear()
        ledger.clear()
    }

    companion object {
        /**
         * The ledger is stored with string keys ([LedgerKey.serialize]). Counters of actions that no longer
         * exist (renamed or removed in an update) cannot be parsed and are dropped on load.
         */
        private val LEDGER_CODEC: Codec<Map<LedgerKey, Long>> = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(
            { stored -> stored.mapNotNull { (key, count) -> LedgerKey.parse(key)?.let { parsed -> parsed to count } }.toMap() },
            { ledger -> ledger.mapKeys { (key, _) -> key.serialize() } }
        )

        val CODEC: Codec<PlayerDeedData> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, DeedProgress.CODEC).optionalFieldOf("progress", emptyMap())
                    .forGetter(PlayerDeedData::progress),
                LEDGER_CODEC.optionalFieldOf("ledger", emptyMap())
                    .forGetter(PlayerDeedData::ledger)
            ).apply(instance) { progress, ledger -> PlayerDeedData(HashMap(progress), HashMap(ledger)) }
        }
    }
}

/** Per-world choice of the active deed set. */
data class WorldDeedSettings(val activeSet: String) {
    companion object {
        const val DEFAULT_SET: String = "short"

        val DEFAULT: WorldDeedSettings = WorldDeedSettings(DEFAULT_SET)

        val CODEC: Codec<WorldDeedSettings> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.optionalFieldOf("active_set", DEFAULT_SET).forGetter(WorldDeedSettings::activeSet)
            ).apply(instance, ::WorldDeedSettings)
        }
    }
}
