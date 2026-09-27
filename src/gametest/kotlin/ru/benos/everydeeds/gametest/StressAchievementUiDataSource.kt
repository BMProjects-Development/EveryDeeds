package ru.benos.everydeeds.gametest

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.benos.everydeeds.client.gui.AchievementUiDataSource
import ru.benos.everydeeds.client.gui.AchievementUiEntry
import ru.benos.everydeeds.client.gui.AchievementUiGroup
import ru.benos.everydeeds.client.gui.AchievementUiRequirement
import ru.benos.everydeeds.client.gui.ActionStatistic
import ru.benos.everydeeds.client.gui.DeedPreview
import ru.benos.everydeeds.client.gui.EntryCompletion
import ru.benos.everydeeds.client.gui.EntryState
import ru.benos.everydeeds.client.gui.RequirementStatus
import ru.benos.everydeeds.client.gui.displayName
import ru.benos.everydeeds.deed.DeedCategory

/**
 * Synthetic data for exercising the Tablo screen: real items plus a small and a very large fake mod
 * group, with deterministic fake progress. Lives in the test source set only.
 */
class StressAchievementUiDataSource(private val largeGroupSize: Int = 4000) : AchievementUiDataSource {
    private val groups: Map<DeedCategory, List<AchievementUiGroup>> =
        DeedCategory.entries.associateWith(::buildGroups)

    override val version: Int = 0

    override fun groups(category: DeedCategory): List<AchievementUiGroup> = groups.getValue(category)

    override fun state(entry: AchievementUiEntry): EntryState =
        when (seedOf(entry) % 3) {
            0 -> EntryState.COMPLETE
            1 -> EntryState.IN_PROGRESS
            else -> EntryState.NOT_STARTED
        }

    override fun completion(entry: AchievementUiEntry): EntryCompletion {
        val seed = seedOf(entry)
        return EntryCompletion(seed % 4, 3).let { if (it.completed > it.total) EntryCompletion(0, 3) else it }
    }

    override fun statistics(entry: AchievementUiEntry): List<ActionStatistic> =
        entry.category.actions.mapIndexed { index, action ->
            val count = (seedOf(entry) + index) % 11L
            ActionStatistic(action, count.takeIf { it > 0 })
        }

    override fun requirements(entry: AchievementUiEntry): List<AchievementUiRequirement> =
        entry.category.actions.mapIndexed { index, action ->
            val seed = seedOf(entry) + index
            val status = when (seed % 4) {
                0 -> RequirementStatus.Complete
                1 -> RequirementStatus.Unexplored
                2 -> RequirementStatus.Counting(action, seed % 5L, 5L)
                else -> RequirementStatus.RemainingUnexplored(seed % 5L + 1)
            }
            AchievementUiRequirement(action.displayName(entry.category), status)
        }

    override fun categoryCompletion(category: DeedCategory): EntryCompletion = EntryCompletion(12, 345)

    private fun seedOf(entry: AchievementUiEntry): Int = entry.id.hashCode() and Int.MAX_VALUE

    private fun buildGroups(category: DeedCategory): List<AchievementUiGroup> {
        val icons = BuiltInRegistries.ITEM.stream().filter { it != Items.AIR }.limit(200).map(::ItemStack).toList()
        val minecraft = icons.map { stack ->
            AchievementUiEntry(category, BuiltInRegistries.ITEM.getKey(stack.item), stack.hoverName, DeedPreview.Item(stack))
        }
        return listOf(
            AchievementUiGroup(Component.literal("Minecraft"), minecraft),
            syntheticGroup(category, "TestModA", 18, icons),
            syntheticGroup(category, "TestModB", largeGroupSize, icons)
        )
    }

    private fun syntheticGroup(category: DeedCategory, title: String, count: Int, icons: List<ItemStack>): AchievementUiGroup =
        AchievementUiGroup(
            Component.literal(title),
            List(count) { index ->
                val stack = icons[index % icons.size]
                AchievementUiEntry(
                    category,
                    Identifier.fromNamespaceAndPath(title.lowercase(), "${category.serializedName}_${index + 1}"),
                    Component.literal("$title #${index + 1}"),
                    DeedPreview.Item(stack)
                )
            }
        )
}
