package ru.benos.everydeeds.client.deed

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.LedgerKey
import ru.benos.everydeeds.network.DeedInstanceView
import ru.benos.everydeeds.network.DeedProgressPayload
import ru.benos.everydeeds.network.DeedProgressView
import ru.benos.everydeeds.network.DeedSyncPayload
import ru.benos.everydeeds.network.DeedTemplateView
import ru.benos.everydeeds.network.DeedVariantsPayload
import ru.benos.everydeeds.network.RequestDeedVariantsPayload

/**
 * Client-side cache of the last synced progress. A read model for the UI only: the server stays
 * authoritative and overwrites this on every sync. Deeds are referenced by their instance index in the
 * last full sync, objects by their id, exactly as sent over the wire.
 *
 * Everything the UI asks for every frame (targets, deeds of an object, category totals) is prepared
 * when data arrives, not computed on each call.
 */
object ClientDeedSnapshot {
    var set: String = ""
        private set
    var availableSets: List<String> = emptyList()
        private set
    var canChangeSet: Boolean = false
        private set
    var templates: List<DeedTemplateView> = emptyList()
        private set
    var instances: List<DeedInstanceView> = emptyList()
        private set

    private var values: LongArray = LongArray(0)
    private var completed: BooleanArray = BooleanArray(0)
    private val ledger: HashMap<LedgerKey, Long> = HashMap()

    // Structure of the last full sync, prepared once.
    private var instancesByTarget: Map<DeedCategory, Map<Identifier, IntArray>> = emptyMap()
    private var targetsByCategory: Map<DeedCategory, List<Identifier>> = emptyMap()
    private var milestonesByCategory: Map<DeedCategory, List<Int>> = emptyMap()
    private var categoryOfInstance: IntArray = IntArray(0)

    // Category totals, kept up to date as deeds complete instead of being recounted every frame.
    private val categoryTotals = IntArray(DeedCategory.entries.size)
    private val categoryCompleted = IntArray(DeedCategory.entries.size)

    // Collected variant keys per deed, fetched on demand for the details window and dropped when the deed progresses.
    private val variantKeys: HashMap<Int, LongOpenHashSet> = HashMap()
    private val requestedVariants: HashSet<Int> = HashSet()

    /** Bumped when the set of deeds changes (full sync): cached UI structure must be rebuilt. */
    var structureVersion: Int = 0
        private set

    /** Bumped on every progress change. */
    var progressVersion: Int = 0
        private set

    val isSynced: Boolean
        get() = structureVersion > 0

    fun applySync(payload: DeedSyncPayload) {
        set = payload.set
        availableSets = payload.availableSets
        canChangeSet = payload.canChangeSet
        templates = payload.templates
        instances = payload.instances
        values = LongArray(instances.size)
        completed = BooleanArray(instances.size)
        indexStructure()

        ledger.clear()
        variantKeys.clear()
        requestedVariants.clear()
        applyProgress(payload.progress)
        payload.ledger.forEach { entry -> ledger[entry.key] = entry.count }

        structureVersion++
        progressVersion++
    }

    fun applyUpdate(payload: DeedProgressPayload) {
        applyProgress(payload.progress)
        payload.progress.forEach { entry ->
            variantKeys.remove(entry.instanceIndex)
            requestedVariants.remove(entry.instanceIndex)
        }
        payload.ledger.forEach { entry -> ledger[entry.key] = entry.count }
        progressVersion++
    }

    fun clear() {
        set = ""
        availableSets = emptyList()
        canChangeSet = false
        templates = emptyList()
        instances = emptyList()
        values = LongArray(0)
        completed = BooleanArray(0)
        instancesByTarget = emptyMap()
        targetsByCategory = emptyMap()
        milestonesByCategory = emptyMap()
        categoryOfInstance = IntArray(0)
        categoryTotals.fill(0)
        categoryCompleted.fill(0)
        ledger.clear()
        variantKeys.clear()
        requestedVariants.clear()
        structureVersion = 0
        progressVersion++
    }

