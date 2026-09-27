package ru.benos.everydeeds.tracking

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedSubject
import java.util.UUID
import kotlin.math.cos

/**
 * Server-side "seen" detection, sampled a few times per second rather than every tick.
 *
 * - Blocks: whatever the crosshair points at within [BLOCK_RANGE] (fluids included).
 * - Entities: living entities within [ENTITY_RANGE], inside the view cone and in line of sight.
 *
 * Both are de-duplicated so staring at one block or walking past a herd does not inflate counters.
 */
internal object SightTracker : PlayerTracker<SightTracker.Sight>() {
    private const val BLOCK_INTERVAL_TICKS = 4
    private const val ENTITY_INTERVAL_TICKS = 10
    private const val BLOCK_RANGE = 24.0
    private const val ENTITY_RANGE = 32.0
    private const val ENTITY_RESEEN_COOLDOWN_TICKS = 20 * 60
    private val VIEW_CONE_COS = cos(Math.toRadians(55.0))

    internal class Sight {
        var lastBlockPos: BlockPos? = null
        var lastBlockState: BlockState? = null
        val entitiesSeenAt: MutableMap<UUID, Int> = HashMap()
    }

    override fun newState(): Sight = Sight()

    override fun process(player: ServerPlayer, state: Sight, tick: Int) {
        if (tick % BLOCK_INTERVAL_TICKS == 0) scanBlock(player, state)
        if (tick % ENTITY_INTERVAL_TICKS == 0) scanEntities(player, state, tick)
    }

    private fun scanBlock(player: ServerPlayer, sight: Sight) {
        val hit = player.pick(BLOCK_RANGE, 1f, true)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) return

        val pos = hit.blockPos
        val state = player.level().getBlockState(pos)
        if (state.isAir) return
        if (pos == sight.lastBlockPos && state == sight.lastBlockState) return

        sight.lastBlockPos = pos.immutable()
        sight.lastBlockState = state
        DeedTracker.record(player, DeedAction.SEEN, DeedSubject.Block(state))
    }

    private fun scanEntities(player: ServerPlayer, sight: Sight, tick: Int) {
        val eye = player.eyePosition
        val look = player.getViewVector(1f)
        val candidates = player.level().getEntitiesOfClass(
            LivingEntity::class.java,
            player.boundingBox.inflate(ENTITY_RANGE)
        ) { entity -> entity !== player && entity !is Player && entity.isAlive && !entity.isInvisible }

        sight.entitiesSeenAt.entries.removeIf { (_, seenAt) -> tick - seenAt > ENTITY_RESEEN_COOLDOWN_TICKS }

        for (entity in candidates) {
            if (entity.uuid in sight.entitiesSeenAt) continue

            val toEntity = entity.getBoundingBox().center.subtract(eye)
            val distance = toEntity.length()
            if (distance > ENTITY_RANGE || distance < 1.0e-3) continue
            if (look.dot(toEntity.scale(1.0 / distance)) < VIEW_CONE_COS) continue
            if (!player.hasLineOfSight(entity)) continue

            sight.entitiesSeenAt[entity.uuid] = tick
            DeedTracker.record(player, DeedAction.SEEN, DeedSubject.Entity(entity))
        }
    }
}
