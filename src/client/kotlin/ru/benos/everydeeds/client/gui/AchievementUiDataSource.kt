package ru.benos.everydeeds.client.gui

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.VariantOption
import java.util.Locale

/**
 * Read-only view of the data the Tablo screen renders. The screen only talks to this interface:
 * [ru.benos.everydeeds.client.deed.SnapshotAchievementUiDataSource] backs it with the synced
 * progress, tests back it with synthetic data.
 *
 * [groups] must return the same list instance while the structure is unchanged: the screen uses
 * reference equality to decide when to rebuild its layout. Progress is read per visible cell and on
 * hover/click, so progress changes never force a layout rebuild.
 */
interface AchievementUiDataSource {
    /** Changes whenever structure or progress changes; views derived from progress rebuild on change. */
    val version: Int

    fun groups(category: DeedCategory): List<AchievementUiGroup>

    fun state(entry: AchievementUiEntry): EntryState

    fun completion(entry: AchievementUiEntry): EntryCompletion

    /** What the player has done with the object, one line per action of its category. */
    fun statistics(entry: AchievementUiEntry): List<ActionStatistic>

    fun requirements(entry: AchievementUiEntry): List<AchievementUiRequirement>

    /** Completed / total deeds of a category, for the sidebar. */
    fun categoryCompletion(category: DeedCategory): EntryCompletion

    /** Completed / total deeds of the whole set (every category), for the bar at the bottom of the screen. */
    fun overallCompletion(): EntryCompletion =
        DeedCategory.entries.map(::categoryCompletion).fold(EntryCompletion.NONE) { sum, part ->
            EntryCompletion(sum.completed + part.completed, sum.total + part.total)
        }

    /** What is still missing for a variant goal ("all block states"), when the space can be listed. */
    fun missingVariants(requirement: AchievementUiRequirement): MissingVariants = MissingVariants.NotListable
}

val DeedCategory.displayName: Component
    get() = "gui.$MOD_ID.categories.$serializedName".translatable

/**
 * Name of the action for objects of [category]. Most actions read the same in every category; a
 * lang key `gui.everydeeds.action.<action>.<category>` gives one its own wording there: a distance is
 * travelled in a biome, but swum in water and ridden on a horse.
 */
fun DeedAction.displayName(category: DeedCategory): Component {
    val specific = "gui.$MOD_ID.action.$serializedName.${category.serializedName}"
    return if (Language.getInstance().has(specific)) specific.translatable else "gui.$MOD_ID.action.$serializedName".translatable
}

/** Display name of a deed set (a difficulty); custom sets without a translation get their id in title case. */
fun deedSetName(set: String): Component {
    val key = "gui.$MOD_ID.set.$set"
    return if (Language.getInstance().has(key)) key.translatable
    else set.split('_', '-').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }.literal
}

/** A whole number with spaces between thousands: "12 345". */
fun formatNumber(value: Long): String =
    String.format(Locale.ROOT, "%,d", value).replace(',', ' ')

/** Whether counters of this action are a measure (distance, time) rather than a number of times. */
val DeedAction.hasUnit: Boolean
    get() = this == DeedAction.TRAVELED || this == DeedAction.TIME_SPENT

/** A counter value in the action's unit: times, blocks, or a duration (time is counted in seconds). */
fun DeedAction.formatAmount(value: Long): Component =
    when (this) {
        DeedAction.TRAVELED -> "gui.$MOD_ID.unit.blocks".translatable(formatNumber(value))
        DeedAction.TIME_SPENT -> {
            val hours = value / 3600
            val minutes = value % 3600 / 60
            val seconds = value % 60
            when {
                hours > 0 -> "gui.$MOD_ID.unit.hours_minutes".translatable(hours, minutes)
                minutes > 0 -> "gui.$MOD_ID.unit.minutes_seconds".translatable(minutes, seconds)
                else -> "gui.$MOD_ID.unit.seconds".translatable(seconds)
            }
        }
        else -> formatNumber(value).literal
    }

data class AchievementUiGroup(
    val title: Component,
    val entries: List<AchievementUiEntry>
)

data class AchievementUiEntry(
    val category: DeedCategory,
    val id: Identifier,
    val name: Component,
    val preview: DeedPreview
)

/** What the cell's live preview shows. */
sealed interface DeedPreview {
    /** Icon used whenever a live render is unavailable (no level loaded, entity cannot be created). */
    val fallbackIcon: ItemStack

    data class Block(val state: BlockState, override val fallbackIcon: ItemStack) : DeedPreview

    data class Item(val stack: ItemStack) : DeedPreview {
        override val fallbackIcon: ItemStack get() = stack
    }

    data class Entity(val type: EntityType<*>, override val fallbackIcon: ItemStack) : DeedPreview

    /**
     * A biome has no model: a swatch in its biome-map colour, with a representative plant or block
     * on top when one is known ([fallbackIcon] is empty otherwise).
     */
    data class Biome(val mapColor: Int, override val fallbackIcon: ItemStack) : DeedPreview

    /** A status effect: its HUD icon (a GUI sprite). */
    data class Effect(val sprite: Identifier, override val fallbackIcon: ItemStack) : DeedPreview

    /** A structure: its explorer-map marker when the game has one, a representative block otherwise. */
    data class Structure(val mapIcon: Identifier?, override val fallbackIcon: ItemStack) : DeedPreview
}

/** How far the player got with an object; drives filtering, ordering and cell colours. */
enum class EntryState {
    COMPLETE,
    IN_PROGRESS,
    NOT_STARTED
}

/** How often an action was performed on an object; [count] is null while the action is still undiscovered. */
data class ActionStatistic(val action: DeedAction, val count: Long?)

/**
 * How far an object is: deeds completed out of its deeds, or for a milestone ([measure] set) the
 * running amount out of the target amount.
 */
data class EntryCompletion(val completed: Int, val total: Int, val measure: DeedAction? = null) {
    val isComplete: Boolean get() = total > 0 && completed >= total
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()

    companion object {
        val NONE: EntryCompletion = EntryCompletion(0, 0)
    }
}

data class AchievementUiRequirement(
    val title: Component,
    val status: RequirementStatus,
    /** The deed behind the row (-1 for rows without one); lets the UI ask for its missing variants. */
    val instanceIndex: Int = -1,
    /** Whether [AchievementUiDataSource.missingVariants] can list this goal's variants. */
    val listable: Boolean = false,
    /** For a family: the member this row stands for; clicking the row opens it. */
    val member: AchievementUiEntry? = null
)

sealed interface MissingVariants {
    data object NotListable : MissingVariants

    /** The collected variants are being fetched from the server. */
    data object Loading : MissingVariants

    data class Ready(val missing: List<VariantOption>, val total: Int) : MissingVariants
}

sealed interface RequirementStatus {
    data object Complete : RequirementStatus

    /** Nothing about this requirement has been explored yet. */
    data object Unexplored : RequirementStatus

    /** Count goal in progress. */
    data class Counting(val action: DeedAction, val current: Long, val required: Long) : RequirementStatus

    /** Variant goal in progress: [remaining] variants are still unknown. */
    data class RemainingUnexplored(val remaining: Long) : RequirementStatus

    /** A family member in progress: [done] of its [total] deeds are complete. */
    data class Partial(val done: Int, val total: Int) : RequirementStatus
}
