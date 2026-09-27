package ru.benos.everydeeds.client.deed

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.gui.DeedToast
import ru.benos.everydeeds.client.gui.deedSetName
import ru.benos.everydeeds.deed.DeedCategory

/**
 * Turns "these deeds were completed" (the unlock packet) into toasts. A toast only appears when a whole
 * cell of the progress screen is finished: an object with all of its deeds, a whole family (every
 * copper stage), or a milestone, which gets a toast of its own kind. Several deeds finishing together
 * give one toast per cell. The deed that completes the whole set also brings the finale toast.
 */
object UnlockAnnouncer {
    /** One cell of the progress screen: its id and the objects whose deeds it stands for. */
    private class Cell(val category: DeedCategory, val id: Identifier, val members: List<Identifier>)

    fun announce(client: Minecraft, instanceIndices: List<Int>) {
        val finished = LinkedHashMap<Pair<DeedCategory, Identifier>, Cell>()
        for (instanceIndex in instanceIndices) {
            val instance = ClientDeedSnapshot.instances.getOrNull(instanceIndex) ?: continue
            val template = ClientDeedSnapshot.templates.getOrNull(instance.templateIndex) ?: continue
            val cell = cellOf(template.category, instance.targetId, template.aggregate)
            if (cell.members.all { member -> isComplete(cell.category, member) }) {
                finished[cell.category to cell.id] = cell
            }
        }

        val toasts = client.gui.toastManager()
        // Unlocks only arrive for deeds that just completed, so a complete set here means it was completed just now.
        if (ClientDeedSnapshot.isEverythingComplete) {
            val description = "toast.$MOD_ID.everything.description".translatable(deedSetName(ClientDeedSnapshot.set))
            DeedToast.show(toasts, DeedToast.Kind.EVERYTHING, ItemStack(Items.NETHER_STAR), description)
        }
        finished.values.forEach { cell -> toast(toasts, cell) }
    }

    private fun cellOf(category: DeedCategory, targetId: Identifier, milestone: Boolean): Cell {
        val single = Cell(category, targetId, listOf(targetId))
        if (milestone) return single
        val family = TargetFamilies.familyKey(category, targetId) ?: return single
        val members = ClientDeedSnapshot.targets(category).filter { id -> TargetFamilies.familyKey(category, id) == family }
        if (members.size < 2) return single
        return Cell(category, if (family in members) family else members.first(), members)
    }

    private fun isComplete(category: DeedCategory, targetId: Identifier): Boolean {
        val deeds = ClientDeedSnapshot.instancesOf(category, targetId)
        return deeds.isNotEmpty() && deeds.all(ClientDeedSnapshot::isCompleted)
    }

    /** "Complete: <icon> <name>"; a milestone shows its full title ("Broken: 10 000") in a milestone toast. */
    private fun toast(toasts: ToastManager, cell: Cell) {
        val milestone = ClientDeedSnapshot.milestones(cell.category)
            .firstOrNull { index -> ClientDeedSnapshot.instances[index].targetId == cell.id }
        if (milestone != null) {
            val instance = ClientDeedSnapshot.instances[milestone]
            val template = ClientDeedSnapshot.templates[instance.templateIndex]
            val entry = TargetEntries.milestoneEntry(template, instance)
            DeedToast.show(toasts, DeedToast.Kind.MILESTONE, iconOf(entry.preview.fallbackIcon), TargetEntries.milestoneTitle(template, instance.required))
            return
        }
        val entry = TargetEntries.entryFor(cell.category, cell.id) ?: return
        DeedToast.show(toasts, DeedToast.Kind.CELL, iconOf(entry.preview.fallbackIcon), entry.name)
    }

    /** Objects without an item form (biomes, dimensions) show a map. */
    private fun iconOf(stack: ItemStack): ItemStack = stack.takeUnless { icon -> icon.isEmpty } ?: ItemStack(Items.MAP)
}
