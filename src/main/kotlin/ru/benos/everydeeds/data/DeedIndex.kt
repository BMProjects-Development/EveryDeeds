package ru.benos.everydeeds.data

import net.minecraft.resources.Identifier
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.DeedDefinition

/**
 * One concrete deed: a definition applied to one target, e.g. "short/blocks/mined" x "minecraft:stone".
 *
 * [index] is only meaningful within one [DeedIndex] (it is how network packets reference deeds);
 * [key] is stable across restarts and is what player progress is stored under.
 */
class DeedInstance(
    val index: Int,
    val templateIndex: Int,
    val definitionId: Identifier,
    val definition: DeedDefinition,
    val targetId: Identifier,
    val required: Long,
    /** Targets whose events advance this deed: just [targetId], or every target of a milestone. */
    val triggers: Collection<Identifier> = listOf(targetId)
) {
    val key: String = storageKey(definitionId, targetId)

    companion object {
        /**
         * Progress is stored without the set name: `everydeeds:blocks/broken@minecraft:stone` is the same
         * deed in "short" and "extended", so what was done in one set counts in the others.
         */
        fun storageKey(definitionId: Identifier, targetId: Identifier): String {
            val shared = definitionId.path.substringAfter('/', definitionId.path)
            return "${definitionId.namespace}:$shared@$targetId"
        }
    }
}

/** A loaded definition as seen by the index; instances refer to it by [index]. */
class DeedTemplate(val index: Int, val definitionId: Identifier, val definition: DeedDefinition)

/** All deeds of the active set, plus fast lookup by the event that can advance them. */
class DeedIndex(
    val set: String,
    val templates: List<DeedTemplate>,
    val instances: List<DeedInstance>
) {
    private val byEvent: Map<EventKey, List<DeedInstance>> = HashMap<EventKey, MutableList<DeedInstance>>().also { map ->
        for (instance in instances) {
            for (trigger in instance.triggers) {
                map.getOrPut(EventKey(instance.definition.action, instance.definition.category, trigger)) { ArrayList(1) } += instance
            }
        }
    }

    fun instancesFor(action: DeedAction, category: DeedCategory, targetId: Identifier): List<DeedInstance> =
        byEvent[EventKey(action, category, targetId)].orEmpty()

    private data class EventKey(val action: DeedAction, val category: DeedCategory, val targetId: Identifier)

    companion object {
        val EMPTY: DeedIndex = DeedIndex("", emptyList(), emptyList())
    }
}
