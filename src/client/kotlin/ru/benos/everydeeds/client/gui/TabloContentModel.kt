package ru.benos.everydeeds.client.gui

import net.minecraft.network.chat.Component
import ru.benos.everydeeds.deed.DeedCategory
import java.util.IdentityHashMap

/**
 * What the grid shows for one category: only objects the player has started or finished, finished
 * ones first, and, on request, the not-yet-started rest at the end of each group (drawn in red).
 *
 * Rebuilt only when the data source's version, the category or the "show remaining" toggle changes.
 */
class TabloContentModel private constructor(
    val category: DeedCategory,
    val showRemaining: Boolean,
    val sourceVersion: Int,
    val groups: List<Group>,
    /** Not-started objects across all groups: the number offered by the "show remaining" button. */
    val remainingCount: Int,
    private val states: Map<AchievementUiEntry, EntryState>
) {
    class Group(
        val title: Component,
        val entries: List<AchievementUiEntry>,
        val completed: Int,
        val total: Int
    )

    fun stateOf(entry: AchievementUiEntry): EntryState = states[entry] ?: EntryState.NOT_STARTED

    fun isStale(dataSource: AchievementUiDataSource, category: DeedCategory, showRemaining: Boolean): Boolean =
        this.category != category || this.showRemaining != showRemaining || sourceVersion != dataSource.version

    companion object {
        fun build(dataSource: AchievementUiDataSource, category: DeedCategory, showRemaining: Boolean): TabloContentModel {
            val states = IdentityHashMap<AchievementUiEntry, EntryState>()
            var remaining = 0

            val groups = dataSource.groups(category).mapNotNull { group ->
                val byState = group.entries.groupBy { entry -> dataSource.state(entry).also { state -> states[entry] = state } }
                val complete = byState[EntryState.COMPLETE].orEmpty()
                val inProgress = byState[EntryState.IN_PROGRESS].orEmpty()
                val notStarted = byState[EntryState.NOT_STARTED].orEmpty()
                remaining += notStarted.size

                val visible = complete + inProgress + if (showRemaining) notStarted else emptyList()
                if (visible.isEmpty()) null else Group(group.title, visible, complete.size, group.entries.size)
            }

            return TabloContentModel(category, showRemaining, dataSource.version, groups, remaining, states)
        }
    }
}
