package ru.benos.everydeeds.client.deed

import net.minecraft.resources.Identifier
import ru.benos.everydeeds.client.gui.AchievementUiEntry
import ru.benos.everydeeds.deed.DeedCategory
import java.util.IdentityHashMap

/**
 * Objects that are one thing in different states, shown as a single cell: every oxidation stage and
 * waxed version of a copper block, every coloured candle on a cake. The cell stands for all members;
 * its details window lists them.
 */
object TargetFamilies {
    private val COPPER_STAGES = listOf("exposed_", "weathered_", "oxidized_")

    /** One family: [head] is the member that represents it (the plain copper block, the plain candle cake). */
    class Family(val head: AchievementUiEntry, val members: List<AchievementUiEntry>)

    /**
     * Replaces family members in [entries] by one family cell (at the place of its first member);
     * families with a single member stay ordinary entries. Returns the new list and the families by cell.
     */
    fun group(category: DeedCategory, entries: List<AchievementUiEntry>): Pair<List<AchievementUiEntry>, Map<AchievementUiEntry, Family>> {
        val byFamily = entries.groupBy { entry -> familyKey(category, entry.id) }
        val families = IdentityHashMap<AchievementUiEntry, Family>()
        val result = ArrayList<AchievementUiEntry>(entries.size)
        val placed = HashSet<Identifier>()
        for (entry in entries) {
            val key = familyKey(category, entry.id)
            val members = byFamily.getValue(key)
            if (key == null || members.size < 2) {
                result += entry
                continue
            }
            if (!placed.add(key)) continue
            val head = members.firstOrNull { member -> member.id == key } ?: members.first()
            val cell = head.copy()
            families[cell] = Family(head, members)
            result += cell
        }
        return result to families
    }

    /** The family an object belongs to (the id of its plain member), or null when it has none. */
    fun familyKey(category: DeedCategory, id: Identifier): Identifier? {
        if (category != DeedCategory.BLOCKS && category != DeedCategory.ITEMS) return null
        val path = id.path
        if ("copper" in path) {
            var base = path.removePrefix("waxed_")
            COPPER_STAGES.firstOrNull { stage -> base.startsWith(stage) }?.let { stage -> base = base.removePrefix(stage) }
            // The plain block is "copper_block"; its aged versions drop the suffix ("exposed_copper").
            if (base == "copper") base = "copper_block"
            return Identifier.fromNamespaceAndPath(id.namespace, base)
        }
        if (path.endsWith("candle_cake")) return Identifier.fromNamespaceAndPath(id.namespace, "candle_cake")
        return null
    }
}
