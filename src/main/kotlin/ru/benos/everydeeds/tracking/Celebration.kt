package ru.benos.everydeeds.tracking

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import ru.benos.everydeeds.EveryDeeds
import kotlin.math.cos
import kotlin.math.sin

/**
 * The finale: once a player has completed every deed of the active set, random fireworks go up around
 * them for a few seconds, one rocket every [LAUNCH_INTERVAL_TICKS] ticks. The rockets only put on a
 * show: their item carries a marker, and a marked rocket's explosion damages nobody (see [isHarmless]).
 */
object Celebration : PlayerTracker<Celebration.Show>() {
    private const val ROCKETS = 16
    private const val LAUNCH_INTERVAL_TICKS = 4
    private const val MIN_DISTANCE = 2.5
    private const val MAX_DISTANCE = 6.5

    private val HARMLESS_MARKER: CompoundTag = CompoundTag().apply { putBoolean("${EveryDeeds.MOD_ID}:harmless", true) }

    class Show {
        var rocketsLeft: Int = 0
    }

    override fun newState(): Show = Show()

    /** Starts the fireworks around [player] (from the beginning, if they are still going). */
    fun start(player: ServerPlayer) {
        stateOf(player).rocketsLeft = ROCKETS
    }

    override fun process(player: ServerPlayer, state: Show, tick: Int) {
        if (state.rocketsLeft <= 0 || tick % LAUNCH_INTERVAL_TICKS != 0) return
        state.rocketsLeft--
        launchAround(player)
    }

    /** Whether [rocket] is a celebration rocket, whose explosion must not hurt anyone. */
    @JvmStatic
    fun isHarmless(rocket: FireworkRocketEntity): Boolean =
        rocket.item.get(DataComponents.CUSTOM_DATA)?.matchedBy(HARMLESS_MARKER) == true

    /** A harmless rocket with random explosions at the given position, not yet added to [level]. */
    fun createRocket(level: ServerLevel, x: Double, y: Double, z: Double): FireworkRocketEntity {
        val random = level.random
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        stack.set(DataComponents.FIREWORKS, Fireworks(1 + random.nextInt(2), List(1 + random.nextInt(2)) { randomExplosion(random) }))
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(HARMLESS_MARKER))
        return FireworkRocketEntity(level, x, y, z, stack)
    }

    /** From a random spot in a ring around the player; a rocket inside a wall would burst at once, so it starts at the player then. */
    private fun launchAround(player: ServerPlayer) {
        val level = player.level()
        val random = player.random
        val angle = random.nextDouble() * Math.PI * 2
        val distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE)
        var x = player.x + cos(angle) * distance
        var z = player.z + sin(angle) * distance
        val spot = BlockPos.containing(x, player.y, z)
        if (!level.getBlockState(spot).getCollisionShape(level, spot).isEmpty) {
            x = player.x
            z = player.z
        }
        level.addFreshEntity(createRocket(level, x, player.y, z))
    }

    private fun randomExplosion(random: RandomSource): FireworkExplosion {
        val shapes = FireworkExplosion.Shape.entries
        return FireworkExplosion(
            shapes[random.nextInt(shapes.size)],
            randomColors(random, 1 + random.nextInt(3)),
            if (random.nextBoolean()) randomColors(random, 1) else IntList.of(),
            random.nextBoolean(),
            random.nextBoolean()
        )
    }

    private fun randomColors(random: RandomSource, count: Int): IntList {
        val dyes = DyeColor.entries
        return IntArrayList(IntArray(count) { dyes[random.nextInt(dyes.size)].fireworkColor })
    }
}
