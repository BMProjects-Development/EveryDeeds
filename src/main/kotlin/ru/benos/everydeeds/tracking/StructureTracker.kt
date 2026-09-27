package ru.benos.everydeeds.tracking

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import ru.benos.everydeeds.config.ServerConfigStore
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedSubject

/**
 * Structures: seen (a piece lies along the player's line of sight), visited (the player stands
 * inside a piece) and looted (a loot container opened in or right next to the structure).
 *
 * Every structure instance counts once per session for "seen" and once per entry for "visited", so
 * staring at a village or walking around inside it does not inflate the counters. Only loaded chunks
 * are inspected: tracking never loads or generates terrain.
 */
internal object StructureTracker : PlayerTracker<StructureTracker.Structures>() {
    private const val SIGHT_INTERVAL_TICKS = 10
    private const val VISIT_INTERVAL_TICKS = 20
    private const val SIGHT_STEP_BLOCKS = 4.0

    /** Loot containers sit inside the structure, but the player may stand just outside its bounds. */
    private const val LOOT_REACH_BLOCKS = 8

    /** One structure instance: its type and the chunk of its start. */
    internal data class Instance(val structure: ResourceKey<Structure>, val startChunk: Long)

    internal class Structures {
        val seen: MutableSet<Instance> = HashSet()
        var inside: Set<Instance> = emptySet()
    }

    override fun newState(): Structures = Structures()

    override fun process(player: ServerPlayer, state: Structures, tick: Int) {
        if (tick % SIGHT_INTERVAL_TICKS == 0) scanSight(player, state)
        if (tick % VISIT_INTERVAL_TICKS == 0) scanVisit(player, state)
    }

    /** A loot table was rolled for [player] (chest, chest boat or minecart, brushed block). */
    @JvmStatic
    fun onLootGenerated(player: ServerPlayer) {
        val level = player.level()
        val pos = player.blockPosition()
        if (!level.isLoaded(pos)) return
        val manager = level.structureManager()
        for (structure in manager.getAllStructuresAt(pos).keys) {
            val start = manager.getStructureAt(pos, structure)
            if (!start.isValid || !start.boundingBox.inflatedBy(LOOT_REACH_BLOCKS).isInside(pos)) continue
            key(level, structure)?.let { key -> DeedTracker.record(player, DeedAction.LOOTED, DeedSubject.Structure(key)) }
        }
    }

    /** Samples the view ray every few blocks up to the first solid block or the configured distance. */
    private fun scanSight(player: ServerPlayer, state: Structures) {
        val level = player.level()
        val distance = ServerConfigStore.current.structureSightDistance.toDouble()
        val eye = player.eyePosition
        val direction = player.getViewVector(1f)
        val end = eye.add(direction.scale(distance))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val length = hit.location.distanceTo(eye)

        var travelled = SIGHT_STEP_BLOCKS
        while (travelled <= length + 0.5) {
            val pos = BlockPos.containing(eye.add(direction.scale(minOf(travelled, length))))
            for (instance in instancesWithPieceAt(level, pos)) {
                if (state.seen.add(instance)) {
                    DeedTracker.record(player, DeedAction.SEEN, DeedSubject.Structure(instance.structure))
                }
            }
            travelled += SIGHT_STEP_BLOCKS
        }
    }

    private fun scanVisit(player: ServerPlayer, state: Structures) {
        val now = instancesWithPieceAt(player.level(), player.blockPosition()).toSet()
        for (instance in now) {
            if (instance !in state.inside) {
                // Being inside also means having seen it.
                if (state.seen.add(instance)) DeedTracker.record(player, DeedAction.SEEN, DeedSubject.Structure(instance.structure))
                DeedTracker.record(player, DeedAction.VISITED, DeedSubject.Structure(instance.structure))
            }
        }
        state.inside = now
    }

    private fun instancesWithPieceAt(level: ServerLevel, pos: BlockPos): List<Instance> {
        if (!level.isLoaded(pos)) return emptyList()
        val manager = level.structureManager()
        return manager.getAllStructuresAt(pos).keys.mapNotNull { structure ->
            val start: StructureStart = manager.getStructureWithPieceAt(pos.x, pos.y, pos.z, structure)
            if (!start.isValid) return@mapNotNull null
            key(level, structure)?.let { key -> Instance(key, start.chunkPos.pack()) }
        }
    }

    private fun key(level: ServerLevel, structure: Structure): ResourceKey<Structure>? =
        level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(structure).orElse(null)
}
