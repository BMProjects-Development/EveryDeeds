package ru.benos.everydeeds.client.deed

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.gui.AchievementUiDataSource
import ru.benos.everydeeds.client.gui.AchievementUiEntry
import ru.benos.everydeeds.client.gui.AchievementUiGroup
import ru.benos.everydeeds.client.gui.AchievementUiRequirement
import ru.benos.everydeeds.client.gui.ActionStatistic
import ru.benos.everydeeds.client.gui.EntryCompletion
import ru.benos.everydeeds.client.gui.EntryState
import ru.benos.everydeeds.client.gui.MissingVariants
import ru.benos.everydeeds.client.gui.RequirementStatus
import ru.benos.everydeeds.client.gui.displayName
import ru.benos.everydeeds.client.gui.formatAmount
import ru.benos.everydeeds.client.gui.hasUnit
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.VariantOption
import ru.benos.everydeeds.deed.VariantOptions
import ru.benos.everydeeds.network.DeedTemplateView
import java.util.IdentityHashMap

/**
 * [AchievementUiDataSource] backed by the synced [ClientDeedSnapshot]. Groups, milestone and family
 * lookups and variant lists are cached per full sync (the snapshot's structure version).
 */
class SnapshotAchievementUiDataSource(
    private val snapshot: ClientDeedSnapshot = ClientDeedSnapshot
) : AchievementUiDataSource {
    private var cachedVersion: Int = -1
    private val cachedGroups: MutableMap<DeedCategory, List<AchievementUiGroup>> = HashMap()
    private val milestoneIndex: MutableMap<AchievementUiEntry, Int> = IdentityHashMap()
    private val families: MutableMap<AchievementUiEntry, TargetFamilies.Family> = IdentityHashMap()

    /** Listable variant values per (variant type, target), computed once with the client's registries. */
    private val optionCache: MutableMap<Pair<String, Identifier>, List<VariantOption>?> = HashMap()

    override val version: Int
        get() = snapshot.progressVersion

    /** Complete when every deed is done; in progress once anything was done with the object at all. */
    override fun state(entry: AchievementUiEntry): EntryState {
        families[entry]?.let { family ->
            val states = family.members.map(::state)
            return when {
                states.all { state -> state == EntryState.COMPLETE } -> EntryState.COMPLETE
                states.any { state -> state != EntryState.NOT_STARTED } -> EntryState.IN_PROGRESS
                else -> EntryState.NOT_STARTED
            }
        }
        val instances = snapshot.instancesOf(entry.category, entry.id)
        if (instances.isNotEmpty() && instances.all(snapshot::isCompleted)) return EntryState.COMPLETE

        val touched = instances.any { index -> snapshot.value(index) > 0 || snapshot.isCompleted(index) } ||
            entry.category.actions.any { action -> snapshot.ledgerCount(entry.category, action, entry.id) > 0 }
        return if (touched) EntryState.IN_PROGRESS else EntryState.NOT_STARTED
    }

    override fun groups(category: DeedCategory): List<AchievementUiGroup> {
        dropCachesOfOldSync()
        return cachedGroups.getOrPut(category) { buildGroups(category) }
    }

    override fun completion(entry: AchievementUiEntry): EntryCompletion {
        families[entry]?.let { family ->
            val parts = family.members.map(::completion)
            return EntryCompletion(parts.sumOf { part -> part.completed }, parts.sumOf { part -> part.total })
        }
        // A milestone is one deed: its progress is the amount collected towards the target.
        milestoneIndex[entry]?.let { index ->
            val instance = snapshot.instances[index]
            val action = snapshot.templates[instance.templateIndex].action
            val required = instance.required.coerceAtMost(Int.MAX_VALUE.toLong())
            return EntryCompletion(snapshot.value(index).coerceAtMost(required).toInt(), required.toInt(), action)
        }
        val instances = instancesOf(entry)
        if (instances.isEmpty()) return EntryCompletion.NONE
        return EntryCompletion(instances.count(snapshot::isCompleted), instances.size)
    }

    override fun statistics(entry: AchievementUiEntry): List<ActionStatistic> {
        // A family adds up what was done with each of its members.
        families[entry]?.let { family ->
            val perMember = family.members.map(::statistics)
            return entry.category.actions.mapNotNull { action ->
                val counts = perMember.mapNotNull { statistics -> statistics.firstOrNull { statistic -> statistic.action == action } }
                if (counts.isEmpty()) null
                else ActionStatistic(action, counts.sumOf { statistic -> statistic.count ?: 0L }.takeIf { it > 0 })
            }
        }
        // A milestone's statistic is its running total.
        milestoneIndex[entry]?.let { index ->
            val action = snapshot.template(index)?.action ?: return emptyList()
            return listOf(ActionStatistic(action, snapshot.value(index).takeIf { it > 0 }))
        }
        // Only actions that apply to this object: those with deeds, or already done anyway (a stone is never "grown").
        val withDeeds = instancesOf(entry).toList().mapNotNull { index: Int -> snapshot.template(index)?.action }.toSet()
        return entry.category.actions.mapNotNull { action ->
            val count = snapshot.ledgerCount(entry.category, action, entry.id)
            if (count <= 0 && action !in withDeeds) null else ActionStatistic(action, count.takeIf { it > 0 })
        }
    }

    /**
     * Ordered like the category's actions (and the statistics block), then by definition. A family lists its members.
     * A deed stays a "?" until its action has been done with the object at least once: the list hints that there
     * is more to find out without telling what. Milestones always show their goal.
     */
    override fun requirements(entry: AchievementUiEntry): List<AchievementUiRequirement> {
        families[entry]?.let { family ->
            return family.members.map { member ->
                val completion = completion(member)
                val status = when {
                    completion.isComplete -> RequirementStatus.Complete
                    state(member) == EntryState.NOT_STARTED -> RequirementStatus.Unexplored
                    else -> RequirementStatus.Partial(completion.completed, completion.total)
                }
                AchievementUiRequirement(member.name, status, member = member)
            }
        }
        return instancesOf(entry).sortedWith(
            compareBy<Int> { index -> snapshot.template(index)?.action?.ordinal ?: Int.MAX_VALUE }
                .thenBy { index -> snapshot.template(index)?.definitionId?.toString().orEmpty() }
        ).map { instanceIndex ->
            val instance = snapshot.instances[instanceIndex]
            val template = snapshot.templates[instance.templateIndex]
            val value = snapshot.value(instanceIndex)

            val discovered = template.aggregate || value > 0L || snapshot.isCompleted(instanceIndex) ||
                snapshot.ledgerCount(entry.category, template.action, instance.targetId) > 0L
            if (!discovered) return@map AchievementUiRequirement(UNDISCOVERED, RequirementStatus.Unexplored, instanceIndex)

            val status = when {
                snapshot.isCompleted(instanceIndex) -> RequirementStatus.Complete
                value <= 0L -> RequirementStatus.Unexplored
                template.variantType.isEmpty() -> RequirementStatus.Counting(template.action, value, instance.required)
                else -> RequirementStatus.RemainingUnexplored(instance.required - value)
            }
            val listable = template.variantType.isNotEmpty() && options(template.variantType, instance.targetId) != null
            AchievementUiRequirement(requirementTitle(template, instance.required), status, instanceIndex, listable)
        }
    }

    override fun categoryCompletion(category: DeedCategory): EntryCompletion {
        val (done, total) = snapshot.categoryCompletion(category)
        return EntryCompletion(done, total)
    }

    override fun missingVariants(requirement: AchievementUiRequirement): MissingVariants {
        if (!requirement.listable) return MissingVariants.NotListable
        val instance = snapshot.instances.getOrNull(requirement.instanceIndex) ?: return MissingVariants.NotListable
        val template = snapshot.templates.getOrNull(instance.templateIndex) ?: return MissingVariants.NotListable
        val all = options(template.variantType, instance.targetId) ?: return MissingVariants.NotListable
        val collected = snapshot.collectedVariants(requirement.instanceIndex) ?: return MissingVariants.Loading
        return MissingVariants.Ready(all.filter { option -> option.key !in collected }, all.size)
    }

    private fun options(variantType: String, targetId: Identifier): List<VariantOption>? {
        dropCachesOfOldSync()
        return optionCache.getOrPut(variantType to targetId) {
            val registries = Minecraft.getInstance().connection?.registryAccess() ?: return null
            VariantOptions.enumerate(variantType, targetId, registries, Minecraft.getInstance().level)
        }
    }

    /** A new full sync (another set, another world) invalidates everything built from the previous one. */
    private fun dropCachesOfOldSync() {
        if (cachedVersion == snapshot.structureVersion) return
        cachedGroups.clear()
        milestoneIndex.clear()
        families.clear()
        optionCache.clear()
        cachedVersion = snapshot.structureVersion
    }

    private fun instancesOf(entry: AchievementUiEntry): IntArray =
        snapshot.instancesOf(entry.category, entry.id)

    private fun requirementTitle(template: DeedTemplateView, required: Long): Component {
        if (template.title.isPresent) return template.title.get()

        val action = template.action.displayName(template.category)
        val base = when {
            template.aggregate -> TargetEntries.milestoneTitle(template, required).copy()
            template.variantType.isNotEmpty() -> "gui.$MOD_ID.goal.${template.variantType}".translatable(action, required)
            template.action.hasUnit -> "gui.$MOD_ID.goal.amount".translatable(action, template.action.formatAmount(required))
            required > 1 -> "gui.$MOD_ID.goal.count".translatable(action, required)
            else -> action.copy()
        }
        return if (template.hasPredicate) base.append(" *".literal) else base
    }

    private fun buildGroups(category: DeedCategory): List<AchievementUiGroup> {
        val (entries, grouped) = TargetFamilies.group(
            category, snapshot.targets(category).mapNotNull { targetId -> TargetEntries.entryFor(category, targetId) }
        )
        families.putAll(grouped)

        // Milestones first, in their own group: by action like everything else, each action's tiers from small to large.
        val milestoneOrder = compareBy<Int> { index -> snapshot.template(index)?.action?.ordinal ?: Int.MAX_VALUE }
            .thenBy { index -> snapshot.instances[index].required }
        val milestoneEntries = snapshot.milestones(category).sortedWith(milestoneOrder).map { index ->
            val instance = snapshot.instances[index]
            TargetEntries.milestoneEntry(snapshot.templates[instance.templateIndex], instance).also { entry -> milestoneIndex[entry] = index }
        }
        val milestoneGroup = if (milestoneEntries.isEmpty()) emptyList()
        else listOf(AchievementUiGroup("gui.$MOD_ID.milestones".translatable, milestoneEntries))

        return milestoneGroup + entries
            .groupBy { entry -> entry.id.namespace }
            .toSortedMap(compareBy<String> { namespace -> namespace != "minecraft" }.thenBy { it })
            .map { (namespace, namespaceEntries) -> AchievementUiGroup(modName(namespace), namespaceEntries) }
    }

    private fun modName(namespace: String): Component =
        FabricLoader.getInstance().getModContainer(namespace)
            .map { container -> container.metadata.name }
            .orElseGet { namespace.split('_', '-').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) } }
            .literal

    private companion object {
        /** Title of a deed whose action is still unknown to the player. */
        val UNDISCOVERED: Component = "?".literal
    }
}