    /** Groups the synced deeds by category and object; milestones are listed apart from real objects. */
    private fun indexStructure() {
        val byTarget = DeedCategory.entries.associateWith { LinkedHashMap<Identifier, MutableList<Int>>() }
        val milestones = DeedCategory.entries.associateWith { ArrayList<Int>() }
        categoryOfInstance = IntArray(instances.size)
        categoryTotals.fill(0)
        categoryCompleted.fill(0)

        instances.forEachIndexed { index, instance ->
            val template = templates[instance.templateIndex]
            byTarget.getValue(template.category).getOrPut(instance.targetId) { ArrayList(2) } += index
            if (template.aggregate) milestones.getValue(template.category) += index
            categoryOfInstance[index] = template.category.ordinal
            categoryTotals[template.category.ordinal]++
        }

        instancesByTarget = byTarget.mapValues { (_, targets) -> targets.mapValues { (_, indices) -> indices.toIntArray() } }
        milestonesByCategory = milestones
        targetsByCategory = byTarget.mapValues { (category, targets) ->
            val milestoneTargets = milestones.getValue(category).mapTo(HashSet()) { index -> instances[index].targetId }
            targets.keys.filter { id -> id !in milestoneTargets }
        }
    }

    private fun applyProgress(entries: List<DeedProgressView>) {
        for (entry in entries) {
            val index = entry.instanceIndex
            if (index !in values.indices) continue
            values[index] = entry.value
            if (completed[index] != entry.completed) {
                completed[index] = entry.completed
                categoryCompleted[categoryOfInstance[index]] += if (entry.completed) 1 else -1
            }
        }
    }

    /** Ids of all objects of [category] that have at least one deed, in index order (milestones excluded). */
    fun targets(category: DeedCategory): List<Identifier> = targetsByCategory[category].orEmpty()

    /** Milestone (aggregate) deeds of [category], as instance indices. */
    fun milestones(category: DeedCategory): List<Int> = milestonesByCategory[category].orEmpty()

    /** Deeds of one object, as instance indices. The array is shared: read it, never modify it. */
    fun instancesOf(category: DeedCategory, targetId: Identifier): IntArray =
        instancesByTarget[category]?.get(targetId) ?: EMPTY

    fun value(instanceIndex: Int): Long = values.getOrElse(instanceIndex) { 0L }

    fun isCompleted(instanceIndex: Int): Boolean = completed.getOrElse(instanceIndex) { false }

    fun template(instanceIndex: Int): DeedTemplateView? =
        instances.getOrNull(instanceIndex)?.let { instance -> templates.getOrNull(instance.templateIndex) }

    fun ledgerCount(category: DeedCategory, action: DeedAction, targetId: Identifier): Long =
        ledger[LedgerKey(category, action, targetId)] ?: 0L

    /**
     * Collected variant keys of a deed, or null while they are being fetched: the first call asks the
     * server, the answer arrives through [applyVariants].
     */
    fun collectedVariants(instanceIndex: Int): LongOpenHashSet? {
        variantKeys[instanceIndex]?.let { keys -> return keys }
        if (requestedVariants.add(instanceIndex) && ClientPlayNetworking.canSend(RequestDeedVariantsPayload.TYPE)) {
            ClientPlayNetworking.send(RequestDeedVariantsPayload(instanceIndex))
        }
        return null
    }

    fun applyVariants(payload: DeedVariantsPayload) {
        variantKeys[payload.instanceIndex] = LongOpenHashSet(payload.keys)
        progressVersion++
    }

    /** Completed and total deed counts of a whole category (milestones included). */
    fun categoryCompletion(category: DeedCategory): Pair<Int, Int> =
        categoryCompleted[category.ordinal] to categoryTotals[category.ordinal]

    /** Whether every deed of the set is completed (a set without deeds never is). */
    val isEverythingComplete: Boolean
        get() {
            val total = categoryTotals.sum()
            return total > 0 && categoryCompleted.sum() >= total
        }

    private val EMPTY = IntArray(0)
}
