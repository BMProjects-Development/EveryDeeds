package ru.benos.everydeeds.tracking

import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.phys.Vec3
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedSubject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Where players are and how they move: biome and dimension visits, time per dimension, mounting, and
 * the distance travelled per biome, swum per liquid and ridden per vehicle.
 *
 * Movement is summed every tick (cheap arithmetic), while the biome lookup, time and distance flushes
 * happen once per second, so the tracker costs next to nothing even with many players.
 */
internal object TravelTracker : PlayerTracker<TravelTracker.Travel>() {
    private const val FLUSH_INTERVAL_TICKS = 20

    /** Moves longer than this in one tick are teleports (ender pearls, portals, commands), not travel. */
    private const val MAX_STEP_BLOCKS = 16.0

    internal class Travel {
        var dimension: ResourceKey<Level>? = null
        var biome: ResourceKey<Biome>? = null
        var lastPosition: Vec3? = null

        /** The entity the player sat on last tick, to count each mounting once. */
        var vehicle: UUID? = null

        /** Horizontal distance in the current biome, whatever the way of moving. */
        val biomeDistance = Odometer()

        /** Full distance (diving too) in the liquid around the player's feet. */
        val swimDistance = Odometer()

        /** Full distance on the entity the player rides. */
        val rideDistance = Odometer()
    }

    /**
     * Distance travelled in or on one thing (a biome, a liquid, a vehicle) and not recorded yet. Only
     * whole blocks are recorded as [DeedAction.TRAVELED]; the fraction carries over to the next flush.
     */
    internal class Odometer {
        private var subject: DeedSubject? = null
        private var pending: Double = 0.0

        /** Adds a move; ignored while there is nothing to credit it to. */
        fun add(distance: Double) {
            if (subject != null) pending += distance
        }

        fun flush(player: ServerPlayer) {
            val current = subject ?: return
            val whole = floor(pending).toInt()
            if (whole <= 0) return
            DeedTracker.record(player, DeedAction.TRAVELED, current, whole)
            pending -= whole
        }

        /** Starts measuring for [next] (null: nothing); whole blocks travelled with the previous subject are recorded first. */
        fun switchTo(player: ServerPlayer, next: DeedSubject?) {
            if (next == subject) return
            flush(player)
            subject = next
            pending = 0.0
        }
    }

    override fun newState(): Travel = Travel()

    override fun process(player: ServerPlayer, state: Travel, tick: Int) {
        trackDimension(player, state)
        // The move of this tick belongs to what the player was in or on when it started, so it is
        // added before the vehicle and the liquid are updated: mounting or diving in counts from the next move.
        trackMovement(player, state)
        trackVehicle(player, state)
        trackLiquid(player, state)
        if (tick % FLUSH_INTERVAL_TICKS == 0) flush(player, state)
    }

    private fun trackDimension(player: ServerPlayer, travel: Travel) {
        val dimension = player.level().dimension()
        if (dimension == travel.dimension) return

        travel.biomeDistance.switchTo(player, null)
        travel.dimension = dimension
        travel.biome = null
        travel.lastPosition = null
        DeedTracker.record(player, DeedAction.VISITED, DeedSubject.Dimension(dimension))
    }

    private fun trackMovement(player: ServerPlayer, travel: Travel) {
        val position = player.position()
        val last = travel.lastPosition
        travel.lastPosition = position
        if (last == null) return

        val dx = position.x - last.x
        val dy = position.y - last.y
        val dz = position.z - last.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal > MAX_STEP_BLOCKS || abs(dy) > MAX_STEP_BLOCKS) return

        val full = sqrt(horizontal * horizontal + dy * dy)
        travel.biomeDistance.add(horizontal)
        travel.swimDistance.add(full)
        travel.rideDistance.add(full)
    }

    private fun trackVehicle(player: ServerPlayer, travel: Travel) {
        val vehicle = player.vehicle
        if (vehicle != null && vehicle.uuid != travel.vehicle) DeedHooks.onMounted(player, vehicle)
        travel.vehicle = vehicle?.uuid
        travel.rideDistance.switchTo(player, vehicle?.let { ridden -> DeedSubject.Entity(ridden) })
    }

    /** Swimming is moving in a liquid by oneself: sitting in a boat on the water is riding the boat. */
    private fun trackLiquid(player: ServerPlayer, travel: Travel) {
        val fluid = player.level().getFluidState(player.blockPosition())
        val liquid = if (fluid.isEmpty || player.isPassenger) null else fluid.createLegacyBlock().block
        // Flowing or still, it is the same water: the liquid block's default state stands for all of them.
        val subject = liquid?.defaultBlockState()?.takeUnless { state -> state.isAir }?.let { state -> DeedSubject.Block(state) }
        travel.swimDistance.switchTo(player, subject)
    }

    private fun flush(player: ServerPlayer, travel: Travel) {
        travel.dimension?.let { dimension -> DeedTracker.record(player, DeedAction.TIME_SPENT, DeedSubject.Dimension(dimension)) }

        // Distance of the last second is credited to the biome the player was in before this check.
        travel.biomeDistance.flush(player)
        val biome = player.level().getBiome(player.blockPosition()).unwrapKey().orElse(null)
        if (biome != null && biome != travel.biome) {
            travel.biome = biome
            travel.biomeDistance.switchTo(player, DeedSubject.Biome(biome))
            DeedTracker.record(player, DeedAction.VISITED, DeedSubject.Biome(biome))
        }

        travel.swimDistance.flush(player)
        travel.rideDistance.flush(player)
    }
}
